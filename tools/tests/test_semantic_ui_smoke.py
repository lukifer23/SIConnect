"""Pure parser fixtures; these do not claim physical semantic action coverage."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0,str(Path(__file__).parents[1]))
from semantic_ui_smoke import screen_precondition, session_probe_absent


class ScreenPreconditionTests(unittest.TestCase):
    def test_exec_out_missing_file_is_detected_from_either_stream_only_for_exact_session(self):
        path='no_backup/semantic-harness-fixture.json'
        message=('ls: '+path+': No such file or directory\n').encode()
        self.assertTrue(session_probe_absent(message,b'',path))
        self.assertTrue(session_probe_absent(b'',message,path))
        self.assertFalse(session_probe_absent(message,b'',path+'-other'))
        self.assertFalse(session_probe_absent(b'run-as: permission denied',b'',path))

    def test_current_unlocked_on_state_is_accepted(self):
        screen_precondition('KeyguardServiceDelegate state:\n  showing=false\n  screenState=SCREEN_STATE_ON\n')

    def test_locked_off_and_unknown_are_rejected_before_session_setup(self):
        for text in ('showing=true\nscreenState=SCREEN_STATE_ON', 'showing=false\nscreenState=SCREEN_STATE_OFF', 'unknown OEM dump', 'showing=false'):
            with self.subTest(text=text), self.assertRaises(RuntimeError):
                screen_precondition(text)
