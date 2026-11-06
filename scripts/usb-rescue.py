#!/usr/bin/env python3
"""Temporarily restart the already-paired DuoDash helper over an authorized USB ADB link.

This does not pair a device, change the setup-completion state, enable app permissions
other than WRITE_SECURE_SETTINGS, or make the helper survive an Android reboot.
"""
import argparse
import re
import secrets
import shlex
import shutil
import subprocess
import time

PACKAGE = "dev.nova.deck"
HELPER_CLASS = "dev.nova.deck.bridge.BridgeMain"
TOKEN_RE = re.compile(r"^[a-f0-9]{64}$")


class RescueError(RuntimeError):
    pass


def run(command, *, input_text=None, timeout=15, check=True):
    try:
        result = subprocess.run(command, input=input_text, capture_output=True, text=True,
                                timeout=timeout, check=False)
    except subprocess.TimeoutExpired as exc:
        raise RescueError("ADB timed out. Check the USB cable and authorization, then retry.") from exc
    if check and result.returncode:
        # Never include command text or stdin in user-facing errors: they may contain the session token.
        detail = result.stderr.strip()
        raise RescueError("ADB command failed" + (f": {detail}" if detail else "."))
    return result


def connected_usb_serial(adb, serial):
    result = run([adb, "devices", "-l"])
    for line in result.stdout.splitlines()[1:]:
        fields = line.split()
        if fields and fields[0] == serial:
            if len(fields) < 2 or fields[1] != "device":
                raise RescueError("The selected USB device is not authorized. Unlock it and accept its USB debugging prompt.")
            if not any(field.startswith("usb:") for field in fields[2:]):
                raise RescueError("The selected serial is not an active USB ADB transport. Choose its USB serial, not a wireless entry.")
            return
    raise RescueError("That USB serial is not listed by adb. Connect one authorized USB device and retry.")


def shell(base, *args, input_text=None, timeout=15, check=True):
    # adb shell accepts one remote command string. Keep the remote argv boundaries
    # intact (especially for `run-as ... sh -c ...`) without invoking a host shell.
    return run(base + ["shell", shlex.join(args)], input_text=input_text, timeout=timeout, check=check)


def installed_uid(base):
    result = shell(base, "dumpsys", "package", PACKAGE)
    match = re.search(r"\buserId=(\d+)\b", result.stdout)
    if not match:
        raise RescueError("Could not read DuoDash's installed application UID.")
    return int(match.group(1))


def paired_and_token(base):
    paired = shell(base, "run-as", PACKAGE, "cat", "shared_prefs/bridge_activation.xml").stdout
    if not re.search(r'<boolean\s+name="paired"\s+value="true"\s*/?>', paired):
        raise RescueError("This rescue works only after DuoDash has been paired in Wireless debugging.")
    key = shell(base, "run-as", PACKAGE, "test", "-f", "files/adb-host.pk8", check=False)
    cert = shell(base, "run-as", PACKAGE, "test", "-f", "files/adb-host.pem", check=False)
    if key.returncode or cert.returncode:
        raise RescueError("DuoDash's saved wireless identity is incomplete. The existing pairing was left untouched.")
    token_file = shell(base, "run-as", PACKAGE, "test", "-e", "files/bridge-token", check=False)
    if token_file.returncode == 0:
        token = shell(base, "run-as", PACKAGE, "cat", "files/bridge-token").stdout.strip()
        if not TOKEN_RE.fullmatch(token):
            raise RescueError("DuoDash's saved helper session is invalid. The existing pairing and file were left untouched.")
    else:
        token = secrets.token_hex(32)
        command = ("umask 077; IFS= read -r session_token; "
                   "printf '%s' \"$session_token\" > files/.bridge-token.new && "
                   "chmod 600 files/.bridge-token.new && mv files/.bridge-token.new files/bridge-token")
        shell(base, "run-as", PACKAGE, "sh", "-c", command, input_text=token + "\n")
        persisted = shell(base, "run-as", PACKAGE, "cat", "files/bridge-token").stdout.strip()
        if persisted != token:
            raise RescueError("Could not safely create DuoDash's private helper session. Pairing was left untouched.")
    return token


def helper_pids(base):
    result = shell(base, "pidof", "nova_deck", check=False)
    return [part for part in result.stdout.split() if part.isdecimal()]


