#!/usr/bin/env python3
"""Benchmark and correctness harness for the Cannsheet SANDBOX backend.

The script intentionally uses only Python's standard library.  It performs a
read-only GET safety check before any POST and refuses to mutate an endpoint
unless that GET explicitly reports ``environment == "SANDBOX"``.

Baseline and optimized runs generate identical IDs when given the same
``--namespace``.  The suite label is evidence metadata only and is deliberately
excluded from UUID generation.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import datetime as dt
import json
import math
import statistics
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence


HARNESS_VERSION = "1.0"
API_VERSION = 2
EXPECTED_ENVIRONMENT = "SANDBOX"
MIN_P95_SAMPLES = 20
DEFAULT_NAMESPACE = uuid.UUID("5752a4e2-e54a-5f9d-9230-3354a437f52c")
DEFAULT_TIMESTAMP_BASE = dt.datetime(2030, 1, 15, 12, 0, 0)
USER_AGENT = f"CannsheetSandboxBenchmark/{HARNESS_VERSION}"

SCENARIO_MINUTE_OFFSETS = {
    "one_consumption_warm": 0,
    "one_consumption_cold": 10_000,
    "duplicate_seed": 20_000,
    "concurrent_identical": 30_000,
    "partial_rejection": 40_000,
    "mixed_purchase_consumption": 50_000,
    "new_purchase": 60_000,
}


class SafetyError(RuntimeError):
    """Raised when an endpoint cannot be proven to be the sandbox."""


class ConfigurationError(ValueError):
    """Raised for invalid local command-line configuration."""


@dataclass(frozen=True)
class ProductReference:
    """Only the non-personal fields needed to submit a consumption."""

    product_id: str
    product_uuid: str | None = None
    status: int | None = None


@dataclass
class HttpObservation:
    """One measured HTTP exchange."""

    method: str
    http_status: int | None
    wall_time_ms: float
    response: Any
    effective_url: str | None
    error: str | None = None
    json_error: str | None = None


def parse_namespace(value: str | uuid.UUID) -> uuid.UUID:
    """Return a validated UUID namespace."""

    if isinstance(value, uuid.UUID):
        return value
    try:
        return uuid.UUID(str(value))
    except (ValueError, AttributeError) as exc:
        raise ConfigurationError("--namespace must be a valid UUID") from exc


def deterministic_uuid(
    namespace: str | uuid.UUID,
    scenario: str,
    sample_index: int,
    role: str,
) -> str:
    """Create a stable UUID without using the baseline/optimized suite label."""

    if sample_index < 0:
        raise ValueError("sample_index must be non-negative")
    if not scenario or not role:
        raise ValueError("scenario and role must be non-empty")
    seed = f"cannsheet-backend-benchmark/v1/{scenario}/{sample_index}/{role}"
    return str(uuid.uuid5(parse_namespace(namespace), seed))


def deterministic_temp_id(
    namespace: str | uuid.UUID,
    scenario: str,
    sample_index: int,
) -> str:
    """Return a stable fake temporary product ID."""

    stable_id = deterministic_uuid(namespace, scenario, sample_index, "temp-id")
    return "benchmark-temp-" + stable_id.split("-")[0]


def scenario_timestamp(
    timestamp_base: dt.datetime,
    scenario: str,
    sample_index: int,
) -> dt.datetime:
    """Return a deterministic timestamp for a scenario sample."""

    if sample_index < 0:
        raise ValueError("sample_index must be non-negative")
    offset = SCENARIO_MINUTE_OFFSETS.get(scenario, 70_000)
    return timestamp_base + dt.timedelta(minutes=offset + sample_index)


def _base_payload(
    namespace: str | uuid.UUID,
    scenario: str,
    sample_index: int,
) -> dict[str, Any]:
    return {
        "apiVersion": API_VERSION,
        "requestId": deterministic_uuid(namespace, scenario, sample_index, "request"),
        "environment": EXPECTED_ENVIRONMENT,
        "purchases": [],
        "consumptions": [],
    }


def build_empty_payload(
    namespace: str | uuid.UUID,
    sample_index: int,
) -> dict[str, Any]:
    """Build one deterministic empty v2 request."""

    return _base_payload(namespace, "empty_v2", sample_index)


def _product_fields(product: ProductReference) -> dict[str, str]:
    fields = {"productId": product.product_id}
    if product.product_uuid:
        fields["productUuid"] = product.product_uuid
    return fields


def _consumption_item(
    namespace: str | uuid.UUID,
    scenario: str,
    sample_index: int,
    product: ProductReference,
    timestamp_base: dt.datetime,
    *,
    role: str = "event",
) -> dict[str, Any]:
    timestamp = scenario_timestamp(timestamp_base, scenario, sample_index)
    item: dict[str, Any] = {
        "eventId": deterministic_uuid(namespace, scenario, sample_index, role),
        "date": timestamp.strftime("%Y-%m-%d"),
        "time": timestamp.strftime("%H:%M:%S"),
        "uses": 1.0,
        "isFinished": False,
        "weightCode": "benchmark",
    }
    item.update(_product_fields(product))
    return item


def build_consumption_payload(
    namespace: str | uuid.UUID,
    scenario: str,
    sample_index: int,
    product: ProductReference,
    timestamp_base: dt.datetime = DEFAULT_TIMESTAMP_BASE,
) -> dict[str, Any]:
    """Build one deterministic v2 consumption request."""

    payload = _base_payload(namespace, scenario, sample_index)
    payload["consumptions"] = [
        _consumption_item(
            namespace,
            scenario,
            sample_index,
            product,
            timestamp_base,
        )
    ]
    return payload


def _purchase_item(
    namespace: str | uuid.UUID,
    scenario: str,
    sample_index: int,
    timestamp_base: dt.datetime,
) -> dict[str, Any]:
    timestamp = scenario_timestamp(timestamp_base, scenario, sample_index)
    return {
        "actionId": deterministic_uuid(namespace, scenario, sample_index, "action"),
        "tempId": deterministic_temp_id(namespace, scenario, sample_index),
        "date": timestamp.strftime("%Y-%m-%d"),
        "type": "P",
        "name": f"Benchmark Product {sample_index:03d}",
        "cost": 10.0 + sample_index / 100.0,
        "thc": 20.0,
        "grams": 3.5,
        "borrowed": 0,
        "postTax": False,
    }


def build_purchase_payload(
    namespace: str | uuid.UUID,
    sample_index: int,
    timestamp_base: dt.datetime = DEFAULT_TIMESTAMP_BASE,
) -> dict[str, Any]:
    """Build one deterministic fake purchase request."""

    scenario = "new_purchase"
    payload = _base_payload(namespace, scenario, sample_index)
    payload["purchases"] = [
        _purchase_item(namespace, scenario, sample_index, timestamp_base)
    ]
    return payload


def build_mixed_payload(
    namespace: str | uuid.UUID,
    sample_index: int,
    timestamp_base: dt.datetime = DEFAULT_TIMESTAMP_BASE,
) -> dict[str, Any]:
    """Build a purchase and a consumption that references its temporary ID."""

    scenario = "mixed_purchase_consumption"
    payload = _base_payload(namespace, scenario, sample_index)
    purchase = _purchase_item(namespace, scenario, sample_index, timestamp_base)
    timestamp = scenario_timestamp(timestamp_base, scenario, sample_index)
    consumption = {
        "eventId": deterministic_uuid(namespace, scenario, sample_index, "event"),
        "date": timestamp.strftime("%Y-%m-%d"),
        "time": timestamp.strftime("%H:%M:%S"),
        "productId": purchase["tempId"],
        "uses": 1.0,
        "isFinished": False,
        "weightCode": "benchmark",
    }
    payload["purchases"] = [purchase]
    payload["consumptions"] = [consumption]
    return payload


def build_partial_rejection_payload(
    namespace: str | uuid.UUID,
    sample_index: int,
    product: ProductReference,
    timestamp_base: dt.datetime = DEFAULT_TIMESTAMP_BASE,
) -> dict[str, Any]:
    """Build one valid event plus one validly-shaped unknown-product event."""

    scenario = "partial_rejection"
    payload = _base_payload(namespace, scenario, sample_index)
    accepted = _consumption_item(
        namespace,
        scenario,
        sample_index,
        product,
        timestamp_base,
        role="accepted-event",
    )
    timestamp = scenario_timestamp(timestamp_base, scenario, sample_index)
    rejected = {
        "eventId": deterministic_uuid(
            namespace, scenario, sample_index, "rejected-event"
        ),
        "date": timestamp.strftime("%Y-%m-%d"),
        "time": timestamp.strftime("%H:%M:%S"),
        "productUuid": deterministic_uuid(
            namespace, scenario, sample_index, "unknown-product"
        ),
        "uses": 1.0,
        "isFinished": False,
        "weightCode": "benchmark",
    }
    payload["consumptions"] = [accepted, rejected]
    return payload


def item_ids(payload: Mapping[str, Any]) -> dict[str, list[str]]:
    """Extract the request's deterministic item IDs for evidence."""

    purchases = payload.get("purchases") or []
    consumptions = payload.get("consumptions") or []
    return {
        "actionIds": [str(item["actionId"]) for item in purchases if item.get("actionId")],
        "eventIds": [str(item["eventId"]) for item in consumptions if item.get("eventId")],
        "tempIds": [str(item["tempId"]) for item in purchases if item.get("tempId")],
    }


