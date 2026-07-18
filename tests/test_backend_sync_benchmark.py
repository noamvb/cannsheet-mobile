"""Pure unit tests for backend_sync_benchmark.py.

These tests never open a network connection and never contact an Apps Script
endpoint.
"""

import datetime as dt
import unittest
import uuid

import backend_sync_benchmark as benchmark


class DeterministicPayloadTests(unittest.TestCase):
    def setUp(self) -> None:
        self.namespace = uuid.UUID("90ab942e-e535-4ba9-9b76-adc99d4ff80a")
        self.product = benchmark.ProductReference(
            product_id="*P1",
            product_uuid="f4ce9f77-df47-4a3c-9cf1-a99527a56730",
            status=0,
        )
        self.timestamp = dt.datetime(2030, 1, 15, 12, 0, 0)

    def test_deterministic_uuid_is_stable_and_role_specific(self) -> None:
        first = benchmark.deterministic_uuid(self.namespace, "scenario", 3, "request")
        second = benchmark.deterministic_uuid(self.namespace, "scenario", 3, "request")
        item = benchmark.deterministic_uuid(self.namespace, "scenario", 3, "event")

        self.assertEqual(first, second)
        self.assertNotEqual(first, item)
        self.assertEqual(uuid.UUID(first).version, 5)

    def test_empty_payload_has_v2_sandbox_contract(self) -> None:
        payload = benchmark.build_empty_payload(self.namespace, 0)

        self.assertEqual(payload["apiVersion"], 2)
        self.assertEqual(payload["environment"], "SANDBOX")
        self.assertEqual(payload["purchases"], [])
        self.assertEqual(payload["consumptions"], [])
        uuid.UUID(payload["requestId"])

    def test_consumption_payload_is_stable_and_references_current_product(self) -> None:
        first = benchmark.build_consumption_payload(
            self.namespace,
            "one_consumption_warm",
            2,
            self.product,
            self.timestamp,
        )
        second = benchmark.build_consumption_payload(
            self.namespace,
            "one_consumption_warm",
            2,
            self.product,
            self.timestamp,
        )

        self.assertEqual(first, second)
        item = first["consumptions"][0]
        self.assertEqual(item["productId"], "*P1")
        self.assertEqual(item["productUuid"], self.product.product_uuid)
        self.assertEqual(item["uses"], 1.0)
        self.assertFalse(item["isFinished"])

    def test_new_purchase_contains_only_synthetic_values(self) -> None:
        payload = benchmark.build_purchase_payload(self.namespace, 4, self.timestamp)
        purchase = payload["purchases"][0]

        self.assertEqual(purchase["name"], "Benchmark Product 004")
        self.assertTrue(purchase["tempId"].startswith("benchmark-temp-"))
        uuid.UUID(purchase["actionId"])

    def test_mixed_payload_consumes_new_purchase_by_temp_id(self) -> None:
        payload = benchmark.build_mixed_payload(self.namespace, 1, self.timestamp)

        self.assertEqual(len(payload["purchases"]), 1)
        self.assertEqual(len(payload["consumptions"]), 1)
        self.assertEqual(
            payload["consumptions"][0]["productId"],
            payload["purchases"][0]["tempId"],
        )

    def test_partial_rejection_has_one_known_and_one_unknown_product(self) -> None:
        payload = benchmark.build_partial_rejection_payload(
            self.namespace, 7, self.product, self.timestamp
        )
        accepted, rejected = payload["consumptions"]

        self.assertEqual(accepted["productId"], self.product.product_id)
        self.assertNotEqual(rejected["productUuid"], self.product.product_uuid)
        self.assertNotIn("productId", rejected)
        self.assertNotEqual(accepted["eventId"], rejected["eventId"])

    def test_suite_name_cannot_change_payload_ids(self) -> None:
        # Payload helpers deliberately accept no suite argument. A baseline and
        # optimized runner using this namespace therefore submit the same IDs.
        baseline = benchmark.build_empty_payload(self.namespace, 8)
        optimized = benchmark.build_empty_payload(self.namespace, 8)
        self.assertEqual(baseline, optimized)


class StatisticsTests(unittest.TestCase):
    def test_small_sample_omits_p95(self) -> None:
        summary = benchmark.summarize_numbers(range(1, 20))

        self.assertEqual(summary["count"], 19)
        self.assertEqual(summary["min"], 1.0)
        self.assertEqual(summary["median"], 10.0)
        self.assertEqual(summary["max"], 19.0)
        self.assertNotIn("p95", summary)

    def test_twenty_samples_include_nearest_rank_p95(self) -> None:
        summary = benchmark.summarize_numbers(range(1, 21))

        self.assertEqual(summary["count"], 20)
        self.assertEqual(summary["median"], 10.5)
        self.assertEqual(summary["p95"], 19.0)

    def test_none_values_are_ignored(self) -> None:
        summary = benchmark.summarize_numbers([None, 10.0, 30.0])
        self.assertEqual(summary["count"], 2)
        self.assertEqual(summary["median"], 20.0)


class SelectionAndExtractionTests(unittest.TestCase):
    def test_reference_selection_prefers_active_then_stable_uuid(self) -> None:
        products = [
            {"id": "*P3", "productUuid": "c", "status": 2},
            {"id": "*P2", "productUuid": "b", "status": 0},
            {"id": "*P1", "productUuid": "a", "status": 0},
        ]

        selected = benchmark.select_reference_product(products)
        self.assertEqual(selected.product_id, "*P1")

    def test_extract_server_duration_supports_top_level_and_nested_fields(self) -> None:
        self.assertEqual(
            benchmark.extract_server_duration_ms({"serverDurationMs": 12.5}), 12.5
        )
        self.assertEqual(
            benchmark.extract_server_duration_ms({"timing": {"totalHandlerMs": 9}}),
            9.0,
        )
        self.assertIsNone(benchmark.extract_server_duration_ms({"success": True}))

    def test_partial_rejection_checks_backend_field_names(self) -> None:
        event_id = "a85285cc-68a2-4e07-9cc0-ec5b92d0b19f"
        payload = {
            "requestId": "7c166627-2887-4c7f-a6fc-43e6776db927",
            "purchases": [],
            "consumptions": [{"eventId": event_id}],
        }
        response = {
            "success": True,
            "allAccepted": False,
            "requestId": payload["requestId"],
            "environment": "SANDBOX",
            "acknowledgedConsumptions": [],
            "rejectedConsumptions": [
                {
                    "eventId": event_id,
                    "errorCode": "UNKNOWN_PRODUCT",
                    "message": "Unknown product reference",
                }
            ],
        }
        observation = benchmark.HttpObservation(
            method="POST",
            http_status=200,
            wall_time_ms=1.0,
            response=response,
            effective_url="https://example.invalid/exec",
        )

        checks = benchmark.evaluate_post_response(
            observation,
            payload,
            expected_rejections={event_id: "UNKNOWN_PRODUCT"},
            expected_all_accepted=False,
        )

        rejection_check = next(
            item for item in checks if item["name"] == "event_rejection_code"
        )
        self.assertTrue(rejection_check["passed"])


if __name__ == "__main__":
    unittest.main()
