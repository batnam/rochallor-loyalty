"""Thin Kafka helper over confluent-kafka for the host side of the E2E.

The harness produces Ingress Events and consumes the canonical events the platform emits,
talking to the broker's EXTERNAL listener (localhost:29092).
"""
from __future__ import annotations

import json
import time
from typing import Callable

from confluent_kafka import Consumer, Producer


class KafkaBus:
    def __init__(self, bootstrap: str):
        self._bootstrap = bootstrap
        self._producer = Producer({"bootstrap.servers": bootstrap})

    def produce_json(self, topic: str, key: str, value: dict, headers: dict[str, str] | None = None) -> None:
        self._producer.produce(
            topic,
            key=key,
            value=json.dumps(value).encode("utf-8"),
            headers=[(k, v.encode("utf-8")) for k, v in (headers or {}).items()],
        )
        self._producer.flush(10)

    def await_message(
        self,
        topic: str,
        match: Callable[[dict], bool],
        timeout_s: float = 60.0,
        group: str | None = None,
    ) -> dict:
        """Consume `topic` from the beginning until a JSON message satisfies `match`.

        A fresh consumer group + earliest offset means messages produced before this call
        are still seen — fine because the stack is brought up clean per run.
        """
        group = group or f"e2e-{topic}-{int(time.time() * 1000)}"
        consumer = Consumer(
            {
                "bootstrap.servers": self._bootstrap,
                "group.id": group,
                "auto.offset.reset": "earliest",
                "enable.auto.commit": False,
            }
        )
        consumer.subscribe([topic])
        deadline = time.time() + timeout_s
        seen = 0
        try:
            while time.time() < deadline:
                msg = consumer.poll(1.0)
                if msg is None or msg.error():
                    continue
                seen += 1
                try:
                    payload = json.loads(msg.value().decode("utf-8"))
                except Exception:
                    continue
                if match(payload):
                    return payload
            raise AssertionError(
                f"no message matching predicate on '{topic}' within {timeout_s:.0f}s "
                f"(saw {seen} message(s))"
            )
        finally:
            consumer.close()

    def close(self) -> None:
        self._producer.flush(5)