def nearest_rank_percentile(values: Sequence[float], percentile: float) -> float:
    """Calculate a percentile using the explicit nearest-rank method."""

    if not values:
        raise ValueError("values must not be empty")
    if not 0 < percentile <= 1:
        raise ValueError("percentile must be greater than 0 and at most 1")
    ordered = sorted(float(value) for value in values)
    rank = max(1, math.ceil(percentile * len(ordered)))
    return ordered[rank - 1]


def summarize_numbers(values: Iterable[float | int | None]) -> dict[str, Any]:
    """Summarize numeric samples; include p95 only for 20 or more samples."""

    numbers = [float(value) for value in values if value is not None]
    if not numbers:
        return {"count": 0}
    summary: dict[str, Any] = {
        "count": len(numbers),
        "min": round(min(numbers), 3),
        "median": round(statistics.median(numbers), 3),
        "max": round(max(numbers), 3),
    }
    if len(numbers) >= MIN_P95_SAMPLES:
        summary["p95"] = round(nearest_rank_percentile(numbers, 0.95), 3)
    return summary


def select_reference_product(products: Sequence[Mapping[str, Any]]) -> ProductReference:
    """Select an active/unopened product deterministically from a GET response."""

    candidates: list[ProductReference] = []
    for product in products:
        product_id = str(product.get("id") or "").strip()
        if not product_id:
            continue
        raw_status = product.get("status")
        try:
            status = int(raw_status) if raw_status is not None else None
        except (TypeError, ValueError):
            status = None
        product_uuid = str(product.get("productUuid") or "").strip() or None
        candidates.append(ProductReference(product_id, product_uuid, status))
    if not candidates:
        raise SafetyError("SANDBOX GET returned no usable products")

    def sort_key(product: ProductReference) -> tuple[int, str, str]:
        status_priority = {0: 0, 2: 1}.get(product.status, 2)
        return (status_priority, product.product_uuid or "", product.product_id)

    return sorted(candidates, key=sort_key)[0]


def parse_timestamp_base(value: str) -> dt.datetime:
    """Parse a deterministic local timestamp without accepting a timezone."""

    try:
        parsed = dt.datetime.fromisoformat(value)
    except ValueError as exc:
        raise ConfigurationError(
            "--timestamp-base must use ISO format, for example 2030-01-15T12:00:00"
        ) from exc
    if parsed.tzinfo is not None:
        raise ConfigurationError("--timestamp-base must not include a timezone")
    return parsed.replace(microsecond=0)


