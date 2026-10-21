"""Real bounded host subprocess checks; not Android capability evidence."""
import importlib.util
from pathlib import Path
import stat
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('siconnect_audit', Path(__file__).parents[1] / 'adb_audit.py')
audit = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = audit
spec.loader.exec_module(audit)


class AuditToolTests(unittest.TestCase):
    def test_both_streams_are_bounded_and_marked_truncated(self):
        result = audit.run_bounded([sys.executable, '-c', "import os;os.write(1,b'a'*100000);os.write(2,b'b'*100000)"], limit=1024)
        self.assertEqual(0, result.code)
        self.assertEqual(1024, len(result.stdout))
        self.assertEqual(1024, len(result.stderr))
        self.assertTrue(result.stdout_truncated and result.stderr_truncated)

    def test_timeout_preserves_observed_partial_output(self):
        result = audit.run_bounded([sys.executable, '-c', "import time;print('started',flush=True);time.sleep(10)"], timeout=.2)
        self.assertTrue(result.timed_out)
        self.assertIn('started', result.stdout)
        self.assertLess(result.seconds, 2)

    def test_upstream_failure_is_preserved(self):
        result = audit.run_bounded(['/bin/bash', '-o', 'pipefail', '-c', 'false | cat'])
        self.assertNotEqual(0, result.code)

    def test_private_output_rejects_repository_and_overwrite(self):
        with self.assertRaises(ValueError):
            audit.outside_repository(audit.REPO_ROOT)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'evidence.json'
            with audit.private_new_file(path) as stream:
                stream.write('{}')
            self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
            with self.assertRaises(FileExistsError):
                audit.private_new_file(path)


if __name__ == '__main__':
    unittest.main()
