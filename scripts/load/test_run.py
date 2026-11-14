"""Offline process-ownership regression: readiness failure cannot leak our child."""
import sys
import json
import subprocess
from contextlib import ExitStack,redirect_stdout
from io import StringIO
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock,patch
import run

class AppOwnershipTest(unittest.TestCase):
    def test_manifest_write_failure_stops_owned_child(self):
        with tempfile.TemporaryDirectory() as directory:
            jar=Path(directory)/'input.jar';jar.touch()
            child=Mock();child.pid=12345;child.poll.return_value=None
            with patch.object(run.subprocess,'Popen',return_value=child),patch.object(run,'save',side_effect=OSError('disk full')):
                with self.assertRaises(OSError):run.start_app(dict(private=directory,run='offline'),jar)
            child.terminate.assert_called_once();child.wait.assert_called_once_with(timeout=20)

    def test_child_environment_excludes_configuration_and_java_overrides(self):
        poison={key:'remote-secret' for key in ['SPRING_DATASOURCE_URL','SPRING_DATA_REDIS_HOST','SPRING_APPLICATION_JSON','SPRING_CONFIG_LOCATION','SPRING_PROFILES_ACTIVE','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH','SMARTLIFE_DB_HOST','ROCKETMQ_NAME_SERVER']}
        with tempfile.TemporaryDirectory() as directory:
            jar=Path(directory)/'input.jar';jar.touch()
            child=Mock();child.pid=12345;child.poll.return_value=None
            with patch.dict(run.os.environ,poison),patch.object(run.subprocess,'Popen',return_value=child) as launch,patch.object(run,'api',return_value={'success':True}):
                run.start_app(dict(private=directory,run='offline'),jar)
            env=launch.call_args.kwargs['env']
            self.assertNotIn('remote-secret',env.values())
            self.assertFalse(any(key.upper().startswith(('SPRING_','JAVA_TOOL','JDK_JAVA','_JAVA','ROCKETMQ_')) for key in env))
            self.assertEqual(env['SMARTLIFE_DB_HOST'],'127.0.0.1')
            self.assertEqual(env['SMARTLIFE_REDIS_PORT'],'16379')
            self.assertEqual(launch.call_args.kwargs['cwd'],Path(directory))

    def test_readiness_failure_stops_own_child(self):
        with tempfile.TemporaryDirectory() as directory:
            base=Path(directory);jar=base/'input.jar';jar.write_bytes(b'fixture')
            child=Mock();child.pid=12345;child.poll.return_value=None
            with patch.object(run.subprocess,'Popen',return_value=child),patch.object(run,'api',side_effect=ConnectionError),patch.object(run.time,'sleep'),patch.object(run.time,'monotonic',side_effect=[0,0,0,0,91]):
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
            manifest=dict(private=directory,run='offline',shopIds=['1'],userIds=[str(i) for i in range(101)])
            argv=['run.py','--jar',str(jar),'--jmeter',str(jmeter),'--output',str(output)]
            with patch.object(sys,'argv',argv),patch.object(run,'command',side_effect=['source','']),patch.object(run,'guard'),patch.object(run,'fixture',return_value=manifest),patch.object(run,'save'),patch.object(run,'start_app',return_value=child),patch.object(run,'stop_app') as stop,patch.object(run,'cleanup') as clean,patch.object(run,'api',return_value={'success':True}),patch.object(run,'publish',return_value='1'),patch.object(run,'jmeter',return_value=([],{})),patch.object(run,'snapshot',side_effect=subprocess.TimeoutExpired('docker',5)):
                with self.assertRaises(subprocess.TimeoutExpired):run.main()
            stop.assert_called_once_with(child);clean.assert_not_called()
            self.assertTrue((output/'orders-1-timeout.json').is_file())