def safe_endpoint_label(endpoint: str) -> str:
    """Return an evidence-safe endpoint label without query-string secrets."""

    parsed = urllib.parse.urlsplit(endpoint)
    return urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, parsed.path, "", ""))


def validate_endpoint_url(endpoint: str) -> None:
    """Reject malformed URLs and insecure non-loopback HTTP endpoints."""

    parsed = urllib.parse.urlsplit(endpoint)
    if parsed.username or parsed.password:
        raise ConfigurationError("endpoint URLs containing credentials are not allowed")
    if parsed.fragment:
        raise ConfigurationError("endpoint URLs must not contain a fragment")
    if not parsed.hostname:
        raise ConfigurationError("--endpoint must be an absolute URL")
    is_loopback = parsed.hostname in {"127.0.0.1", "::1", "localhost"}
    if parsed.scheme != "https" and not (parsed.scheme == "http" and is_loopback):
        raise ConfigurationError(
            "--endpoint must use HTTPS (plain HTTP is allowed only for loopback tests)"
        )


def _decode_response(raw: bytes, content_type: str | None) -> tuple[Any, str | None]:
    charset = "utf-8"
    if content_type and "charset=" in content_type.lower():
        charset = content_type.lower().split("charset=", 1)[1].split(";", 1)[0].strip()
    try:
        text = raw.decode(charset, errors="replace")
    except LookupError:
        text = raw.decode("utf-8", errors="replace")
    try:
        return json.loads(text), None
    except json.JSONDecodeError as exc:
        return text, f"Malformed JSON response: {exc.msg} at character {exc.pos}"


def http_json(
    endpoint: str,
    method: str,
    payload: Mapping[str, Any] | None,
    *,
    timeout_seconds: float,
    max_response_bytes: int,
) -> HttpObservation:
    """Perform one measured JSON exchange without third-party packages."""

    body = None
    headers = {"Accept": "application/json", "User-Agent": USER_AGENT}
    if payload is not None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    request = urllib.request.Request(endpoint, data=body, headers=headers, method=method)
    started = time.perf_counter_ns()
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            raw = response.read(max_response_bytes + 1)
            elapsed = (time.perf_counter_ns() - started) / 1_000_000
            if len(raw) > max_response_bytes:
                return HttpObservation(
                    method,
                    response.status,
                    elapsed,
                    None,
                    safe_endpoint_label(response.geturl()),
                    error=f"Response exceeded {max_response_bytes} bytes",
                )
            decoded, json_error = _decode_response(raw, response.headers.get("Content-Type"))
            return HttpObservation(
                method,
                response.status,
                elapsed,
                decoded,
                safe_endpoint_label(response.geturl()),
                json_error=json_error,
            )
    except urllib.error.HTTPError as exc:
        raw = exc.read(max_response_bytes + 1)
        elapsed = (time.perf_counter_ns() - started) / 1_000_000
        decoded, json_error = _decode_response(raw[:max_response_bytes], exc.headers.get("Content-Type"))
        error = f"HTTP {exc.code}: {exc.reason}"
        if len(raw) > max_response_bytes:
            error += f"; response exceeded {max_response_bytes} bytes"
        return HttpObservation(
            method,
            exc.code,
            elapsed,
            decoded,
            safe_endpoint_label(exc.geturl()),
            error=error,
            json_error=json_error,
        )
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        elapsed = (time.perf_counter_ns() - started) / 1_000_000
        return HttpObservation(
            method,
            None,
            elapsed,
            None,
            None,
            error=f"{type(exc).__name__}: {exc}",
        )


def extract_server_duration_ms(response: Any) -> float | None:
    """Read an additive server-duration field when a backend exposes one."""

    if not isinstance(response, Mapping):
        return None
    direct_names = (
        "serverDurationMs",
        "totalHandlerDurationMs",
        "handlerDurationMs",
    )
    for name in direct_names:
        value = response.get(name)
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            return float(value)
    for container_name in ("timing", "timings", "performance"):
        nested = response.get(container_name)
        if not isinstance(nested, Mapping):
            continue
        for name in ("serverDurationMs", "totalHandlerMs", "totalMs"):
            value = nested.get(name)
            if isinstance(value, (int, float)) and not isinstance(value, bool):
                return float(value)
    return None


def validate_sandbox_get(observation: HttpObservation) -> list[dict[str, Any]]:
    """Return explicit safety/correctness checks for a GET observation."""

    response = observation.response
    checks = [
        check("http_status_200", observation.http_status == 200, observation.http_status, 200),
        check("json_object", isinstance(response, Mapping), type(response).__name__, "object"),
    ]
    if isinstance(response, Mapping):
        checks.extend(
            [
                check(
                    "environment_is_sandbox",
                    response.get("environment") == EXPECTED_ENVIRONMENT,
                    response.get("environment"),
                    EXPECTED_ENVIRONMENT,
                ),
                check(
                    "products_is_array",
                    isinstance(response.get("products"), list),
                    type(response.get("products")).__name__,
                    "list",
                ),
            ]
        )
    return checks


def require_sandbox_preflight(observation: HttpObservation) -> list[Mapping[str, Any]]:
    """Refuse every endpoint that does not explicitly identify as SANDBOX."""

    checks = validate_sandbox_get(observation)
    if not all(item["passed"] for item in checks):
        detail = "; ".join(
            f"{item['name']}={item['actual']!r}" for item in checks if not item["passed"]
        )
        raise SafetyError(
            "No POST was sent because the read-only endpoint check did not prove "
            f"environment SANDBOX ({detail})"
        )
    assert isinstance(observation.response, Mapping)
    products = observation.response.get("products")
    assert isinstance(products, list)
    return products


def check(name: str, passed: bool, actual: Any, expected: Any) -> dict[str, Any]:
    return {
        "name": name,
        "passed": bool(passed),
        "actual": actual,
        "expected": expected,
    }


