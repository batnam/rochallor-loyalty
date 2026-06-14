"""Session fixtures: stack lifecycle (build/up/down), readiness polling, HTTP + Kafka clients.

The suite owns the stack by default: `pytest` runs `down -v` (clean slate) then `up -d --build`,
health-polls every service, yields, then `down -v` on teardown. Set E2E_ASSUME_RUNNING=1 to point
at an already-running stack (you are then responsible for a fresh DB — see config.CUSTOMER_ID),
and E2E_KEEP_STACK=1 to leave it up after the run.
"""
from __future__ import annotations

import subprocess
import time

import pytest
import requests

import config
from bus import KafkaBus


def _compose(*args: str, timeout: int) -> None:
    subprocess.run(
        config.compose_cmd(*args),
        env=config.compose_env(),
        check=True,
        timeout=timeout,
    )


def _wait_healthy(timeout_s: float = 240.0) -> None:
    deadline = time.time() + timeout_s
    pending = dict(config.HEALTH_TARGETS)
    last_err: dict[str, str] = {}
    while pending and time.time() < deadline:
        for name, url in list(pending.items()):
            try:
                # Any HTTP status means the web server is serving (context refresh done).
                requests.get(url, timeout=3)
                del pending[name]
            except requests.RequestException as e:
                last_err[name] = repr(e)
        if pending:
            time.sleep(3)
    if pending:
        raise RuntimeError(
            "services not ready within "
            f"{timeout_s:.0f}s: { {n: last_err.get(n, 'unknown') for n in pending} }"
        )


@pytest.fixture(scope="session", autouse=True)
def stack():
    if config.ASSUME_RUNNING:
        _wait_healthy()
        yield
        return

    # Clean slate so member_id IDENTITY starts at 1 (see config.CUSTOMER_ID rationale).
    _compose("down", "-v", "--remove-orphans", timeout=180)
    _compose("up", "-d", "--build", timeout=1800)
    try:
        _wait_healthy()
        yield
    finally:
        if not config.KEEP_STACK:
            _compose("down", "-v", "--remove-orphans", timeout=180)


@pytest.fixture(scope="session")
def http() -> requests.Session:
    s = requests.Session()
    s.headers.update({"Content-Type": "application/json"})
    return s


@pytest.fixture(scope="session")
def bus() -> KafkaBus:
    b = KafkaBus(config.KAFKA_BOOTSTRAP)
    yield b
    b.close()
