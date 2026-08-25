import unittest
import os
from patch_manager import PatchManager

class TestPatchManager(unittest.TestCase):
    def setUp(self):
        self.patch_mgr = PatchManager()
        self.test_file = "temp_test.py"
        with open(self.test_file, "w") as f:
            f.write("def get_token(user):\n    print('test')\n    return user.token\n")

    def tearDown(self):
        if os.path.exists(self.test_file):
            os.remove(self.test_file)

    def test_dry_run_compile_valid(self):
        content = "def test():\n    pass\n"
        ok, err = self.patch_mgr.dry_run_compile_check(self.test_file, content)
        self.assertTrue(ok)

    def test_dry_run_compile_invalid(self):
        content = "def test(\n    pass"  # Missing closing paren
        ok, err = self.patch_mgr.dry_run_compile_check(self.test_file, content)
        self.assertFalse(ok)
