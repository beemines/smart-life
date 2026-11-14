"""Offline process-ownership regression: readiness failure cannot leak our child."""
import sys
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

class DependencyDeadlineTest(unittest.TestCase):
    def test_snapshot_uses_remaining_budget_and_resets_it(self):
        import subprocess
        with patch.object(run.time,'monotonic',return_value=100),patch.object(run.subprocess,'run',side_effect=subprocess.TimeoutExpired('docker',5)) as call:
            with self.assertRaises(subprocess.TimeoutExpired):
                run.snapshot({'userIds':['0']*101,'run':'offline'},'1',[],1,deadline=105)
            self.assertEqual(call.call_args.kwargs['timeout'],5)
        self.assertIsNone(run.DEPENDENCY_DEADLINE.get())

    def test_drain_timeout_stops_owned_app_and_retains_evidence(self):
        import subprocess
        with tempfile.TemporaryDirectory() as directory:
            base=Path(directory);jar=base/'app.jar';jar.write_bytes(b'fixture')
            jmeter=base/'jmeter';(jmeter/'bin').mkdir(parents=True);(jmeter/'bin/ApacheJMeter.jar').touch()
            output=base/'results';child=Mock()
            manifest=dict(private=directory,run='offline',shopIds=['1'])
            argv=['run.py','--jar',str(jar),'--jmeter',str(jmeter),'--output',str(output)]
            with patch.object(sys,'argv',argv),patch.object(run,'command',side_effect=['source','']),patch.object(run,'guard'),patch.object(run,'fixture',return_value=manifest),patch.object(run,'save'),patch.object(run,'start_app',return_value=child),patch.object(run,'stop_app') as stop,patch.object(run,'cleanup') as clean,patch.object(run,'api',return_value={'success':True}),patch.object(run,'publish',return_value='1'),patch.object(run,'jmeter',return_value=([],{})),patch.object(run,'snapshot',side_effect=subprocess.TimeoutExpired('docker',5)):
                with self.assertRaises(subprocess.TimeoutExpired):run.main()
            stop.assert_called_once_with(child);clean.assert_not_called()
            self.assertTrue((output/'orders-1-timeout.json').is_file())

if __name__=='__main__':unittest.main()
