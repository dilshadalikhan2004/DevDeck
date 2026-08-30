import unittest
from pathlib import Path

import devdeck


class ErrorMetadataTests(unittest.TestCase):
    def test_traceback_source_line(self):
        stderr = '''
Traceback (most recent call last):
  File "test_errors.py", line 7, in trigger_type_error
    print("Hello " + name)
TypeError: can only concatenate str (not "NoneType") to str
'''
        line = devdeck._source_line_from_traceback(stderr)
        self.assertEqual('print("Hello " + name)', line)

    def test_typeerror_metadata_from_repo_file(self):
        root = Path(__file__).resolve().parents[1]
        stderr = f'''
Traceback (most recent call last):
  File "{root / "test_errors.py"}", line 7, in trigger_type_error
    print("Hello " + name)
TypeError: can only concatenate str (not "NoneType") to str
'''
        ctx, path, line, orig = devdeck.get_error_metadata(stderr, root, 'python test_errors.py typeerror')
        self.assertTrue(path and path.endswith("test_errors.py"))
        self.assertEqual(7, line)
        self.assertIn("Hello", orig or "")
        self.assertIn("name", orig or "")


if __name__ == "__main__":
    unittest.main()
