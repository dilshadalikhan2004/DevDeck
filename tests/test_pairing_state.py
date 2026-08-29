import tempfile
import unittest
from pathlib import Path

from pairing_state import PairingRegistry


class PairingRegistryTest(unittest.TestCase):
    def test_enrollment_token_is_single_use_and_not_persisted_in_plaintext(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            state_path = Path(temp_dir) / "pairings.json"
            registry = PairingRegistry(state_path)

            enrollment = registry.create_enrollment("wss://192.168.1.8:8765", "sha256/fingerprint")
            stored = state_path.read_text(encoding="utf-8")

            self.assertNotIn(enrollment.token, stored)
            self.assertTrue(registry.consume_enrollment(enrollment.token, "phone-key-1"))
            self.assertFalse(registry.consume_enrollment(enrollment.token, "phone-key-1"))
            self.assertTrue(registry.is_paired("phone-key-1"))

    def test_expired_enrollment_cannot_register_a_phone(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            registry = PairingRegistry(Path(temp_dir) / "pairings.json")
            enrollment = registry.create_enrollment("wss://192.168.1.8:8765", "sha256/fingerprint", ttl_seconds=0)

            self.assertFalse(registry.consume_enrollment(enrollment.token, "phone-key-2"))
            self.assertFalse(registry.is_paired("phone-key-2"))


if __name__ == "__main__":
    unittest.main()
