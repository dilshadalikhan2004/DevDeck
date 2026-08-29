import unittest

from pipeline_events import crash_to_dispatch_events, humanize_sandbox_failure, make_event


class PipelineEventsTest(unittest.TestCase):
    def test_event_shape(self):
        event = make_event("inc-1", "sandbox_dry_run", "failed", "Sandbox dry-run failed: test suite exited with code 1")
        self.assertEqual("pipeline_event", event["type"])
        self.assertEqual("inc-1", event["incident_id"])
        self.assertEqual("sandbox_dry_run", event["stage"])
        self.assertEqual("failed", event["phase"])

    def test_rejects_unknown_stage(self):
        with self.assertRaises(ValueError):
            make_event("inc-1", "not_a_stage", "started", "nope")

    def test_indexing_is_skipped_when_cache_is_warm(self):
        events = crash_to_dispatch_events("inc-1", indexing_rebuilt=False, command="pytest")
        stages = [(e["stage"], e["phase"]) for e in events]
        self.assertIn(("context_indexing", "skipped"), stages)
        self.assertNotIn(("context_indexing", "started"), stages)

    def test_sandbox_failure_is_human_readable(self):
        message, detail = humanize_sandbox_failure(
            {"exit_code": 1, "sandbox_stderr": "E   AssertionError: boom\n" * 40},
            command="pytest tests/",
        )
        self.assertEqual("Sandbox dry-run failed: test suite exited with code 1", message)
        self.assertIn("Command: pytest tests/", detail)
        self.assertNotIn("E   AssertionError: boom\nE   AssertionError", message)
