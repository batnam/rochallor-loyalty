"""Shared constants + path/compose helpers for the loyalty E2E suite.

All of this is keyed off the live `docker compose` stack (base src/docker-compose.yml +
e2e/docker-compose.e2e.yml overlay). Service ports mirror the compose port mappings; the
host reaches Kafka on the EXTERNAL listener (localhost:29092).
"""
from __future__ import annotations

import os
from pathlib import Path

# --- repo layout -------------------------------------------------------------
E2E_DIR = Path(__file__).resolve().parent
REPO_ROOT = E2E_DIR.parent
SRC_DIR = REPO_ROOT / "src"
BASE_COMPOSE = SRC_DIR / "docker-compose.yml"
E2E_COMPOSE = E2E_DIR / "docker-compose.e2e.yml"
WIREMOCK_MAPPINGS = E2E_DIR / "wiremock" / "mappings"

# --- host endpoints (compose published ports) --------------------------------
HOST = os.environ.get("E2E_HOST", "localhost")
CORE = f"http://{HOST}:8081"
EARNING = f"http://{HOST}:8082"
REDEMPTION = f"http://{HOST}:8083"
CAMPAIGN = f"http://{HOST}:8084"
BRIDGE = f"http://{HOST}:8085"
MOBILE_BFF = f"http://{HOST}:8090"
ADMIN_BFF = f"http://{HOST}:8091"
WIREMOCK = f"http://{HOST}:9300"

# Any HTTP response from these proves the Spring context finished starting (web server
# binds at the end of context refresh, after Flyway + Kafka listener registration).
HEALTH_TARGETS = {
    "loyalty-core": CORE + "/members/lookup?programId=1&customerId=999999999",
    "loyalty-earning": EARNING + "/programs/1/earn-sources",
    "loyalty-redemption": REDEMPTION + "/rewards/999999999",
    "loyalty-campaign": CAMPAIGN + "/programs/1/campaigns",
    "loyalty-integration-bridge": BRIDGE + "/webhooks/voucher",
    "loyalty-mobile-bff": MOBILE_BFF + "/me/programs?customerId=1",
    "loyalty-admin-bff": ADMIN_BFF + "/reward-types?userId=health&roles=loyalty-readonly",
    "wiremock": WIREMOCK + "/__admin/mappings",
}

KAFKA_BOOTSTRAP = os.environ.get("E2E_KAFKA_BOOTSTRAP", f"{HOST}:29092")

# --- canonical topics --------------------------------------------------------
TOPIC_INGRESS_CARD_SPEND = "loyalty.ingress.card_spend.v1"
TOPIC_EARN_TRANSLATED = "loyalty.earn.translated.v1"     # bridge -> earning
TOPIC_LEDGER = "loyalty.ledger.v1"                       # core outbox

# --- domain fixtures ---------------------------------------------------------
# customerId MUST be 1 so that on a fresh DB the first opted-in member is assigned
# member_id = 1 (IDENTITY). Earning resolves customerId->memberId via core (real id),
# while mobile-bff->redemption forces memberId == customerId; they only agree at id 1.
PROGRAM_ID = 1
CUSTOMER_ID = 1
TCS_VERSION = 1
CARD_SPEND_AMOUNT = 60_000          # one synthetic large spend, enough to cross SILVER
EXPECTED_EARN_POINTS = 60_000       # RATE perAmount=1 points=1 -> floor(amount) == amount
SILVER_THRESHOLD = 50_000           # core seed tier ladder

ADMIN_USER = "e2e-campaign-manager"
ADMIN_ROLE = "loyalty-campaign-manager"
CASA_ACCOUNT = "000-E2E-CASA"

# Voucher async path: the WireMock /provision stub returns this externalRef, the saga parks
# on it, and the test fires the resume webhook with jobHandle == this value.
VOUCHER_EXTERNAL_REF = "VPREF-E2E-1"
VOUCHER_WEBHOOK_HMAC_SECRET = "dev-only-local-secret"   # matches compose env

ASSUME_RUNNING = os.environ.get("E2E_ASSUME_RUNNING") == "1"
KEEP_STACK = os.environ.get("E2E_KEEP_STACK") == "1"


def compose_cmd(*args: str) -> list[str]:
    """`docker compose` invocation for the layered stack.

    --project-directory is the base file's dir (src/) so the base build contexts
    (./loyalty-core, ...) resolve; absolute -f paths make it cwd-independent.
    """
    return [
        "docker", "compose",
        "--project-directory", str(SRC_DIR),
        "-f", str(BASE_COMPOSE),
        "-f", str(E2E_COMPOSE),
        *args,
    ]


def compose_env() -> dict[str, str]:
    env = dict(os.environ)
    env["E2E_WIREMOCK_MAPPINGS"] = str(WIREMOCK_MAPPINGS)
    return env
