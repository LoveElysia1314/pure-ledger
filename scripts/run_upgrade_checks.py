"""Prove a debug-to-production upgrade on the named synthetic AVD; never uninstall or clear data."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import subprocess

from release import ROOT, ReleaseTools, read_json, sha256, signature_for_sdk

PACKAGE = 'io.github.localledger.app'
COMPONENT = PACKAGE + '/' + PACKAGE + '.accessibility.LedgerAccessibilityService'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', required=True, type=Path)
    parser.add_argument('--baseline-apk', required=True, type=Path)
    parser.add_argument('--release-apk', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('Only an explicitly selected disposable emulator is supported')
    tools = ReleaseTools()
    identity = tools.validate_material()
    release = tools.verify(args.release_apk.resolve())
    folder = args.output.resolve()
    folder.mkdir(parents=True, exist_ok=True)

    def adb(*parts, timeout=60, check=True):
        result = subprocess.run([str(args.adb), '-s', args.serial, *map(str, parts)], capture_output=True, timeout=timeout)
        text = (result.stdout + result.stderr).decode('utf-8', errors='replace').strip()
        if check and result.returncode:
            raise RuntimeError(text)
        return result.returncode, text

    def shell(*parts):
        return adb('shell', *parts)[1]

    avd = adb('emu', 'avd', 'name')[1].splitlines()[0]
    if avd != 'LocalLedger_API35' or shell('getprop', 'ro.kernel.qemu') != '1' or shell('getprop', 'ro.build.version.sdk') != '35':
        raise ValueError('Use the disposable LocalLedger_API35 / API35 AVD. No APK was installed.')
    baseline_path = args.baseline_apk.resolve()
    baseline_signature = tools.apk_signer('verify', '--print-certs', baseline_path)
    baseline_signer = signature_for_sdk(baseline_signature, 35)
    if baseline_signer not in {identity['legacy_sha256'], identity['release_sha256']}:
        raise ValueError('Baseline is not in the retained signing identity')
    badging = tools.run([tools.aapt, 'dump', 'badging', baseline_path])
    metadata = re.search(r"package: name='([^']+)' versionCode='(\d+)'", badging)
    if not metadata or metadata.group(1) != PACKAGE or int(metadata.group(2)) >= release['version_code']:
        raise ValueError('Invalid baseline package/version')
    baseline_code = int(metadata.group(2))
    installed = shell('dumpsys', 'package', PACKAGE)
    installed_version = re.search(r'\bversionCode=(\d+)', installed)
    if installed_version and int(installed_version.group(1)) > baseline_code:
        raise ValueError('A newer app is installed on this AVD. No downgrade or data removal will be attempted.')

    test_source = ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    legacy_test = folder / 'upgrade-probe-legacy.apk'
    release_test = folder / 'upgrade-probe-release.apk'
    legacy_only = folder / 'legacy-only-rejected.apk'
    tools.sign(test_source, legacy_test, rotate=False)
    tools.sign(test_source, release_test)
    tools.sign(ROOT / 'app/build/outputs/apk/release/app-release-unsigned.apk', legacy_only, rotate=False)

    def permission_state():
        package_dump = shell('dumpsys', 'package', PACKAGE)
        op = re.search(r'SYSTEM_ALERT_WINDOW: (\w+)', shell('appops', 'get', PACKAGE, 'SYSTEM_ALERT_WINDOW'))
        return dict(accessibility_services=shell('settings', 'get', 'secure', 'enabled_accessibility_services'),
                    accessibility_enabled=shell('settings', 'get', 'secure', 'accessibility_enabled'),
                    overlay=op.group(1) if op else 'default',
                    notifications='true' in re.findall(r'android.permission.POST_NOTIFICATIONS:\s+granted=(true|false)', package_dump))

    def probe(phase, code, signer, log_name):
        _, text = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', PACKAGE + '.NativeUpgradeDataTest',
                      '-e', 'upgrade_phase', phase, '-e', 'expected_version_code', code,
                      '-e', 'expected_signer', signer, '-e', 'legacy_signer', identity['legacy_sha256'],
                      PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner', timeout=90)
        (folder / log_name).write_text(text, encoding='utf-8')
        if 'OK (1 test)' not in text or 'FAILURES!!!' in text or 'Process crashed' in text:
            raise RuntimeError('Upgrade data probe failed; see ' + str(folder / log_name))
        match = re.search(r'INSTRUMENTATION_STATUS: upgrade_result=(\{[^\r\n]+\})', text)
        if not match:
            raise RuntimeError('Missing structured upgrade evidence')
        return json.loads(match.group(1))

    original = permission_state()
    (folder / 'original-permissions.json').write_text(json.dumps(original, indent=2), encoding='utf-8')
    try:
        adb('install', '--no-incremental', '-r', baseline_path)
        adb('install', '--no-incremental', '-r', '-t', legacy_test if baseline_signer == identity['legacy_sha256'] else release_test)
        shell('appops', 'set', PACKAGE, 'SYSTEM_ALERT_WINDOW', 'allow')
        shell('pm', 'grant', PACKAGE, 'android.permission.POST_NOTIFICATIONS')
        services = original['accessibility_services']
        values = [] if services in ('', 'null') else services.split(':')
        if COMPONENT not in values:
            values.append(COMPONENT)
        shell('settings', 'put', 'secure', 'enabled_accessibility_services', ':'.join(values))
        shell('settings', 'put', 'secure', 'accessibility_enabled', '1')
        before_permissions = permission_state()
        before = probe('seed', baseline_code, baseline_signer, 'before-upgrade.log')
        adb('install', '--no-incremental', '-r', args.release_apk.resolve())
        adb('install', '--no-incremental', '-r', '-t', release_test)
        shell('am', 'start', '-W', '-n', PACKAGE + '/.MainActivity')
        after_permissions = permission_state()
        after = probe('verify', release['version_code'], identity['release_sha256'], 'after-upgrade.log')
        if before_permissions != after_permissions or before['state_sha256'] != after['state_sha256'] or before['uid'] != after['uid']:
            raise ValueError('Upgrade failed to preserve application data or permissions')
        rejected_code, rejected = adb('install', '--no-incremental', '-r', legacy_only, check=False)
        (folder / 'legacy-rollback-rejected.log').write_text(rejected, encoding='utf-8')
        if rejected_code == 0 or 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' not in rejected:
            raise ValueError('A legacy-only APK unexpectedly replaced the rotated production signer')
        final = probe('verify', release['version_code'], identity['release_sha256'], 'after-rejection.log')
        result = dict(serial=args.serial, avd=avd, api=35, phone_tested=False, synthetic_data=True,
                      baseline_apk_sha256=sha256(baseline_path), release_apk_sha256=release['sha256'],
                      before=before, after=after, after_rejected_legacy_apk=final,
                      permissions_before=before_permissions, permissions_after=after_permissions,
                      checks=dict(direct_install=True, full_database=True, all_preferences=True, retained_file=True,
                                  same_uid=True, permissions_preserved=True, release_not_debuggable=True,
                                  rotated_signer=True, legacy_only_rejected=True, no_uninstall_or_clear=True))
        (folder / 'summary.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(result, indent=2))
    finally:
        # Restore only the synthetic AVD's permissions; never restore by removing application data.
        for key, value in [('enabled_accessibility_services', original['accessibility_services']),
                           ('accessibility_enabled', original['accessibility_enabled'])]:
            if value in ('', 'null'):
                shell('settings', 'delete', 'secure', key)
            else:
                if not re.fullmatch(r'[A-Za-z0-9_.$/:]+', value):
                    raise ValueError('Unexpected original accessibility setting')
                shell('settings', 'put', 'secure', key, value)
        shell('appops', 'set', PACKAGE, 'SYSTEM_ALERT_WINDOW', original['overlay'])
        if not original['notifications']:
            shell('pm', 'revoke', PACKAGE, 'android.permission.POST_NOTIFICATIONS')
        restored = permission_state()
        (folder / 'restored-permissions.json').write_text(json.dumps(restored, indent=2), encoding='utf-8')
        if restored != original:
            raise RuntimeError('Original AVD permissions were not restored')


if __name__ == '__main__':
    main()
