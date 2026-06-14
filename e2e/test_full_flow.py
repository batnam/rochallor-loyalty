"""End-to-end "customer journey" across the whole loyalty platform, black-box against the live
docker stack with real Postgres + Kafka and WireMock standing in for the external Payment Hub /
voucher partner.

The single ordered flow (each step asserts the hop's observable contract — HTTP + Kafka):

  1. admin authors a CARD_SPEND earning rule (admin-bff) and activates it (earning, approval-gated)
  2. customer opts into the program (mobile-bff)  -> member_id == customer_id == 1
  3. a settled card spend is produced as an Ingress Event on Kafka
  4. bridge translates it    -> assert loyalty.earn.translated.v1
  5. earning + core award it -> assert loyalty.ledger.v1 (PointsEarned) + mobile-bff balance + SILVER tier
  6. CASHBACK redemption (sync) -> WireMock Payment Hub -> commit; balance drops
  7. THIRD_PARTY_VOUCHER redemption (async) -> WireMock provision -> FULFILLING ->
     test fires the HMAC-signed partner webhook to the bridge -> resume -> commit; balance drops
"""
from __future__ import annotations

import hashlib
import hmac
import json
import time

import pytest
import requests

import config


# Unique idempotency root per run so a re-run against a non-fresh stack can't silently no-op.
RUN = str(int(time.time()))
CARD_SPEND_EVENT_ID = f"e2e:cardspend:{RUN}"


def poll(fn, predicate, timeout_s=60.0, interval=1.0, what="condition"):
    """Re-call `fn` until `predicate(result)` is truthy; return that result or fail."""
    deadline = time.time() + timeout_s
    last = None
    while time.time() < deadline:
        last = fn()
        if predicate(last):
            return last
        time.sleep(interval)
    raise AssertionError(f"{what} not satisfied within {timeout_s:.0f}s; last={last!r}")


@pytest.fixture(scope="module")
def journey():
    """Mutable bag of ids discovered as the ordered steps run."""
    return {}


# --- step 1: author + activate the earning rule ------------------------------

def test_01_author_and_activate_card_spend_rule(http, journey):
    # Discover the CARD_SPEND earn source id via the admin facade.
    r = http.get(
        f"{config.ADMIN_BFF}/programs/{config.PROGRAM_ID}/earn-sources",
        params={"userId": config.ADMIN_USER, "roles": config.ADMIN_ROLE},
    )
    assert r.status_code == 200, r.text
    sources = {s["earnSourceCode"]: s["earnSourceId"] for s in r.json()}
    assert "CARD_SPEND" in sources, sources
    earn_source_id = sources["CARD_SPEND"]

    # Author the rule (DRAFT) through admin-bff: 1 point per 1 currency unit of card spend.
    dsl = {
        "dslVersion": 1,
        "earnSource": "CARD_SPEND",
        "rows": [{"when": {"amount": "*"}, "earn": {"type": "RATE", "perAmount": 1, "points": 1}}],
    }
    r = http.post(
        f"{config.ADMIN_BFF}/programs/{config.PROGRAM_ID}/rules",
        params={"userId": config.ADMIN_USER, "roles": config.ADMIN_ROLE},
        data=json.dumps(
            {"earnSourceId": earn_source_id, "dslJson": dsl,
             "effectiveFrom": "2026-01-01T00:00:00Z", "effectiveTo": None, "campaignId": None}
        ),
    )
    assert r.status_code == 201, r.text
    rule = r.json()
    rule_id = rule["ruleId"]
    assert rule["status"] == "DRAFT"

    # Activate. admin-bff's RuleStatusRequest can't carry the bepApprovalRef the earning service
    # requires for ACTIVE, so we call earning's own approval-gated API directly (still a real
    # platform write path; see README "Findings").
    r = http.patch(
        f"{config.EARNING}/rules/{rule_id}",
        headers={"X-Actor-Id": config.ADMIN_USER},
        data=json.dumps({"status": "ACTIVE", "bepApprovalRef": f"BEP-E2E-{RUN}"}),
    )
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "ACTIVE"
    journey["rule_id"] = rule_id


# --- step 2: opt the customer into the program -------------------------------