def _ack_status(response: Mapping[str, Any], collection: str, id_name: str, item_id: str) -> str | None:
    values = response.get(collection)
    if not isinstance(values, list):
        return None
    for item in values:
        if isinstance(item, Mapping) and item.get(id_name) == item_id:
            status = item.get("status")
            return str(status) if status is not None else None
    return None


def _rejection_code(response: Mapping[str, Any], collection: str, id_name: str, item_id: str) -> str | None:
    values = response.get(collection)
    if not isinstance(values, list):
        return None
    for item in values:
        if isinstance(item, Mapping) and item.get(id_name) == item_id:
            code = item.get("errorCode")
            return str(code) if code is not None else None
    return None


def evaluate_post_response(
    observation: HttpObservation,
    payload: Mapping[str, Any],
    *,
    expected_purchase_status: str | None = None,
    expected_event_statuses: Mapping[str, str | set[str]] | None = None,
    expected_rejections: Mapping[str, str] | None = None,
    expected_all_accepted: bool | None = True,
) -> list[dict[str, Any]]:
    """Evaluate the stable v2 response contract for one POST."""

    response = observation.response
    checks = [
        check("post_http_status_200", observation.http_status == 200, observation.http_status, 200),
        check("post_json_object", isinstance(response, Mapping), type(response).__name__, "object"),
    ]
    if not isinstance(response, Mapping):
        return checks
    checks.extend(
        [
            check("success", response.get("success") is True, response.get("success"), True),
            check(
                "environment_is_sandbox",
                response.get("environment") == EXPECTED_ENVIRONMENT,
                response.get("environment"),
                EXPECTED_ENVIRONMENT,
            ),
            check(
                "request_id_echo",
                response.get("requestId") == payload.get("requestId"),
                response.get("requestId"),
                payload.get("requestId"),
            ),
        ]
    )
    if expected_all_accepted is not None:
        checks.append(
            check(
                "all_accepted",
                response.get("allAccepted") is expected_all_accepted,
                response.get("allAccepted"),
                expected_all_accepted,
            )
        )
    purchases = payload.get("purchases") or []
    if expected_purchase_status is not None and purchases:
        action_id = purchases[0]["actionId"]
        actual = _ack_status(response, "acknowledgedPurchases", "actionId", action_id)
        checks.append(
            check(
                "purchase_ack_status",
                actual == expected_purchase_status,
                actual,
                expected_purchase_status,
            )
        )
        temp_id = purchases[0].get("tempId")
        id_map = response.get("productIdMap")
        mapped = id_map.get(temp_id) if isinstance(id_map, Mapping) else None
        checks.append(check("temporary_id_resolved", bool(mapped), mapped, "non-empty legacy ID"))
    for event_id, expected in (expected_event_statuses or {}).items():
        actual = _ack_status(
            response, "acknowledgedConsumptions", "eventId", event_id
        )
        allowed = expected if isinstance(expected, set) else {expected}
        checks.append(
            check(
                "event_ack_status",
                actual in allowed,
                actual,
                sorted(allowed),
            )
        )
    for event_id, expected_code in (expected_rejections or {}).items():
        actual = _rejection_code(
            response, "rejectedConsumptions", "eventId", event_id
        )
        checks.append(
            check(
                "event_rejection_code",
                actual == expected_code,
                actual,
                expected_code,
            )
        )
    return checks


def summarize_get_response(response: Any) -> Any:
    """Store contract evidence without copying product names or commercial data."""

    if not isinstance(response, Mapping):
        return response
    products = response.get("products")
    summary: dict[str, Any] = {
        key: response.get(key)
        for key in ("apiVersion", "environment", "error", "errorCode")
        if key in response
    }
    if isinstance(products, list):
        summary["productCount"] = len(products)
    else:
        summary["productsType"] = type(products).__name__
    return summary


def observation_evidence(observation: HttpObservation, *, summarize_get: bool = False) -> dict[str, Any]:
    response = summarize_get_response(observation.response) if summarize_get else observation.response
    return {
        "method": observation.method,
        "httpStatus": observation.http_status,
        "wallTimeMs": round(observation.wall_time_ms, 3),
        "effectiveUrl": observation.effective_url,
        "error": observation.error,
        "jsonError": observation.json_error,
        "response": response,
    }


