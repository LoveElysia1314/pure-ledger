"""Run synthetic Android checks only on the named disposable AVD, never a phone."""
from __future__ import annotations
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

PACKAGE = "io.github.localledger.app"
ROOT = Path(__file__).resolve().parents[1]
GROUPS = {
    "update": ("NativeUpdateUiTest", 1),
    "ui": ("NativeLedgerSmokeTest,NativeLayoutReviewTest,NativeCatalogInteractionTest,NativeBookMergeTest,NativeCatalogBatchTest,NativeEnglishLocaleTest,NativeBrandIdentityTest", 24),
    "capacity": ("NativeLedgerCapacityTest", 1),
    "runtime": ("NativeRuntimeTest", 1),
}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit emulator serial; no device auto-selection")
    parser.add_argument("--adb", type=Path, help="Existing ADB client; avoids changing the host server")
    parser.add_argument("--group", choices=["all", *GROUPS], default="all")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    adb = args.adb or (Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb") if sdk else None)
    if adb is None or not adb.is_file():
        parser.error("Supply --adb or an Android SDK containing platform-tools")
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        parser.error("Only an explicitly selected emulator is supported")
    output = (args.output or ROOT / "build" / "native-checks" / dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d-%H%M%S")).resolve()
    output.mkdir(parents=True, exist_ok=True)

    def device(*parts: object, timeout: int = 30, binary: bool = False):
        result = subprocess.run([str(adb), "-s", args.serial, *map(str, parts)], capture_output=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(result.stderr.decode("utf-8", "replace"))
        return result.stdout if binary else result.stdout.decode("utf-8", "replace").strip()

    def shell(*parts: object):
        return device("shell", *parts)

    avd = device("emu", "avd", "name").splitlines()[0]
    if avd != "LocalLedger_API35" or shell("getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("Use the disposable LocalLedger_API35 AVD. No APKs were installed.")
    if shell("getprop", "ro.build.version.sdk") != "35":
        raise RuntimeError("The reference checks require API 35")
    density = shell("wm", "density")
    density_match = re.search(r"Override density: ([0-9]+)", density) or re.search(r"Physical density: ([0-9]+)", density)
    if density_match is None or density_match.group(1) != "480":
        raise RuntimeError("The reference AVD requires 480 dpi")
    if not args.skip_build:
        wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
        with (output / "build.log").open("w", encoding="utf-8") as log:
            subprocess.run([str(wrapper), ":app:assembleDebug", ":app:assembleDebugAndroidTest", "--console=plain"],
                           cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
    apk_hashes = {}
    for test, relative in [(False, "debug/app-debug.apk"), (True, "androidTest/debug/app-debug-androidTest.apk")]:
        apk = ROOT / "app/build/outputs/apk" / relative
        if not apk.is_file():
            raise FileNotFoundError(apk)
        apk_hashes["test" if test else "app"] = hashlib.sha256(apk.read_bytes()).hexdigest()
        print(device("install", "--no-incremental", "-r", *(["-t"] if test else []), apk), flush=True)

    def collect(group: str):
        folders = {"update": ["update-review"], "ui": ["native-ui-screens", "layout-review", "catalog-review", "book-merge-review", "batch-review", "english-review", "brand-review"], "runtime": ["runtime-review"], "capacity": []}[group]
        files = {"runtime": ["runtime-result.json"], "capacity": ["capacity-benchmark.json"]}.get(group, [])
        for folder in folders:
            listing = shell("run-as", PACKAGE, "find", "files/" + folder, "-type", "f")
            files.extend(line.removeprefix("files/") for line in listing.splitlines() if line.startswith("files/"))
        for name in files:
            relative = Path(name)
            if relative.is_absolute() or ".." in relative.parts:
                raise ValueError("Unexpected AVD file path")
            target = output / group / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(device("exec-out", "run-as", PACKAGE, "cat", "files/" + name, binary=True))

    def runtime_state():
        return {
            "enabled_accessibility_services": shell("settings", "get", "secure", "enabled_accessibility_services"),
            "accessibility_enabled": shell("settings", "get", "secure", "accessibility_enabled"),
            "overlay_mode": next(iter(re.findall(r"SYSTEM_ALERT_WINDOW: (\w+)", shell("appops", "get", PACKAGE, "SYSTEM_ALERT_WINDOW"))), "default"),
            "notifications_granted": "true" in re.findall(r"android.permission.POST_NOTIFICATIONS:\s+granted=(true|false)", shell("dumpsys", "package", PACKAGE)),
        }

    total = 0
    for group in GROUPS if args.group == "all" else [args.group]:
        before = runtime_state() if group == "runtime" else None
        display_keys = ["font_scale", "user_rotation", "accelerometer_rotation"]
        display_before = {key: shell("settings", "get", "system", key) for key in display_keys} if group in {"ui", "update"} else None
        size_before = shell("wm", "size") if display_before is not None else None
        density_before = shell("wm", "density") if display_before is not None else None
        locale_before = shell("cmd", "locale", "get-app-locales", PACKAGE) if display_before is not None else None
        night_before = shell("cmd", "uimode", "night").removeprefix("Night mode: ").strip() if display_before is not None else None
        if night_before is not None and night_before not in ("yes", "no", "auto", "custom"):
            raise RuntimeError("Cannot determine the AVD's initial night mode")
        try:
            if group == "ui":
                shell("wm", "user-rotation", "lock", "0")
            if before:
                shell("am", "force-stop", PACKAGE)
                shell("appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", "allow")
                if not before["notifications_granted"]:
                    shell("pm", "grant", "--user", "0", PACKAGE, "android.permission.POST_NOTIFICATIONS")
            names, expected = GROUPS[group]
            classes = ",".join("io.github.localledger.app." + name for name in names.split(","))
            try:
                result = device("shell", "am", "instrument", "-w", "-r", "-e", "class", classes,
                                PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner", timeout=240)
            except subprocess.TimeoutExpired as error:
                (output / (group + ".log")).write_bytes(error.stdout or b"")
                shell("am", "force-stop", PACKAGE)
                raise RuntimeError(f"{group} timed out; stopped only the test target on the verified AVD") from error
            (output / (group + ".log")).write_text(result, encoding="utf-8")
            print(result[-1800:], flush=True)
            passed = bool(re.search(r"OK \(" + str(expected) + r" tests?\)", result)) and "FAILURES!!!" not in result and "Process crashed" not in result
            if not passed:
                try:
                    collect(group)
                except (RuntimeError, subprocess.TimeoutExpired) as error:
                    print(f"Could not collect all failure frames: {error}", file=sys.stderr)
                raise RuntimeError(f"{group} failed; see {output / (group + '.log')}")
            collect(group)
            total += expected
        finally:
            if display_before is not None:
                size_match = re.search(r"Override size: ([0-9]+x[0-9]+)", size_before or "")
                shell("wm", "size", size_match.group(1) if size_match else "reset")
                density_override = re.search(r"Override density: ([0-9]+)", density_before or "")
                shell("wm", "density", density_override.group(1) if density_override else "reset")
                for key, value in display_before.items():
                    if value in ("", "null"):
                        shell("settings", "delete", "system", key)
                    else:
                        if not re.fullmatch(r"[0-9.-]+", value):
                            raise ValueError("Unexpected AVD display setting")
                        shell("settings", "put", "system", key, value)
                shell("cmd", "uimode", "night", night_before)
                locale_match = re.search(r"\[(.*?)\]", locale_before or "")
                if locale_match is None:
                    raise RuntimeError("Could not read reference app locales")
                locale_tags = locale_match.group(1)
                if locale_tags:
                    if not re.fullmatch(r"[A-Za-z0-9,-]+", locale_tags):
                        raise ValueError("Unexpected app locales")
                    shell("cmd", "locale", "set-app-locales", PACKAGE, "--locales", locale_tags)
                else:
                    shell("cmd", "locale", "set-app-locales", PACKAGE)
                locale_after = shell("cmd", "locale", "get-app-locales", PACKAGE)
                rotation = display_before["user_rotation"]
                shell("wm", "user-rotation", "lock", rotation if rotation in ("0", "1", "2", "3") else "0")
                if display_before["accelerometer_rotation"] == "1":
                    shell("wm", "user-rotation", "free")
                display_after = {key: shell("settings", "get", "system", key) for key in display_keys}
                night_after = shell("cmd", "uimode", "night").removeprefix("Night mode: ").strip()
                (output / "display-state.json").write_text(json.dumps({"before": display_before, "after": display_after,
                    "night_before": night_before, "night_after": night_after, "app_locales_before": locale_before, "app_locales_after": locale_after, "size_before": size_before,
                    "size_after": shell("wm", "size"), "density_before": density_before, "density_after": shell("wm", "density")}, indent=2), encoding="utf-8")
                if display_before != display_after or night_before != night_after or locale_before != locale_after or size_before != shell("wm", "size") or density_before != shell("wm", "density"):
                    raise RuntimeError("AVD display settings did not restore")
            if before:
                shell("cmd", "statusbar", "collapse")
                for key in ["enabled_accessibility_services", "accessibility_enabled"]:
                    value = before[key]
                    if value in ("", "null"):
                        shell("settings", "delete", "secure", key)
                    else:
                        if not re.fullmatch(r"[A-Za-z0-9_.$/:]+", str(value)):
                            raise ValueError("Unexpected secure setting value")
                        shell("settings", "put", "secure", key, value)
                shell("appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", before["overlay_mode"])
                if not before["notifications_granted"]:
                    # Revocation can terminate the target: restore after instrumentation and file reads.
                    shell("pm", "revoke", "--user", "0", PACKAGE, "android.permission.POST_NOTIFICATIONS")
                after = runtime_state()
                (output / "runtime-state.json").write_text(json.dumps({"before": before, "after": after}, indent=2), encoding="utf-8")
                if before != after:
                    raise RuntimeError("AVD permissions did not restore; inspect runtime-state.json")
    (output / "summary.json").write_text(json.dumps({"tests": total, "serial": args.serial, "avd": avd,
        "api": 35, "apk_sha256": apk_hashes, "phone_tested": False, "synthetic_data": True}, indent=2), encoding="utf-8")
    print(f"Passed {total} native checks; artifacts: {output}")


if __name__ == "__main__":
    main()