class FailureGatesTest(unittest.TestCase):
    def main_failure(self,mode,observed=None):
        with tempfile.TemporaryDirectory() as directory,ExitStack() as stack:
            base=Path(directory);jar=base/'app.jar';jar.touch()
            jmeter=base/'jmeter';(jmeter/'bin').mkdir(parents=True);(jmeter/'bin/ApacheJMeter.jar').touch()
            output=base/'results';child=Mock()
            manifest=dict(private=directory,run='offline',shopIds=['1'],userIds=[str(i) for i in range(101)])
            stack.enter_context(patch.object(sys,'argv',['run.py','--jar',str(jar),'--jmeter',str(jmeter),'--output',str(output)]))
            for name,value in [('command',Mock(side_effect=['source',''])),('guard',Mock()),('fixture',Mock(return_value=manifest)),('save',Mock()),('start_app',Mock(return_value=child)),('stop_app',Mock()),('cleanup',Mock()),('publish',Mock(return_value='1')),('jmeter',Mock(return_value=([dict(userId='1',requestKey='key',outcome='SYSTEM_ERROR',orderId='',token='secret')],{})))]:
                stack.enter_context(patch.object(run,name,value))
            warmup=stack.enter_context(patch.object(run,'api',return_value={'success':mode!='warmup'}))
            state=observed if observed is not None else dict(requests=[dict(orderId='101',userId='1',requestKey='key',state='PREPARING')],failures=[dict(orderId='102',compensation='manual')])
            result=dict(passed=False,errors=['unresolved'],effectiveOrders=0)
            stack.enter_context(patch.object(run,'reconcile',return_value=result))
            stack.enter_context(patch.object(run.time,'sleep'))
            stack.enter_context(patch.object(run.time,'monotonic',side_effect=[100,100,100,161,161] if mode=='failed' else lambda:100))
            stack.enter_context(patch.object(run,'snapshot',side_effect=[state,subprocess.TimeoutExpired('secret command',5)] if mode=='timeout' else ([subprocess.TimeoutExpired('secret command',5)] if mode=='first-timeout' else lambda *a,**k:state)))
            stream=StringIO()
            with redirect_stdout(stream):
                with self.assertRaises((RuntimeError,subprocess.TimeoutExpired)):run.main()
            warmup.assert_called_once_with('/shop/1')
            run.stop_app.assert_called_once_with(child);run.cleanup.assert_not_called()
            self.assertNotIn('PASS',stream.getvalue())
            if mode in ('timeout','first-timeout'):
                evidence=json.loads((output/'orders-1-timeout.json').read_text())
                self.assertNotIn('secret',json.dumps(evidence))
                self.assertEqual(len(evidence['expectedResponses']),100)
                self.assertEqual(evidence['responses'][0]['outcome'],'SYSTEM_ERROR')
                if mode=='timeout':
                    self.assertEqual(evidence['lastCompleteObservation']['snapshot'],state)
                    if observed is None:
                        self.assertEqual(evidence['unresolved']['pendingOrderIds'],['101'])
                        self.assertEqual(evidence['unresolved']['manualOrderIds'],['102'])
                    self.assertEqual(evidence['unresolved']['systemErrorResponses'][0]['userId'],'1')
                else:self.assertEqual(evidence['durableState'],'unavailable')
                return evidence

    def test_rejected_pending_compensation_is_pending_not_manual(self):
        evidence=self.main_failure('timeout',dict(requests=[dict(orderId='101',state='REJECTED')],failures=[dict(orderId='101',compensation='pending')]))
        self.assertEqual(evidence['unresolved']['pendingOrderIds'],['101'])
        self.assertEqual(evidence['unresolved']['manualOrderIds'],[])

    def test_review_without_failure_is_manual_not_pending(self):
        evidence=self.main_failure('timeout',dict(requests=[dict(orderId='101',state='REVIEW')],failures=[]))
        self.assertEqual(evidence['unresolved']['pendingOrderIds'],[])
        self.assertEqual(evidence['unresolved']['manualOrderIds'],['101'])

    def test_duplicate_records_count_once_and_manual_takes_precedence(self):
        evidence=self.main_failure('timeout',dict(requests=[dict(orderId='101',state='PREPARING'),dict(orderId='101',state='PENDING'),dict(orderId='102',state='REVIEW')],failures=[dict(orderId='101',compensation='pending'),dict(orderId='101',compensation='pending'),dict(orderId='102',compensation='pending'),dict(orderId='102',compensation='manual'),dict(orderId='102',compensation='manual'),dict(orderId='103',compensation='done')]))
        self.assertEqual(evidence['unresolved']['pendingOrderIds'],['101'])
        self.assertEqual(evidence['unresolved']['manualOrderIds'],['102'])
        self.assertEqual(evidence['unresolved']['counts'],dict(pendingOrderIds=1,manualOrderIds=1,systemErrorResponses=1))

    def test_failed_reconciliation_retains_fixtures(self):self.main_failure('failed')
    def test_warmup_runs_and_failure_retains_fixtures(self):self.main_failure('warmup')
    def test_timeout_retains_last_complete_snapshot_and_classification(self):self.main_failure('timeout')
    def test_first_snapshot_timeout_preserves_redacted_http_and_expected_ids(self):self.main_failure('first-timeout')

    def test_container_port_guard_cannot_be_optimized_away(self):
        with patch.object(run,'command',return_value=json.dumps([{'NetworkSettings':{'Ports':{'3306/tcp':[{'HostPort':'3306'}]}}}])):
            with self.assertRaises(RuntimeError):run.guard()

    def test_redis_pipeline_errors_cannot_be_optimized_away(self):
        with patch.object(run.subprocess,'run',return_value=Mock(stdout=b'errors: 1')):
            with self.assertRaises(RuntimeError):run.redis_pipeline([['PING']])

if __name__=='__main__':unittest.main()