class BenchmarkRunner:
    """Execute a deterministic benchmark suite against a preflighted sandbox."""

    def __init__(
        self,
        *,
        endpoint: str,
        namespace: uuid.UUID,
        timestamp_base: dt.datetime,
        product: ProductReference,
        timeout_seconds: float,
        max_response_bytes: int,
        cold_interval_seconds: float,
    ) -> None:
        self.endpoint = endpoint
        self.namespace = namespace
        self.timestamp_base = timestamp_base
        self.product = product
        self.timeout_seconds = timeout_seconds
        self.max_response_bytes = max_response_bytes
        self.cold_interval_seconds = cold_interval_seconds
        self.records: list[dict[str, Any]] = []
        self.concurrency_pairs: list[dict[str, Any]] = []
        self._sequence = 0

    def _request(self, method: str, payload: Mapping[str, Any] | None = None) -> HttpObservation:
        return http_json(
            self.endpoint,
            method,
            payload,
            timeout_seconds=self.timeout_seconds,
            max_response_bytes=self.max_response_bytes,
        )

    def _idle(self, temperature: str) -> float:
        if temperature == "cold" and self.cold_interval_seconds > 0:
            time.sleep(self.cold_interval_seconds)
            return self.cold_interval_seconds
        return 0.0

    def _next_sequence(self) -> int:
        self._sequence += 1
        return self._sequence

    def run_get(self, scenario: str, sample_index: int, temperature: str) -> None:
        idle = self._idle(temperature)
        print(f"[{scenario} {sample_index + 1}] GET ({temperature})", flush=True)
        observation = self._request("GET")
        checks = validate_sandbox_get(observation)
        record = {
            "sequence": self._next_sequence(),
            "scenario": scenario,
            "sampleIndex": sample_index,
            "operation": "GET",
            "temperature": temperature,
            "idleBeforeSeconds": idle,
            "serverDurationMs": extract_server_duration_ms(observation.response),
            "getWallTimeMs": round(observation.wall_time_ms, 3),
            "httpStatus": observation.http_status,
            "response": summarize_get_response(observation.response),
            "transportError": observation.error,
            "jsonError": observation.json_error,
            "checks": checks,
            "correctnessPassed": all(item["passed"] for item in checks),
        }
        self.records.append(record)

    def _post_and_follow_get(self, payload: Mapping[str, Any]) -> tuple[HttpObservation, HttpObservation]:
        post = self._request("POST", payload)
        follow_get = self._request("GET")
        return post, follow_get

    def _make_post_record(
        self,
        *,
        scenario: str,
        sample_index: int,
        temperature: str,
        idle_before_seconds: float,
        payload: Mapping[str, Any],
        post: HttpObservation,
        follow_get: HttpObservation,
        checks: list[dict[str, Any]],
        role: str | None = None,
        pair_index: int | None = None,
        member_index: int | None = None,
    ) -> dict[str, Any]:
        follow_checks = validate_sandbox_get(follow_get)
        checks = checks + [
            check(
                "follow_up_" + item["name"],
                item["passed"],
                item["actual"],
                item["expected"],
            )
            for item in follow_checks
        ]
        combined = post.wall_time_ms + follow_get.wall_time_ms
        record: dict[str, Any] = {
            "sequence": self._next_sequence(),
            "scenario": scenario,
            "sampleIndex": sample_index,
            "operation": "POST+GET",
            "temperature": temperature,
            "idleBeforeSeconds": idle_before_seconds,
            "role": role,
            "concurrentPairIndex": pair_index,
            "concurrentMemberIndex": member_index,
            "requestId": payload.get("requestId"),
            "itemIds": item_ids(payload),
            "payload": payload,
            "serverDurationMs": extract_server_duration_ms(post.response),
            "postWallTimeMs": round(post.wall_time_ms, 3),
            "followUpGetTimeMs": round(follow_get.wall_time_ms, 3),
            "combinedTimeMs": round(combined, 3),
            "httpStatus": post.http_status,
            "response": post.response,
            "transportError": post.error,
            "jsonError": post.json_error,
            "followUpGet": observation_evidence(follow_get, summarize_get=True),
            "checks": checks,
            "correctnessPassed": all(item["passed"] for item in checks),
        }
        return record

    def run_post(
        self,
        *,
        scenario: str,
        sample_index: int,
        temperature: str,
        payload: Mapping[str, Any],
        expected_purchase_status: str | None = None,
        expected_event_statuses: Mapping[str, str | set[str]] | None = None,
        expected_rejections: Mapping[str, str] | None = None,
        expected_all_accepted: bool | None = True,
        role: str | None = None,
    ) -> dict[str, Any]:
        idle = self._idle(temperature)
        print(f"[{scenario} {sample_index + 1}] POST + GET ({temperature})", flush=True)
        post, follow_get = self._post_and_follow_get(payload)
        checks = evaluate_post_response(
            post,
            payload,
            expected_purchase_status=expected_purchase_status,
            expected_event_statuses=expected_event_statuses,
            expected_rejections=expected_rejections,
            expected_all_accepted=expected_all_accepted,
        )
        record = self._make_post_record(
            scenario=scenario,
            sample_index=sample_index,
            temperature=temperature,
            idle_before_seconds=idle,
            payload=payload,
            post=post,
            follow_get=follow_get,
            checks=checks,
            role=role,
        )
        self.records.append(record)
        return record

    def run_concurrent_pair(self, pair_index: int) -> None:
        scenario = "concurrent_identical"
        payload = build_consumption_payload(
            self.namespace,
            scenario,
            pair_index,
            self.product,
            self.timestamp_base,
        )
        event_id = payload["consumptions"][0]["eventId"]
        barrier = threading.Barrier(2)

        def worker() -> tuple[HttpObservation, HttpObservation]:
            barrier.wait(timeout=max(5.0, self.timeout_seconds))
            return self._post_and_follow_get(payload)

        print(f"[{scenario} pair {pair_index + 1}] two simultaneous POSTs", flush=True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            futures = [executor.submit(worker) for _ in range(2)]
            exchanges = [future.result() for future in futures]

        pair_records: list[dict[str, Any]] = []
        statuses: list[str | None] = []
        for member_index, (post, follow_get) in enumerate(exchanges):
            checks = evaluate_post_response(
                post,
                payload,
                expected_event_statuses={event_id: {"committed", "duplicate"}},
                expected_all_accepted=True,
            )
            status = (
                _ack_status(
                    post.response,
                    "acknowledgedConsumptions",
                    "eventId",
                    event_id,
                )
                if isinstance(post.response, Mapping)
                else None
            )
            statuses.append(status)
            record = self._make_post_record(
                scenario=scenario,
                sample_index=pair_index,
                temperature="warm",
                idle_before_seconds=0.0,
                payload=payload,
                post=post,
                follow_get=follow_get,
                checks=checks,
                role="concurrent_member",
                pair_index=pair_index,
                member_index=member_index,
            )
            pair_records.append(record)

        exactly_once = sorted(status for status in statuses if status) == [
            "committed",
            "duplicate",
        ]
        pair_check = check(
            "concurrent_pair_exactly_one_commit",
            exactly_once,
            statuses,
            ["committed", "duplicate"],
        )
        for record in pair_records:
            record["checks"].append(pair_check.copy())
            record["correctnessPassed"] = all(
                item["passed"] for item in record["checks"]
            )
            self.records.append(record)
        self.concurrency_pairs.append(
            {
                "pairIndex": pair_index,
                "requestId": payload["requestId"],
                "eventId": event_id,
                "statuses": statuses,
                "passed": exactly_once,
            }
        )


def summarize_records(records: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    """Group timing evidence by scenario, temperature, and operation."""

    groups: dict[tuple[str, str, str], list[Mapping[str, Any]]] = {}
    for record in records:
        key = (
            str(record.get("scenario")),
            str(record.get("temperature")),
            str(record.get("operation")),
        )
        groups.setdefault(key, []).append(record)

    summaries: list[dict[str, Any]] = []
    for key in sorted(groups):
        scenario, temperature, operation = key
        values = groups[key]
        metrics: dict[str, dict[str, Any]] = {}
        for metric in (
            "serverDurationMs",
            "getWallTimeMs",
            "postWallTimeMs",
            "followUpGetTimeMs",
            "combinedTimeMs",
        ):
            summary = summarize_numbers(record.get(metric) for record in values)
            if summary["count"]:
                metrics[metric] = summary
        summaries.append(
            {
                "scenario": scenario,
                "temperature": temperature,
                "operation": operation,
                "sampleCount": len(values),
                "correctnessPassedCount": sum(
                    1 for record in values if record.get("correctnessPassed") is True
                ),
                "correctnessFailedCount": sum(
                    1 for record in values if record.get("correctnessPassed") is not True
                ),
                "metrics": metrics,
            }
        )
    return summaries


def _stats_cell(summary: Mapping[str, Any] | None) -> str:
    if not summary or not summary.get("count"):
        return "-"
    p95 = summary.get("p95", "-")
    return f"{summary['min']} / {summary['median']} / {summary['max']} / {p95}"


def _short_ids(ids: Mapping[str, Sequence[str]]) -> str:
    all_ids = list(ids.get("actionIds", [])) + list(ids.get("eventIds", []))
    if not all_ids:
        return "-"
    return ", ".join(value[:8] for value in all_ids)


def render_markdown(evidence: Mapping[str, Any]) -> str:
    """Render human-readable evidence alongside the complete JSON artifact."""

    config = evidence["configuration"]
    correctness = evidence["correctness"]
    lines = [
        "# Cannsheet backend sync benchmark evidence",
        "",
        f"- Suite: `{evidence['suite']}`",
        f"- Generated (UTC): `{evidence['generatedAtUtc']}`",
        f"- Endpoint (query removed): `{evidence['endpoint']}`",
        f"- Confirmed environment: `{evidence['preflight']['environment']}`",
        f"- Deterministic namespace: `{config['namespace']}`",
        f"- Timestamp base: `{config['timestampBase']}`",
        f"- Cold idle scheduled before each cold-labelled request: `{config['coldIntervalSeconds']}` seconds",
        f"- Overall correctness: **{'PASS' if correctness['passed'] else 'FAIL'}** "
        f"({correctness['passedRecords']}/{correctness['totalRecords']} records passed)",
        "",
        "The suite label is not part of UUID generation. Reset and reseed the same",
        "sandbox fixture before the baseline and optimized runs so both suites see",
        "the same starting data and submit byte-equivalent deterministic payloads.",
        "",
    ]
    if config["coldIntervalSeconds"] == 0 and any(
        record.get("temperature") == "cold" for record in evidence["records"]
    ):
        lines.extend(
            [
                "> Cold-labelled rows had no enforced idle interval. Keep them separate",
                "> from warm rows, but do not claim they prove a Google cold start.",
                "",
            ]
        )

    lines.extend(
        [
            "## Timing summary",
            "",
            "All timing cells are `minimum / median / maximum / p95` in milliseconds.",
            "A p95 is shown only when that metric has at least 20 samples.",
            "",
            "| Scenario | Label | Operation | Samples | Passed | Server | GET | POST | Follow-up GET | Combined |",
            "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|",
        ]
    )
    for summary in evidence["summaries"]:
        metrics = summary["metrics"]
        lines.append(
            "| {scenario} | {temperature} | {operation} | {samples} | {passed} | {server} | {get} | {post} | {follow} | {combined} |".format(
                scenario=summary["scenario"],
                temperature=summary["temperature"],
                operation=summary["operation"],
                samples=summary["sampleCount"],
                passed=summary["correctnessPassedCount"],
                server=_stats_cell(metrics.get("serverDurationMs")),
                get=_stats_cell(metrics.get("getWallTimeMs")),
                post=_stats_cell(metrics.get("postWallTimeMs")),
                follow=_stats_cell(metrics.get("followUpGetTimeMs")),
                combined=_stats_cell(metrics.get("combinedTimeMs")),
            )
        )

    lines.extend(
        [
            "",
            "## Individual results",
            "",
            "Full payloads, responses, checks, and follow-up GET evidence are in the JSON file.",
            "",
            "| # | Scenario | Label | HTTP | Request | Item IDs | Server ms | Wall ms | Follow-up GET ms | Combined ms | Check |",
            "|---:|---|---|---:|---|---|---:|---:|---:|---:|---|",
        ]
    )
    for record in evidence["records"]:
        request_id = str(record.get("requestId") or "-")
        wall = record.get("postWallTimeMs", record.get("getWallTimeMs", "-"))
        lines.append(
            "| {sequence} | {scenario} | {temperature} | {status} | {request_id} | {item_ids} | {server} | {wall} | {follow} | {combined} | {passed} |".format(
                sequence=record["sequence"],
                scenario=record["scenario"],
                temperature=record["temperature"],
                status=record.get("httpStatus", "-"),
                request_id=request_id[:8],
                item_ids=_short_ids(record.get("itemIds", {})),
                server=record.get("serverDurationMs") if record.get("serverDurationMs") is not None else "-",
                wall=wall,
                follow=record.get("followUpGetTimeMs", "-"),
                combined=record.get("combinedTimeMs", "-"),
                passed="PASS" if record.get("correctnessPassed") else "FAIL",
            )
        )

    if evidence["concurrencyPairs"]:
        lines.extend(
            [
                "",
                "## Concurrent identical pairs",
                "",
                "| Pair | Request | Event | Returned statuses | Exactly one commit |",
                "|---:|---|---|---|---|",
            ]
        )
        for pair in evidence["concurrencyPairs"]:
            statuses = ", ".join(str(value) for value in pair["statuses"])
            lines.append(
                f"| {pair['pairIndex'] + 1} | {pair['requestId'][:8]} | "
                f"{pair['eventId'][:8]} | {statuses} | "
                f"{'PASS' if pair['passed'] else 'FAIL'} |"
            )

    lines.extend(
        [
            "",
            "## Safety and interpretation",
            "",
            "- The harness sent no POST until a read-only GET explicitly returned `environment: SANDBOX` and a products array.",
            "- Every POST also declares `environment: SANDBOX`.",
            "- Product names, prices, potency, and weights returned by GET are not copied into evidence; only the count and chosen identifiers are retained.",
            "- `serverDurationMs` is reported only when the HTTP response exposes it. Client wall-clock time is always measured.",
            "- The combined time is POST wall time plus the immediately following GET wall time.",
            "- These requests mutate the sandbox. Restore the normal fixture after collecting evidence.",
            "",
        ]
    )
    return "\n".join(lines)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Run deterministic correctness and timing scenarios against an explicitly "
            "identified Cannsheet SANDBOX endpoint."
        )
    )
    parser.add_argument("--endpoint", required=True, help="Sandbox web-app /exec URL")
    parser.add_argument(
        "--suite", required=True, choices=("baseline", "optimized"), help="Evidence label only"
    )
    parser.add_argument(
        "--output",
        required=True,
        help="Output path stem; .json and .md are written beside it",
    )
    parser.add_argument(
        "--namespace",
        default=str(DEFAULT_NAMESPACE),
        help="UUID namespace shared by baseline and optimized runs",
    )
    parser.add_argument(
        "--timestamp-base",
        default=DEFAULT_TIMESTAMP_BASE.isoformat(),
        help="Deterministic local ISO timestamp shared by both runs",
    )
    parser.add_argument("--timeout-seconds", type=float, default=120.0)
    parser.add_argument("--max-response-bytes", type=int, default=5_000_000)
    parser.add_argument(
        "--cold-interval-seconds",
        type=float,
        default=0.0,
        help="Optional enforced idle before each cold-labelled request",
    )
    parser.add_argument("--cold-get-count", type=int, default=5)
    parser.add_argument("--warm-get-count", type=int, default=20)
    parser.add_argument("--empty-count", type=int, default=5)
    parser.add_argument("--warm-consumption-count", type=int, default=20)
    parser.add_argument("--cold-consumption-count", type=int, default=5)
    parser.add_argument("--duplicate-retry-count", type=int, default=10)
    parser.add_argument("--new-purchase-count", type=int, default=5)
    parser.add_argument("--mixed-count", type=int, default=5)
    parser.add_argument("--partial-rejection-count", type=int, default=5)
    parser.add_argument("--concurrent-pair-count", type=int, default=3)
    parser.add_argument(
        "--confirm-sandbox-mutations",
        action="store_true",
        help="Required acknowledgement that POST scenarios mutate the sandbox",
    )
    parser.add_argument(
        "--overwrite", action="store_true", help="Replace existing output files"
    )
    return parser


