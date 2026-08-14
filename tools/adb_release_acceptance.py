#!/usr/bin/env python3
"""Exact-APK PI//DECK acceptance using only release-visible UI and ADB facts."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Iterable


class AcceptanceError(RuntimeError):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(4 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_bounds(value: str) -> tuple[int, int, int, int] | None:
    match = re.fullmatch(r"\[(\d+),(\d+)]\[(\d+),(\d+)]", value or "")
    if match is None:
        return None
    bounds = tuple(int(part) for part in match.groups())
    if bounds[2] <= bounds[0] or bounds[3] <= bounds[1]:
        return None
    return bounds  # type: ignore[return-value]


def ui_nodes(xml: str) -> list[dict[str, str]]:
    try:
        root = ET.fromstring(xml)
    except ET.ParseError as error:
        raise AcceptanceError("uiautomator returned malformed XML") from error
    return [dict(node.attrib) for node in root.iter("node")]


def find_node(
    xml: str,
    *,
    texts: Iterable[str] = (),
    descriptions: Iterable[str] = (),
    class_suffix: str | None = None,
    contains: bool = False,
) -> dict[str, str] | None:
    expected_texts = tuple(texts)
    expected_descriptions = tuple(descriptions)
    for node in ui_nodes(xml):
        if class_suffix and not node.get("class", "").endswith(class_suffix):
            continue
        values = []
        if expected_texts:
            values.append((node.get("text", ""), expected_texts))
        if expected_descriptions:
            values.append((node.get("content-desc", ""), expected_descriptions))
        if not values:
            return node
        for actual, expected in values:
            if any((needle in actual) if contains else (needle == actual) for needle in expected):
                return node
    return None


def text_present(xml: str, needles: Iterable[str]) -> bool:
    values = tuple(needle.casefold() for needle in needles)
    return any(
        any(needle in (node.get("text", "") + " " + node.get("content-desc", "")).casefold()
            for needle in values)
        for node in ui_nodes(xml)
    )


def resolve_serial(devices_output: str, requested: str | None) -> str:
    ready = []
    for line in devices_output.splitlines()[1:]:
        columns = line.split()
        if len(columns) >= 2 and columns[1] == "device":
            ready.append(columns[0])
    if requested:
        if requested not in ready:
            raise AcceptanceError(f"ADB device is not ready: {requested}")
        return requested
    if len(ready) != 1:
        raise AcceptanceError("Specify --serial unless exactly one ADB device is ready")
    return ready[0]


def remote_apk_path(pm_output: str) -> str:
    paths = [line.removeprefix("package:").strip() for line in pm_output.splitlines()
             if line.startswith("package:")]
    if len(paths) != 1 or not paths[0].startswith("/data/app/"):
        raise AcceptanceError("Installed package does not have one canonical base APK")
    return paths[0]


class Adb:
    def __init__(self, executable: str, serial: str):
        self.executable = executable
        self.serial = serial

    def run(
        self,
        *arguments: str,
        timeout: float = 60,
        check: bool = True,
    ) -> subprocess.CompletedProcess[str]:
        result = subprocess.run(
            [self.executable, "-s", self.serial, *arguments],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=timeout,
            check=False,
        )
        if check and result.returncode != 0:
            message = (result.stderr or result.stdout).strip()
            raise AcceptanceError(f"adb {' '.join(arguments[:2])} failed: {message[:500]}")
        return result

    def shell(self, *arguments: str, timeout: float = 60) -> str:
        return self.run("shell", *arguments, timeout=timeout).stdout.replace("\r", "")

    def dump_ui(self) -> str:
        remote = "/sdcard/pideck-release-acceptance.xml"
        self.shell("uiautomator", "dump", remote, timeout=20)
        xml = self.run("exec-out", "cat", remote, timeout=20).stdout
        if "<hierarchy" not in xml:
            raise AcceptanceError("uiautomator hierarchy is unavailable")
        return xml[xml.index("<hierarchy"):]

    def tap(self, node: dict[str, str]) -> None:
        bounds = parse_bounds(node.get("bounds", ""))
        if bounds is None:
            raise AcceptanceError("UI target has no usable bounds")
        self.shell("input", "tap", str((bounds[0] + bounds[2]) // 2),
                   str((bounds[1] + bounds[3]) // 2))

    def wait_ui(
        self,
        predicate: Callable[[str], bool],
        timeout: float,
        label: str,
        interval: float = 1.0,
    ) -> str:
        deadline = time.monotonic() + timeout
        last_error = ""
        while time.monotonic() < deadline:
            try:
                xml = self.dump_ui()
                if predicate(xml):
                    return xml
            except (AcceptanceError, subprocess.TimeoutExpired) as error:
                last_error = str(error)
            time.sleep(interval)
        suffix = f" ({last_error})" if last_error else ""
        raise AcceptanceError(f"Timed out waiting for {label}{suffix}")


def android_tool(name: str) -> str | None:
    direct = shutil.which(name)
    if direct:
        return direct
    roots = [os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME")]
    roots.extend(["/home/che/Android/Sdk", str(Path.home() / "Android" / "Sdk")])
    candidates: list[Path] = []
    for raw_root in roots:
        if not raw_root:
            continue
        directory = Path(raw_root) / "build-tools"
        if directory.is_dir():
            candidates.extend(directory.glob(f"*/{name}"))
    return str(sorted(candidates)[-1]) if candidates else None


def apk_metadata(apk: Path) -> dict[str, object]:
    result: dict[str, object] = {"sha256": sha256_file(apk), "bytes": apk.stat().st_size}
    aapt = android_tool("aapt")
    if aapt:
        output = subprocess.run(
            [aapt, "dump", "badging", str(apk)],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=30,
            check=False,
        ).stdout
        package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", output)
        if package:
            result.update({
                "applicationId": package.group(1),
                "versionCode": int(package.group(2)),
                "versionName": package.group(3),
            })
    signer = android_tool("apksigner")
    if signer:
        output = subprocess.run(
            [signer, "verify", "--print-certs", str(apk)],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=30,
            check=False,
        )
        if output.returncode != 0:
            raise AcceptanceError("APK signature verification failed")
        match = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)", output.stdout)
        if match:
            result["signerSha256"] = match.group(1).lower()
    return result


def click_named(adb: Adb, xml: str, names: Iterable[str]) -> None:
    node = find_node(xml, texts=names) or find_node(xml, descriptions=names)
    if node is None:
        raise AcceptanceError(f"UI control is not visible: {', '.join(names)}")
    adb.tap(node)


def submit_prompt(adb: Adb, prompt: str) -> None:
    xml = adb.dump_ui()
    editor = find_node(xml, class_suffix="EditText")
    if editor is None:
        raise AcceptanceError("Prompt editor is not visible")
    adb.tap(editor)
    encoded = prompt.replace("%", "%25").replace(" ", "%s")
    adb.shell("input", "text", encoded, timeout=30)
    xml = adb.dump_ui()
    send = find_node(
        xml,
        descriptions=("Отправить сообщение", "Send message"),
    )
    if send is None:
        raise AcceptanceError("Send control is not enabled after text input")
    adb.tap(send)


def scroll_until(adb: Adb, labels: Iterable[str], attempts: int = 8) -> str:
    for _ in range(attempts):
        xml = adb.dump_ui()
        if text_present(xml, labels):
            return xml
        adb.shell("input", "swipe", "540", "1750", "540", "650", "350")
        time.sleep(0.4)
    raise AcceptanceError(f"Could not reveal UI row: {', '.join(labels)}")


def wait_ready_with_safe_bootstrap(adb: Adb, timeout: float) -> str:
    """Complete only idempotent upgrade/start actions; never chooses or downloads a model."""
    deadline = time.monotonic() + timeout
    clicked: set[str] = set()
    while time.monotonic() < deadline:
        xml = adb.dump_ui()
        if text_present(xml, ("Готово отвечать", "Ready ·")):
            return xml
        for label in ("INSTALL CORE", "IGNITE LLM", "START BRIDGE"):
            if label in clicked or not text_present(xml, (label,)):
                continue
            print(f"[acceptance] safe boot action: {label}", file=sys.stderr, flush=True)
            click_named(adb, xml, (label,))
            clicked.add(label)
            break
        time.sleep(1.0)
    raise AcceptanceError("Timed out waiting for READY UI after safe bootstrap")


def safe_process_facts(ps_output: str) -> list[dict[str, object]]:
    facts = []
    for line in ps_output.splitlines()[1:]:
        columns = line.split(None, 1)
        if len(columns) != 2:
            continue
        pid, name = columns
        if pid.isdigit() and (
            "pideck" in name.lower() or "llama" in name.lower() or name == "com.termux"
        ):
            facts.append({"pid": int(pid), "name": name[:128]})
    return facts[:32]


def memory_facts(output: str) -> dict[str, int]:
    result: dict[str, int] = {}
    for key, pattern in {
        "totalPssKb": r"TOTAL PSS:\s*(\d+)",
        "totalRssKb": r"TOTAL RSS:\s*(\d+)",
    }.items():
        match = re.search(pattern, output)
        if match:
            result[key] = int(match.group(1))
    return result


def run_acceptance(arguments: argparse.Namespace) -> dict[str, object]:
    apk = arguments.apk.resolve()
    if not apk.is_file():
        raise AcceptanceError(f"APK does not exist: {apk}")
    adb_executable = shutil.which(arguments.adb)
    if adb_executable is None:
        raise AcceptanceError(f"ADB executable not found: {arguments.adb}")
    devices = subprocess.run(
        [adb_executable, "devices", "-l"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=20, check=True,
    ).stdout
    serial = resolve_serial(devices, arguments.serial)
    adb = Adb(adb_executable, serial)
    local = apk_metadata(apk)
    report: dict[str, object] = {
        "schemaVersion": 1,
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "status": "RUNNING",
        "apk": local,
        "device": {
            "serial": serial,
            "manufacturer": adb.shell("getprop", "ro.product.manufacturer").strip(),
            "model": adb.shell("getprop", "ro.product.model").strip(),
            "sdk": int(adb.shell("getprop", "ro.build.version.sdk").strip()),
        },
        "checks": {},
    }
    checks = report["checks"]
    assert isinstance(checks, dict)

    if not arguments.skip_install:
        print("[acceptance] installing exact APK with data preservation", file=sys.stderr, flush=True)
        installed = adb.run("install", "-r", str(apk), timeout=300)
        if "Success" not in installed.stdout:
            raise AcceptanceError("adb install -r did not report Success")
        checks["installPreservedData"] = True

    package = arguments.package
    path = remote_apk_path(adb.shell("pm", "path", package))
    remote_hash_output = adb.shell("sha256sum", path, timeout=120).strip().split()
    if not remote_hash_output or not re.fullmatch(r"[0-9a-f]{64}", remote_hash_output[0]):
        raise AcceptanceError("Could not hash installed base APK")
    remote_hash = remote_hash_output[0]
    checks["installedApkSha256"] = remote_hash
    checks["exactApkBytes"] = remote_hash == local["sha256"]
    if remote_hash != local["sha256"]:
        raise AcceptanceError("Installed APK bytes differ from the requested artifact")
    package_dump = adb.shell("dumpsys", "package", package, timeout=60)
    installed_version_name = re.search(r"versionName=([^\s]+)", package_dump)
    installed_version_code = re.search(r"versionCode=(\d+)", package_dump)
    report["installedPackage"] = {
        "versionName": installed_version_name.group(1) if installed_version_name else None,
        "versionCode": int(installed_version_code.group(1)) if installed_version_code else None,
    }

    print("[acceptance] cold-launch sampling boot UI", file=sys.stderr, flush=True)
    adb.run("logcat", "-c", timeout=30, check=False)
    adb.shell("am", "force-stop", package)
    adb.shell("am", "start", "-W", "-n", f"{package}/.MainActivity", timeout=60)
    false_link_seen = False
    samples = 0
    sample_deadline = time.monotonic() + arguments.boot_sample_seconds
    while time.monotonic() < sample_deadline:
        xml = adb.dump_ui()
        samples += 1
        false_link_seen = false_link_seen or text_present(
            xml, ("СВЯЖИТЕ TERMUX С ДЕКОЙ", "LINK TERMUX TO THE DECK")
        )
        if text_present(xml, ("Готово отвечать", "Ready ·")):
            break
        time.sleep(0.4)
    checks["bootUiSamples"] = samples
    checks["falseLinkCardSeen"] = false_link_seen
    if arguments.expect_linked and false_link_seen:
        raise AcceptanceError("Linked upgrade flashed the LINK TERMUX repair card")

    ready_xml = wait_ready_with_safe_bootstrap(adb, arguments.ready_timeout)
    checks["readyUi"] = True
    print("[acceptance] exact-answer turn", file=sys.stderr, flush=True)
    submit_prompt(
        adb,
        "Reply only with PIDECK underscore OK replacing the word underscore with the character",
    )
    adb.wait_ui(lambda value: text_present(value, ("PIDECK_OK",)),
                arguments.turn_timeout, "PIDECK_OK answer")
    checks["promptAnswer"] = "PIDECK_OK"

    print("[acceptance] real shell-tool turn", file=sys.stderr, flush=True)
    submit_prompt(adb, "Use the bash tool to run uname -s and answer with the exact output")
    approval_xml = adb.wait_ui(
        lambda value: text_present(value, ("Разрешить один раз", "Allow once")),
        arguments.turn_timeout,
        "one-time Android approval",
    )
    click_named(adb, approval_xml, ("Разрешить один раз", "Allow once"))
    adb.wait_ui(lambda value: text_present(value, ("Linux",)),
                arguments.turn_timeout, "uname -s result")
    checks["toolResult"] = "Linux"
    checks["oneTimeApproval"] = True

    adb.shell("input", "keyevent", "KEYCODE_BACK")
    core_xml = adb.dump_ui()
    click_named(adb, core_xml, ("ЯДРО", "CORE"))
    core_xml = adb.wait_ui(
        lambda value: text_present(value, ("CONFIRM CHANGES",)),
        20,
        "CONFIRM CHANGES profile",
    )
    checks["confirmChangesDefault"] = True
    diagnostics_xml = scroll_until(
        adb, ("Диагностика операций", "Operation diagnostics")
    )
    click_named(adb, diagnostics_xml, ("Диагностика операций", "Operation diagnostics"))
    operations_xml = adb.wait_ui(
        lambda value: text_present(value, ("AGENT_TURN · COMPLETED",)),
        20,
        "release operation diagnostics",
    )
    operation_text = "\n".join(node.get("text", "") for node in ui_nodes(operations_xml))
    checks["completedAgentTurnsVisible"] = operation_text.count("AGENT_TURN · COMPLETED")
    if checks["completedAgentTurnsVisible"] < 2:
        raise AcceptanceError("Release diagnostics did not expose both completed turns")
    adb.shell("input", "keyevent", "KEYCODE_BACK")

    print("[acceptance] background/resume lifecycle", file=sys.stderr, flush=True)
    pid_before = adb.shell("pidof", package).strip()
    adb.shell("input", "keyevent", "KEYCODE_HOME")
    time.sleep(arguments.background_seconds)
    pid_background = adb.shell("pidof", package).strip()
    adb.shell("am", "start", "-W", "-n", f"{package}/.MainActivity", timeout=60)
    adb.wait_ui(lambda value: text_present(value, ("Готово отвечать", "Ready ·")),
                arguments.ready_timeout, "READY after background")
    checks["backgroundProcessSurvived"] = bool(pid_before and pid_background)
    checks["readyAfterBackground"] = True

    if arguments.screen_cycle:
        adb.shell("input", "keyevent", "KEYCODE_SLEEP")
        time.sleep(arguments.background_seconds)
        checks["pidWhileScreenOff"] = bool(adb.shell("pidof", package).strip())
        adb.shell("input", "keyevent", "KEYCODE_WAKEUP")
        adb.shell("wm", "dismiss-keyguard", timeout=20)
        adb.shell("am", "start", "-W", "-n", f"{package}/.MainActivity", timeout=60)
        adb.wait_ui(lambda value: text_present(value, ("Готово отвечать", "Ready ·")),
                    arguments.ready_timeout, "READY after screen cycle")
        checks["readyAfterScreenCycle"] = True

    window = adb.shell("dumpsys", "window", "windows")
    checks["topActivity"] = package in window and "MainActivity" in window
    report["processes"] = safe_process_facts(adb.shell("ps", "-A", "-o", "PID,NAME"))
    report["memory"] = memory_facts(adb.shell("dumpsys", "meminfo", package, timeout=90))
    logs = adb.run("logcat", "-d", "-t", "1200", timeout=60, check=False).stdout
    checks["fatalExceptionCount"] = sum(
        1 for line in logs.splitlines()
        if package in line and ("FATAL EXCEPTION" in line or "Fatal signal" in line)
    )
    checks["lowMemoryKillCount"] = sum(
        1 for line in logs.splitlines()
        if package in line and ("lowmemorykiller" in line.lower() or "lmkd" in line.lower())
    )
    if checks["fatalExceptionCount"] or checks["lowMemoryKillCount"]:
        raise AcceptanceError("Crash or LMK evidence was observed")
    report["status"] = "PASS"
    report["finishedAt"] = datetime.now(timezone.utc).isoformat()
    return report


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--serial")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--package", default="dev.pideck.app")
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--skip-install", action="store_true")
    parser.add_argument("--no-expect-linked", dest="expect_linked", action="store_false")
    parser.set_defaults(expect_linked=True)
    parser.add_argument("--screen-cycle", action="store_true")
    parser.add_argument("--boot-sample-seconds", type=float, default=15.0)
    parser.add_argument("--background-seconds", type=float, default=8.0)
    parser.add_argument("--ready-timeout", type=float, default=360.0)
    parser.add_argument("--turn-timeout", type=float, default=900.0)
    return parser.parse_args()


def main() -> int:
    arguments = parse_args()
    arguments.report.parent.mkdir(parents=True, exist_ok=True)
    try:
        report = run_acceptance(arguments)
    except Exception as error:
        report = {
            "schemaVersion": 1,
            "status": "FAIL",
            "finishedAt": datetime.now(timezone.utc).isoformat(),
            "errorType": type(error).__name__,
            "error": str(error)[:1000],
        }
        arguments.report.write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        print(f"[acceptance] FAIL: {error}", file=sys.stderr)
        return 1
    arguments.report.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"[acceptance] PASS: {arguments.report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
