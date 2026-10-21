"""Controlled parser/counter fixtures; no device actions or claimed physical measurements."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).parents[1]))
import profile_device as profile


class DeviceProfileTests(unittest.TestCase):
    def sample(self, elapsed, cpu, *, pid=9, start=100, boot='session-one', version=3):
        return {'elapsed_seconds': elapsed, 'boot_session': boot, 'app_version': {'version_code': version},
                'app': {'available': True, 'pid': pid, 'start_ticks': start, 'cpu_ticks': cpu,
                        'observed_elapsed_seconds': elapsed, 'observed_at_ms': 1000000 + elapsed * 1000}}

    def test_process_stat_handles_spaces_parentheses_and_does_not_retain_status_identity(self):
        fields = ['S'] + ['0'] * 19
        fields[11], fields[12], fields[19] = '12', '4', '77'
        text = '123 (name (with) spaces) ' + ' '.join(fields) + '\nName:\tprivate name\nVmRSS:\t500 kB\nVmSize:\t900 kB\nUid:\t123\n'
        parsed = profile.parse_process(text, 123)
        self.assertEqual(16, parsed['cpu_ticks'])
        self.assertEqual(77, parsed['start_ticks'])
        self.assertEqual(500, parsed['rss_kib'])
        self.assertNotIn('private', str(parsed))
        self.assertFalse(profile.parse_process(text, 124)['available'])

    def test_cpu_percentage_uses_actual_process_counter_observation_times(self):
        samples = [self.sample(1, 20), self.sample(11, 30), self.sample(21, 40)]
        result = profile.cpu_summary(samples, 'app', 100)
        self.assertEqual(.2, result['cpu_seconds'])
        self.assertEqual(1.0, result['one_core_percent'])
        self.assertEqual(20, result['counter_observed_seconds'])

    def test_intermediate_process_replacement_cannot_hide_between_matching_endpoints(self):
        samples = [self.sample(0, 20), self.sample(10, 100, start=200), self.sample(20, 40)]
        result = profile.cpu_summary(samples, 'app', 100)
        self.assertNotIn('one_core_percent', result)
        self.assertEqual(2, result['excluded_intervals']['PROCESS_CHANGED'])

    def test_reboot_unknown_boot_app_upgrade_counter_reset_and_host_sleep_are_excluded(self):
        base = self.sample(0, 20)
        changed = [
            (self.sample(10, 30, boot='other'), 'BOOT_CHANGED_OR_UNKNOWN'),
            (self.sample(10, 30, boot=None), 'BOOT_CHANGED_OR_UNKNOWN'),
            (self.sample(10, 30, version=4), 'APP_VERSION_CHANGED'),
            (self.sample(10, 30, version=None), 'APP_VERSION_UNKNOWN'),
            (self.sample(10, 10), 'CLOCK_OR_COUNTER_REGRESSION'),
        ]
        sleeping = self.sample(10, 30); sleeping['app']['observed_at_ms'] += 60000
        changed.append((sleeping, 'HOST_CLOCK_OR_SLEEP_GAP'))
        for last, reason in changed:
            with self.subTest(reason=reason):
                result = profile.cpu_summary([base, last], 'app', 100)
                self.assertNotIn('one_core_percent', result)
                self.assertEqual(1, result['excluded_intervals'][reason])

    def test_conditions_keep_unknown_fields_unknown_and_distinguish_service_from_foreground(self):
        power = 'mWakefulness=Asleep\nmInteractive=false\nmIsPowered=true\nDisplay Power: state=OFF\n'
        policy = 'mScreenOnFully=false\nKeyguardServiceDelegate state:\n  showing=true\n  occluded=false\n'
        result = profile.parse_conditions(power, policy, '125\n')
        self.assertFalse(result['screen_interactive'])
        self.assertTrue(result['keyguard_showing'])
        self.assertEqual('FOREGROUND_SERVICE', result['app_importance_name'])
        self.assertIsNone(result['device_locked'])
        self.assertIsNone(profile.parse_conditions('mWakefulness=Asleep', '', '')['screen_interactive'])

    def test_installed_version_requires_exact_package_and_never_infers_local_source_version(self):
        text = 'package:dev.siconnect.app.test versionCode:99\npackage:dev.siconnect.app versionCode:7\n'
        self.assertEqual(7, profile.parse_version(text)['version_code'])
        self.assertIsNone(profile.parse_version('package:dev.siconnect.app.test versionCode:99')['version_code'])
        self.assertIsNone(profile.parse_version(text)['version_name'])


if __name__ == '__main__':
    unittest.main()