def validate_args(args: argparse.Namespace) -> None:
    validate_endpoint_url(args.endpoint)
    if args.timeout_seconds <= 0:
        raise ConfigurationError("--timeout-seconds must be greater than zero")
    if args.max_response_bytes < 1:
        raise ConfigurationError("--max-response-bytes must be positive")
    if args.cold_interval_seconds < 0:
        raise ConfigurationError("--cold-interval-seconds must not be negative")
    count_names = (
        "cold_get_count",
        "warm_get_count",
        "empty_count",
        "warm_consumption_count",
        "cold_consumption_count",
        "duplicate_retry_count",
        "new_purchase_count",
        "mixed_count",
        "partial_rejection_count",
        "concurrent_pair_count",
    )
    for name in count_names:
        if getattr(args, name) < 0:
            raise ConfigurationError(f"--{name.replace('_', '-')} must not be negative")


def output_paths(value: str) -> tuple[Path, Path]:
    base = Path(value)
    if base.suffix.lower() in {".json", ".md"}:
        base = base.with_suffix("")
    return base.with_suffix(".json"), base.with_suffix(".md")


def write_evidence(
    evidence: Mapping[str, Any],
    json_path: Path,
    markdown_path: Path,
    *,
    overwrite: bool,
) -> None:
    for path in (json_path, markdown_path):
        if path.exists() and not overwrite:
            raise ConfigurationError(
                f"Refusing to overwrite {path}; choose a new --output or pass --overwrite"
            )
    json_path.parent.mkdir(parents=True, exist_ok=True)
    markdown_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(
        json.dumps(evidence, indent=2, sort_keys=False) + "\n", encoding="utf-8"
    )
    markdown_path.write_text(render_markdown(evidence), encoding="utf-8")


