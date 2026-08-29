import unittest
import tempfile
from pathlib import Path

from bridge_protocol import RepairRequest
from devdeck import build_incident_payload
from relay_server import repair_for_incident, sandbox_project_root


def valid_payload():
    return {
        "type": "repair",
        "protocol_version": 2,
        "incident_id": "incident-1",
        "project_id": "project-1",
        "file": "src/example.py",
        "expected_sha256": "a" * 64,
        "patch_type": "single_line",
        "line": 4,
        "code": "return value",
        "confidence": 0.95,
    }


class BridgeProtocolTest(unittest.TestCase):
    def test_repair_requires_a_matching_captured_incident(self):
        with self.assertRaisesRegex(ValueError, "unknown incident"):
            repair_for_incident(valid_payload(), {})

    def test_repair_rejects_a_path_outside_the_captured_project(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project = Path(temp_dir) / "project"
            project.mkdir()
            source = project / "src" / "example.py"
            source.parent.mkdir()
            source.write_text("print('safe')\n", encoding="utf-8")
            outside = Path(temp_dir) / "outside.py"
            outside.write_text("print('unsafe')\n", encoding="utf-8")
            incident = build_incident_payload(
                "py -3.11 src/example.py",
                f'File "{source}", line 1\nValueError: boom',
                project,
            )
            payload = valid_payload()
            payload.update({
                "incident_id": incident["incident_id"],
                "project_id": incident["project_id"],
                "file": "../outside.py",
                "expected_sha256": "a" * 64,
            })

            with self.assertRaisesRegex(ValueError, "outside trusted project root"):
                repair_for_incident(payload, {incident["incident_id"]: incident})

    def test_sandbox_uses_only_the_captured_project_root(self):
        incident = {"project_root": "C:/trusted/project"}

        self.assertEqual("C:/trusted/project", sandbox_project_root(incident))

    def test_incident_payload_uses_a_uuid_and_project_relative_path(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            project = Path(temp_dir)
            source = project / "src" / "example.py"
            source.parent.mkdir()
            source.write_text("raise ValueError('boom')\n", encoding="utf-8")
            stderr = f'File "{source}", line 1\nValueError: boom'

            incident = build_incident_payload("py -3.11 src/example.py", stderr, project)

        self.assertEqual("incident", incident["type"])
        self.assertEqual(2, incident["protocol_version"])
        self.assertEqual("src/example.py", incident["error_file"])
        self.assertEqual(64, len(incident["expected_sha256"]))
        self.assertTrue(incident["incident_id"])
        self.assertTrue(incident["project_id"])

    def test_accepts_a_complete_v2_single_line_repair(self):
        repair = RepairRequest.from_dict(valid_payload())

        self.assertEqual("incident-1", repair.incident_id)
        self.assertEqual("single_line", repair.patch_type)

    def test_rejects_versions_other_than_two(self):
        payload = valid_payload()
        payload["protocol_version"] = 1

        with self.assertRaisesRegex(ValueError, "protocol_version"):
            RepairRequest.from_dict(payload)

    def test_rejects_single_line_payload_with_diff_text(self):
        payload = valid_payload()
        payload["diff_text"] = "@@ -1 +1 @@\n-old\n+new"

        with self.assertRaisesRegex(ValueError, "diff_text"):
            RepairRequest.from_dict(payload)

    def test_rejects_diff_payload_with_code(self):
        payload = valid_payload()
        payload.update({
            "patch_type": "diff",
            "diff_text": "@@ -1 +1 @@\n-old\n+new",
        })

        with self.assertRaisesRegex(ValueError, "code"):
            RepairRequest.from_dict(payload)

    def test_rejects_non_sha256_expected_hash(self):
        payload = valid_payload()
        payload["expected_sha256"] = "not-a-hash"

        with self.assertRaisesRegex(ValueError, "expected_sha256"):
            RepairRequest.from_dict(payload)


if __name__ == "__main__":
    unittest.main()