def test_02_opt_in_member(http, journey):
    r = http.post(
        f"{config.MOBILE_BFF}/me/programs/{config.PROGRAM_ID}/opt-in",
        data=json.dumps({"customerId": config.CUSTOMER_ID, "tcsVersion": config.TCS_VERSION}),
    )
    assert r.status_code == 200, r.text
    enrolled = r.json()
    assert enrolled["optInStatus"] in ("ACTIVE", "OPTED_IN", "ENROLLED"), enrolled
    assert (enrolled.get("redeemableBalance") or 0) == 0

    # Guard the customerId==memberId seam the redemption leg depends on (Finding 3).
    r = http.get(
        f"{config.CORE}/members/lookup",
        params={"programId": config.PROGRAM_ID, "customerId": config.CUSTOMER_ID},
    )
    assert r.status_code == 200, r.text
    member_id = r.json()["memberId"]
    assert member_id == config.CUSTOMER_ID, (
        f"member_id ({member_id}) != customer_id ({config.CUSTOMER_ID}); the DB was not clean. "
        "Run without E2E_ASSUME_RUNNING, or reset the volume."
    )
    journey["member_id"] = member_id


# --- step 3-5: ingress card spend -> translate -> earn -> balance + tier ------

def test_03_produce_card_spend_ingress_event(bus):
    event = {
        "eventId": CARD_SPEND_EVENT_ID,
        "customerId": config.CUSTOMER_ID,
        "occurredAt": "2026-05-29T10:30:00Z",
        "amount": config.CARD_SPEND_AMOUNT,
        "currency": "USD",
        "mcc": "5411",
        "merchantId": "M-E2E-001",
        "schemaVersion": 1,
    }
    bus.produce_json(
        config.TOPIC_INGRESS_CARD_SPEND,
        key=str(config.CUSTOMER_ID),
        value=event,
        headers={"source": "e2e-harness", "traceparent": "00-e2e0000000000000000000000000001-0000000000000001-01"},
    )


def test_04_bridge_translates_to_canonical_earn_event(bus):
    translated = bus.await_message(
        config.TOPIC_EARN_TRANSLATED,
        match=lambda m: m.get("eventId") == CARD_SPEND_EVENT_ID,
        timeout_s=60,
    )
    assert translated["source"] == "CARD_SPEND"
    assert translated["customerId"] == config.CUSTOMER_ID
    assert translated["payload"]["amount"] == config.CARD_SPEND_AMOUNT


def test_05_core_emits_points_earned_and_balance_and_tier(bus, http, journey):
    ledger_evt = bus.await_message(
        config.TOPIC_LEDGER,
        match=lambda m: m.get("eventType") == "PointsEarned"
        and str(m.get("sourceRef", "")).startswith(CARD_SPEND_EVENT_ID)
        and m.get("memberId") == journey["member_id"],
        timeout_s=60,
    )
    assert ledger_evt["redeemableDelta"] == config.EXPECTED_EARN_POINTS

    balance = poll(
        lambda: http.get(
            f"{config.MOBILE_BFF}/me/programs/{config.PROGRAM_ID}/balance",
            params={"customerId": config.CUSTOMER_ID},
        ).json(),
        predicate=lambda b: b.get("redeemableBalance") == config.EXPECTED_EARN_POINTS,
        what="mobile-bff redeemable balance reflects the earn",
    )
    assert balance["qualifyingBalance"] == config.EXPECTED_EARN_POINTS

    tier = http.get(
        f"{config.MOBILE_BFF}/me/programs/{config.PROGRAM_ID}/tier",
        params={"customerId": config.CUSTOMER_ID},
    ).json()
    assert tier["currentTier"]["name"] == "SILVER", tier


# --- step 6: CASHBACK redemption (sync, via WireMock Payment Hub) ------------

def _eligible_rewards(http):
    r = http.get(
        f"{config.MOBILE_BFF}/me/programs/{config.PROGRAM_ID}/rewards",
        params={"customerId": config.CUSTOMER_ID},
    )
    assert r.status_code == 200, r.text
    return {item["rewardTypeCode"]: item for item in r.json()["items"]}


def _balance(http):
    return http.get(
        f"{config.MOBILE_BFF}/me/programs/{config.PROGRAM_ID}/balance",
        params={"customerId": config.CUSTOMER_ID},
    ).json()["redeemableBalance"]