def execute(args: argparse.Namespace) -> dict[str, Any]:
    namespace = parse_namespace(args.namespace)
    timestamp_base = parse_timestamp_base(args.timestamp_base)

    print("Running read-only SANDBOX safety check...", flush=True)
    preflight_observation = http_json(
        args.endpoint,
        "GET",
        None,
        timeout_seconds=args.timeout_seconds,
        max_response_bytes=args.max_response_bytes,
    )
    products = require_sandbox_preflight(preflight_observation)
    product = select_reference_product(products)
    if not args.confirm_sandbox_mutations:
        raise SafetyError(
            "SANDBOX was confirmed, but no POST was sent. Re-run with "
            "--confirm-sandbox-mutations after ensuring the performance fixture can be reset."
        )

    runner = BenchmarkRunner(
        endpoint=args.endpoint,
        namespace=namespace,
        timestamp_base=timestamp_base,
        product=product,
        timeout_seconds=args.timeout_seconds,
        max_response_bytes=args.max_response_bytes,
        cold_interval_seconds=args.cold_interval_seconds,
    )

    for index in range(args.cold_get_count):
        runner.run_get("get", index, "cold")
    for index in range(args.warm_get_count):
        runner.run_get("get", index, "warm")

    for index in range(args.empty_count):
        runner.run_post(
            scenario="empty_v2",
            sample_index=index,
            temperature="warm",
            payload=build_empty_payload(namespace, index),
            expected_event_statuses={},
        )

    for index in range(args.warm_consumption_count):
        scenario = "one_consumption_warm"
        payload = build_consumption_payload(
            namespace, scenario, index, product, timestamp_base
        )
        runner.run_post(
            scenario="one_consumption",
            sample_index=index,
            temperature="warm",
            payload=payload,
            expected_event_statuses={
                payload["consumptions"][0]["eventId"]: "committed"
            },
        )

    for index in range(args.cold_consumption_count):
        scenario = "one_consumption_cold"
        payload = build_consumption_payload(
            namespace, scenario, index, product, timestamp_base
        )
        runner.run_post(
            scenario="one_consumption",
            sample_index=index,
            temperature="cold",
            payload=payload,
            expected_event_statuses={
                payload["consumptions"][0]["eventId"]: "committed"
            },
        )

    if args.duplicate_retry_count:
        duplicate_payload = build_consumption_payload(
            namespace,
            "duplicate_seed",
            0,
            product,
            timestamp_base,
        )
        duplicate_event_id = duplicate_payload["consumptions"][0]["eventId"]
        runner.run_post(
            scenario="duplicate_seed",
            sample_index=0,
            temperature="warm",
            payload=duplicate_payload,
            expected_event_statuses={duplicate_event_id: "committed"},
            role="setup",
        )
        for index in range(args.duplicate_retry_count):
            runner.run_post(
                scenario="duplicate_retry",
                sample_index=index,
                temperature="warm",
                payload=duplicate_payload,
                expected_event_statuses={duplicate_event_id: "duplicate"},
            )

    for index in range(args.new_purchase_count):
        payload = build_purchase_payload(namespace, index, timestamp_base)
        runner.run_post(
            scenario="new_purchase",
            sample_index=index,
            temperature="warm",
            payload=payload,
            expected_purchase_status="committed",
        )

    for index in range(args.mixed_count):
        payload = build_mixed_payload(namespace, index, timestamp_base)
        event_id = payload["consumptions"][0]["eventId"]
        runner.run_post(
            scenario="mixed_purchase_consumption",
            sample_index=index,
            temperature="warm",
            payload=payload,
            expected_purchase_status="committed",
            expected_event_statuses={event_id: "committed"},
        )

    for index in range(args.partial_rejection_count):
        payload = build_partial_rejection_payload(
            namespace, index, product, timestamp_base
        )
        accepted_id = payload["consumptions"][0]["eventId"]
        rejected_id = payload["consumptions"][1]["eventId"]
        runner.run_post(
            scenario="partial_rejection",
            sample_index=index,
            temperature="warm",
            payload=payload,
            expected_event_statuses={accepted_id: "committed"},
            expected_rejections={rejected_id: "UNKNOWN_PRODUCT"},
            expected_all_accepted=False,
        )

    for pair_index in range(args.concurrent_pair_count):
        runner.run_concurrent_pair(pair_index)

    summaries = summarize_records(runner.records)
    passed_count = sum(
        1 for record in runner.records if record.get("correctnessPassed") is True
    )
    generated_at = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat()
    preflight_response = preflight_observation.response
    assert isinstance(preflight_response, Mapping)
    evidence: dict[str, Any] = {
        "schemaVersion": 1,
        "harnessVersion": HARNESS_VERSION,
        "suite": args.suite,
        "generatedAtUtc": generated_at,
        "endpoint": safe_endpoint_label(args.endpoint),
        "preflight": {
            "environment": preflight_response.get("environment"),
            "apiVersion": preflight_response.get("apiVersion"),
            "productCount": len(products),
            "httpStatus": preflight_observation.http_status,
            "wallTimeMs": round(preflight_observation.wall_time_ms, 3),
            "selectedProduct": {
                "productId": product.product_id,
                "productUuid": product.product_uuid,
                "status": product.status,
            },
            "checks": validate_sandbox_get(preflight_observation),
        },
        "configuration": {
            "namespace": str(namespace),
            "suiteExcludedFromUuidGeneration": True,
            "timestampBase": timestamp_base.isoformat(),
            "timeoutSeconds": args.timeout_seconds,
            "maxResponseBytes": args.max_response_bytes,
            "coldIntervalSeconds": args.cold_interval_seconds,
            "counts": {
                "coldGet": args.cold_get_count,
                "warmGet": args.warm_get_count,
                "empty": args.empty_count,
                "warmConsumption": args.warm_consumption_count,
                "coldConsumption": args.cold_consumption_count,
                "duplicateRetry": args.duplicate_retry_count,
                "newPurchase": args.new_purchase_count,
                "mixed": args.mixed_count,
                "partialRejection": args.partial_rejection_count,
                "concurrentPairs": args.concurrent_pair_count,
            },
        },
        "correctness": {
            "passed": passed_count == len(runner.records),
            "totalRecords": len(runner.records),
            "passedRecords": passed_count,
            "failedRecords": len(runner.records) - passed_count,
        },
        "summaries": summaries,
        "concurrencyPairs": runner.concurrency_pairs,
        "records": runner.records,
    }
    return evidence


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        validate_args(args)
        json_path, markdown_path = output_paths(args.output)
        for path in (json_path, markdown_path):
            if path.exists() and not args.overwrite:
                raise ConfigurationError(
                    f"Refusing to overwrite {path}; choose a new --output or pass --overwrite"
                )
        evidence = execute(args)
        write_evidence(
            evidence,
            json_path,
            markdown_path,
            overwrite=args.overwrite,
        )
        print(f"JSON evidence: {json_path.resolve()}")
        print(f"Markdown evidence: {markdown_path.resolve()}")
        return 0 if evidence["correctness"]["passed"] else 1
    except (SafetyError, ConfigurationError) as exc:
        print(f"SAFETY/CONFIGURATION STOP: {exc}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("Interrupted; no further requests will be sent.", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
