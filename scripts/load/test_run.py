"""Offline process-ownership regression: readiness failure cannot leak our child."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock,patch
import run

class AppOwnershipTest(unittest.TestCase):
    def test_readiness_failure_stops_own_child(self):
        with tempfile.TemporaryDirectory() as directory:
            base=Path(directory);jar=base/'input.jar';jar.write_bytes(b'fixture')
            child=Mock();child.pid=12345;child.poll.return_value=None
            with patch.object(run.subprocess,'Popen',return_value=child),patch.object(run,'api',side_effect=ConnectionError),patch.object(run.time,'sleep'):
                with self.assertRaisesRegex(RuntimeError,'did not become ready'):
                    run.start_app(dict(private=directory,run='offline'),jar)
            child.terminate.assert_called_once();child.wait.assert_called_once_with(timeout=20)

if __name__=='__main__':unittest.main()