def test_06_cashback_redemption_commits(http, journey):
    rewards = _eligible_rewards(http)
    assert "CASHBACK" in rewards, rewards
    reward = rewards["CASHBACK"]
    before = _balance(http)

    r = http.post(
        f"{config.MOBILE_BFF}/redemptions",
        headers={"Idempotency-Key": f"e2e-cashback-{RUN}"},
        data=json.dumps(
            {"customerId": config.CUSTOMER_ID, "accountNumber": config.CASA_ACCOUNT,
             "programId": config.PROGRAM_ID, "rewardId": reward["rewardId"]}
        ),
    )
    assert r.status_code == 200, f"expected sync 200 commit, got {r.status_code}: {r.text}"
    body = r.json()
    assert body["status"] == "COMPLETED", body
    assert body["externalRef"], body  # Payment Hub disbursement ref (from WireMock)

    after = poll(lambda: _balance(http),
                 predicate=lambda b: b == before - reward["pointCost"],
                 what="balance drops by the cashback point cost")
    assert after == before - reward["pointCost"]
    journey["after_cashback_balance"] = after


# --- step 7: THIRD_PARTY_VOUCHER redemption (async, webhook-resumed) ---------

def _sign_webhook(body_str: str, timestamp: int) -> str:
    mac = hmac.new(
        config.VOUCHER_WEBHOOK_HMAC_SECRET.encode("utf-8"),
        f"{timestamp}.{body_str}".encode("utf-8"),
        hashlib.sha256,
    )
    return mac.hexdigest()


def test_07_voucher_redemption_resumes_via_webhook_and_commits(http, journey):
    rewards = _eligible_rewards(http)
    assert "THIRD_PARTY_VOUCHER" in rewards, rewards
    reward = rewards["THIRD_PARTY_VOUCHER"]
    before = _balance(http)

    # Submit -> partner provisioning is async, so the saga parks at FULFILLING (HTTP 202).
    r = http.post(
        f"{config.MOBILE_BFF}/redemptions",
        headers={"Idempotency-Key": f"e2e-voucher-{RUN}"},
        data=json.dumps(
            {"customerId": config.CUSTOMER_ID, "accountNumber": config.CASA_ACCOUNT,
             "programId": config.PROGRAM_ID, "rewardId": reward["rewardId"]}
        ),
    )
    assert r.status_code == 202, f"expected async 202 FULFILLING, got {r.status_code}: {r.text}"
    submitted = r.json()
    assert submitted["status"] == "FULFILLING", submitted
    redemption_id = submitted["redemptionId"]

    # Held reservation must not have moved the (committed) balance yet.
    assert _balance(http) == before

    # Act as the voucher partner: POST the HMAC-signed resume webhook to the bridge. jobHandle must
    # equal the correlation ref the WireMock /provision stub returned (the saga parked on it).
    voucher_code = f"VCH-E2E-{RUN}"
    body = json.dumps(
        {"jobHandle": config.VOUCHER_EXTERNAL_REF, "status": "READY",
         "voucherCode": voucher_code, "partnerRef": f"PRT-{RUN}"}
    )
    ts = int(time.time())
    resp = requests.post(
        f"{config.BRIDGE}/webhooks/voucher",
        headers={"Content-Type": "application/json",
                 "X-Signature": _sign_webhook(body, ts), "X-Timestamp": str(ts)},
        data=body,
        timeout=10,
    )
    assert resp.status_code == 202, f"bridge rejected webhook: {resp.status_code} {resp.text}"

    # The bridge -> Kafka resume -> redemption ResumeConsumer -> saga commit is eventual.
    final = poll(
        lambda: http.get(f"{config.MOBILE_BFF}/redemptions/{redemption_id}").json(),
        predicate=lambda b: b.get("status") == "COMPLETED",
        timeout_s=60,
        what="voucher redemption commits after the resume webhook",
    )
    assert final["externalRef"] == voucher_code, final

    after = poll(lambda: _balance(http),
                 predicate=lambda b: b == before - reward["pointCost"],
                 what="balance drops by the voucher point cost")
    assert after == before - reward["pointCost"]
