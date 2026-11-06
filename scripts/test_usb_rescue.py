import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
from types import SimpleNamespace


SCRIPT = Path(__file__).with_name("usb-rescue.py")
SPEC = importlib.util.spec_from_file_location("usb_rescue", SCRIPT)
usb_rescue = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(usb_rescue)


class UsbRescueTests(unittest.TestCase):
    def test_shell_serializes_remote_arguments_as_one_command_and_keeps_secret_in_stdin(self):
        token = "a" * 64
        with patch.object(usb_rescue, "run") as run:
            usb_rescue.shell(["adb", "-s", "USB123"], "run-as", "dev.nova.deck", "sh", "-c",
                             "IFS= read -r t; printf '%s' \"$t\" > files/bridge-token",
                             input_text=token)
        command = run.call_args.args[0]
        self.assertEqual(command[:3], ["adb", "-s", "USB123"])
        self.assertEqual(command[3], "shell")
        self.assertEqual(len(command), 5)
        self.assertIn("run-as dev.nova.deck sh -c", command[4])
        self.assertNotIn(token, " ".join(command))
        self.assertEqual(run.call_args.kwargs["input_text"], token)

    def test_helper_identity_accepts_nice_name_and_same_package_apk_path(self):
        installed = "/data/app/~~new/dev.nova.deck-current/base.apk"
        old_classpath = "/data/app/~~old/dev.nova.deck-previous/base.apk"
        self.assertTrue(usb_rescue.helper_identity_matches("2000", "nova_deck\0", old_classpath, installed))

    def test_helper_identity_rejects_other_uid_name_or_package(self):
        installed = "/data/app/~~new/dev.nova.deck-current/base.apk"
        classpath = "/data/app/~~new/dev.nova.deck-current/base.apk"
        self.assertFalse(usb_rescue.helper_identity_matches("1000", "nova_deck\0", classpath, installed))
        self.assertFalse(usb_rescue.helper_identity_matches("2000", "other\0", classpath, installed))
        self.assertFalse(usb_rescue.helper_identity_matches("2000", "nova_deck\0",
                                                           "/data/app/~~x/com.other-app/base.apk", installed))

    def test_reconnect_grant_uses_granted_state_not_requested_permission(self):
        for detail,expected in [
            ("requested permissions:\n android.permission.WRITE_SECURE_SETTINGS\n",False),
            ("android.permission.WRITE_SECURE_SETTINGS: granted=false\nandroid.permission.INTERNET: granted=true",False),
            ("android.permission.WRITE_SECURE_SETTINGS: granted=true",True),
        ]:
            with patch.object(usb_rescue,"shell",return_value=SimpleNamespace(stdout=detail)):
                self.assertEqual(usb_rescue.reconnect_permission_granted(["adb"]),expected)

    def test_restart_helper_starts_expected_class_and_keeps_token_on_stdin(self):
        token = "b" * 64
        with patch.object(usb_rescue, "helper_pids", side_effect=[[], ["4321"]]), \
             patch.object(usb_rescue, "checked_helper_cmdline", return_value=True), \
             patch.object(usb_rescue, "shell") as shell, \
             patch.object(usb_rescue.time, "monotonic", side_effect=[0.0, 1.0]):
            usb_rescue.restart_helper(["adb", "-s", "USB123"],
                                      "/data/app/~~new/dev.nova.deck-current/base.apk",
                                      10123, token)

        self.assertEqual(shell.call_count, 1)
        call = shell.call_args
        self.assertEqual(call.args[:2], (["adb", "-s", "USB123"], "sh"))
        remote_command = call.args[3]
        self.assertIn("dev.nova.deck.bridge.BridgeMain 10123", remote_command)
        self.assertNotIn(token, remote_command)
        self.assertEqual(call.kwargs["input_text"], token + "\n")


if __name__ == "__main__":
    unittest.main()