def helper_identity_matches(uid, cmdline, classpath, installed_apk):
    """Identify this helper by shell UID, Android nice-name, and its app's APK path."""
    process_name = cmdline.split("\0", 1)[0].strip()
    package_dir = re.search(r"/(dev\.nova\.deck-[^/]+)/base\.apk$", installed_apk)
    classpath_dir = re.search(r"/(dev\.nova\.deck-[^/]+)/base\.apk$", classpath)
    return (uid == "2000" and process_name == "nova_deck" and package_dir is not None
            and classpath_dir is not None and classpath.startswith("/data/app/")
            and classpath_dir.group(1).startswith("dev.nova.deck-"))


def checked_helper_cmdline(base, pid, installed_apk):
    # Never print or log cmdline: it may contain the private session token.
    cmdline = shell(base, "cat", f"/proc/{pid}/cmdline", check=False).stdout
    uid = shell(base, "sh", "-c", f"awk '/^Uid:/ {{print $2}}' /proc/{pid}/status", check=False).stdout.strip()
    classpath = shell(base, "sh", "-c", f"tr '\\000' '\\n' < /proc/{pid}/environ | sed -n 's/^CLASSPATH=//p'", check=False).stdout.strip()
    return helper_identity_matches(uid, cmdline, classpath, installed_apk)


def restart_helper(base, apk_path, app_uid, token):
    pids = helper_pids(base)
    for pid in pids:
        if not checked_helper_cmdline(base, pid, apk_path):
            raise RescueError("A process named nova_deck is present but does not match DuoDash's helper. It was left untouched.")
    if pids:
        shell(base, "kill", *pids)
        deadline = time.monotonic() + 2.0
        while time.monotonic() < deadline and any(pid in helper_pids(base) for pid in pids):
            time.sleep(0.1)
        if any(pid in helper_pids(base) for pid in pids):
            raise RescueError("DuoDash's old helper did not stop; no other process was touched.")

    # Read the token through stdin so it is not embedded in the host process arguments or shell command.
    command = ("IFS= read -r session_token; "
               f"CLASSPATH={shlex.quote(apk_path)} nohup app_process /system/bin "
               f"--nice-name=nova_deck {HELPER_CLASS} {app_uid} \"$session_token\" "
               ">/data/local/tmp/nova-deck-bridge.log 2>&1 </dev/null &")
    shell(base, "sh", "-c", command, input_text=token + "\n", timeout=10)
    deadline = time.monotonic() + 5.0
    while time.monotonic() < deadline:
        for pid in helper_pids(base):
            if checked_helper_cmdline(base, pid, apk_path):
                return
        time.sleep(0.2)
    raise RescueError("DuoDash's helper did not start. Pairing and app data were left intact.")


def reconnect_permission_granted(base):
    # Android 13 on the Nova has no `pm check-permission` command.
    details = shell(base, "dumpsys", "package", PACKAGE).stdout
    return re.search(r"android\.permission\.WRITE_SECURE_SETTINGS: granted=true\b", details) is not None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True, help="Authorized USB ADB serial, not a wireless serial")
    args = parser.parse_args()
    adb = shutil.which("adb")
    if not adb:
        raise SystemExit("Install Android Platform Tools and make adb available on PATH.")
    try:
        connected_usb_serial(adb, args.device)
        base = [adb, "-s", args.device]
        model = shell(base, "getprop", "ro.product.model").stdout.strip()
        if "nova" not in model.lower():
            raise RescueError("This rescue script is limited to the Retroid Pocket Nova; the selected device model was not recognized.")
        uid = installed_uid(base)
        apk_result = shell(base, "pm", "path", PACKAGE)
        match = re.search(r"^package:(/data/app/\S+\.apk)$", apk_result.stdout.strip(), re.M)
        if not match:
            raise RescueError("Could not locate the installed DuoDash APK.")
        token = paired_and_token(base)
        if not reconnect_permission_granted(base):
            shell(base, "pm", "grant", PACKAGE, "android.permission.WRITE_SECURE_SETTINGS")
            if not reconnect_permission_granted(base):
                raise RescueError("Android did not grant the persistent reconnect permission.")
        restart_helper(base, match.group(1), uid, token)
        print("DuoDash's paired helper is running for this session. Return to DuoDash and tap Reconnect.")
        print("This USB rescue does not create pairing or setup completion, and the helper will not survive a reboot by itself.")
    except RescueError as exc:
        raise SystemExit(str(exc)) from None


if __name__ == "__main__":
    main()
