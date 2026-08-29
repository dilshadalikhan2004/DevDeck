import json
import unittest


class ExistingRepairContractTest(unittest.TestCase):
    def test_legacy_single_line_payload_round_trips(self):
        payload = {
            "type": "repair",
            "patch_type": "single_line",
            "file": "example.py",
            "line": 4,
            "code": "return value",
        }

        self.assertEqual(json.loads(json.dumps(payload))["patch_type"], "single_line")
