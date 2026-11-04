"""Initialize private signing material, build and verify a rotated production APK.

Secrets stay in ignored .private/signing; subprocess arguments contain environment
variable names, never passwords. Public certificates and lineage are safe to commit.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import struct
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = ROOT / '.private/signing'
PUBLIC = ROOT / 'signing'
LEGACY_SHA256 = '1fab5639917a71e5490b0ab426bc182e2d10077503d2bfe44f471b5fafb60341'
ROTATION_SDK = 33


def signature_for_sdk(output: str, sdk: int) -> str:
    ranged = re.findall(r'^Signer \(minSdkVersion=(\d+), maxSdkVersion=(\d+)\) certificate SHA-256 digest: ([0-9a-f]{64})$', output, re.M)
    if ranged:
        matches = [digest for low, high, digest in ranged if int(low) <= sdk <= int(high)]
    else:
        matches = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$', output, re.M)
    if len(matches) != 1:
        raise ValueError('Expected exactly one applicable signing certificate')
    return matches[0]


def read_json(path: Path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def sha256(path: Path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_version_properties(properties):
    match = re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)', properties['versionName'])
    if match is None:
        raise ValueError('Invalid public version')
    major, minor, patch = map(int, match.groups())
    base = int(properties.get('versionCodeBase', '0'))
    code = int(properties['versionCode'])
    if base < 0 or code != base + major * 1_000_000 + minor * 10_000 + patch * 100 or not 0 < code <= 2_100_000_000:
        raise ValueError('Version code does not match the fixed installation-code base')
    return code


class ReleaseTools:
    def __init__(self):
        config_file = ROOT / '.private/developer.json'
        config = read_json(config_file) if config_file.exists() else {}
        java_home = os.environ.get('JAVA_HOME') or config.get('javaHome')
        android_home = os.environ.get('ANDROID_HOME') or config.get('androidHome')
        if not java_home or not android_home:
            raise ValueError('Set JAVA_HOME and ANDROID_HOME, or local developer configuration')
        self.java_home = Path(java_home)
        self.sdk = Path(android_home) / 'build-tools/36.0.0'
        suffix = '.exe' if os.name == 'nt' else ''
        self.java = self.java_home / ('bin/java' + suffix)
        self.keytool = self.java_home / ('bin/keytool' + suffix)
        self.signer = self.sdk / 'lib/apksigner.jar'
        self.aapt = self.sdk / ('aapt2' + suffix)
        self.zipalign = self.sdk / ('zipalign' + suffix)
        self.env = dict(os.environ, JAVA_HOME=str(self.java_home), ANDROID_HOME=str(android_home))
        if config.get('gradleUserHome') and not os.environ.get('GRADLE_USER_HOME'):
            self.env['GRADLE_USER_HOME'] = config['gradleUserHome']
        self.robolectric = config.get('robolectricDir')

    def run(self, arguments, *, env=None):
        result = subprocess.run(list(map(str, arguments)), cwd=ROOT, env=env or self.env,
                                capture_output=True, text=True, encoding='utf-8', errors='replace')
        if result.returncode:
            # Tool output contains no command-line credentials; never include the environment.
            raise RuntimeError(f'{Path(str(arguments[0])).name} failed ({result.returncode}):\n{result.stdout}\n{result.stderr}')
        return result.stdout + result.stderr

    def apk_signer(self, *arguments, env=None):
        return self.run([self.java, '-jar', self.signer, *arguments], env=env)

    def signing_environment(self):
        if not (PRIVATE / 'credentials.json').is_file():
            raise ValueError('Signing material is missing. Run release.py init first.')
        credentials = read_json(PRIVATE / 'credentials.json')
        return dict(self.env, PURE_LEDGER_RELEASE_PASSWORD=credentials['releasePassword'],
                    PURE_LEDGER_LEGACY_PASSWORD=credentials['legacyPassword'])

    def signer_options(self, legacy: bool):
        alias = 'androiddebugkey' if legacy else 'pure-ledger-release'
        file = PRIVATE / ('legacy-debug.keystore' if legacy else 'pure-ledger-release.p12')
        variable = 'PURE_LEDGER_LEGACY_PASSWORD' if legacy else 'PURE_LEDGER_RELEASE_PASSWORD'
        return ['--ks', file, '--ks-key-alias', alias, '--ks-pass', 'env:' + variable, '--key-pass', 'env:' + variable]

    def export_certificate(self, legacy: bool, env):
        variable = 'PURE_LEDGER_LEGACY_PASSWORD' if legacy else 'PURE_LEDGER_RELEASE_PASSWORD'
        path = PUBLIC / ('legacy-certificate.der' if legacy else 'release-certificate.der')
        alias = 'androiddebugkey' if legacy else 'pure-ledger-release'
        store = PRIVATE / ('legacy-debug.keystore' if legacy else 'pure-ledger-release.p12')
        self.run([self.keytool, '-exportcert', '-keystore', store, '-alias', alias,
                  '-storepass:env', variable, '-file', path], env=env)
        return sha256(path)

    def initialize(self, legacy_keystore: Path | None):
        if (PRIVATE / 'credentials.json').exists():
            self.validate_material()
            return {'initialized': True, 'reused_existing_key': True, **read_json(PUBLIC / 'certificates.json')}
        # Never silently regenerate a key or overwrite partial material.
        if PRIVATE.exists() and any(PRIVATE.iterdir()):
            raise ValueError('Partial signing directory exists; recover it before initialization')
        if PUBLIC.exists() and any(PUBLIC.iterdir()):
            raise ValueError('Public signing identity exists; restore its private material instead of creating a new key')
        legacy_keystore = legacy_keystore or Path.home() / '.android/debug.keystore'
        if not legacy_keystore.is_file():
            raise ValueError('Supply the original legacy debug keystore')
        PRIVATE.mkdir(parents=True, exist_ok=True)
        PUBLIC.mkdir(parents=True, exist_ok=True)
        if os.name == 'nt':
            # Restrict private material to the current Windows SID and Local System.
            identity = self.run(['whoami', '/user', '/fo', 'csv', '/nh'])
            sid = re.search(r'S-1-[0-9-]+', identity).group(0)
            self.run(['icacls', PRIVATE, '/inheritance:r', '/grant:r', f'*{sid}:(OI)(CI)F', '*S-1-5-18:(OI)(CI)F'])
        else:
            PRIVATE.chmod(0o700)
        shutil.copy2(legacy_keystore, PRIVATE / 'legacy-debug.keystore')
        credentials = {'legacyPassword': 'android', 'releasePassword': secrets.token_urlsafe(48)}
        credential_file = PRIVATE / 'credentials.json'
        credential_file.write_text(json.dumps(credentials, indent=2), encoding='utf-8')
        credential_file.chmod(0o600)
        env = self.signing_environment()
        legacy = self.export_certificate(True, env)
        if legacy != LEGACY_SHA256:
            raise ValueError('Legacy key does not match the previously distributed Pure Ledger APK')
        self.run([self.keytool, '-genkeypair', '-keystore', PRIVATE / 'pure-ledger-release.p12',
                  '-storetype', 'PKCS12', '-alias', 'pure-ledger-release', '-keyalg', 'RSA', '-keysize', '4096',
                  '-sigalg', 'SHA256withRSA', '-validity', '36500', '-dname', 'CN=Pure Ledger Release,O=Pure Ledger',
                  '-storepass:env', 'PURE_LEDGER_RELEASE_PASSWORD', '-keypass:env', 'PURE_LEDGER_RELEASE_PASSWORD'], env=env)
        release = self.export_certificate(False, env)
        self.apk_signer('rotate', '--out', PUBLIC / 'release.lineage', '--old-signer', *self.signer_options(True),
                        '--set-installed-data', 'true', '--set-shared-uid', 'false', '--set-permission', 'true',
                        '--set-rollback', 'false', '--set-auth', 'false', '--new-signer', *self.signer_options(False), env=env)
        identity = dict(package='io.github.localledger.app', rotation_min_sdk=ROTATION_SDK,
                        legacy_sha256=legacy, release_sha256=release, lineage_sha256=sha256(PUBLIC / 'release.lineage'),
                        legacy_capabilities=dict(installed_data=True, shared_uid=False, permission=True, rollback=False, auth=False))
        (PUBLIC / 'certificates.json').write_text(json.dumps(identity, indent=2) + '\n', encoding='utf-8')
        self.validate_material()
        # Password-protected PKCS12 copies are recoverable; recovery credentials are kept separately.
        backup = PRIVATE / 'backup'
        backup.mkdir(exist_ok=True)
        shutil.copy2(PRIVATE / 'pure-ledger-release.p12', backup / 'pure-ledger-release.p12')
        shutil.copy2(PRIVATE / 'legacy-debug.keystore', backup / 'legacy-debug.keystore')
        shutil.copy2(PUBLIC / 'release.lineage', backup / 'release.lineage')
        (backup / 'SHA256SUMS').write_text(''.join(f'{sha256(path)}  {path.name}\n' for path in sorted(backup.iterdir())), encoding='utf-8')
        (PRIVATE / 'recovery-credentials.txt').write_text(
            'Store this recovery note separately from the keystore backup.\n'
            'Alias: pure-ledger-release\nPassword: ' + credentials['releasePassword'] + '\n'
            'Legacy alias: androiddebugkey\nLegacy password: android\n', encoding='utf-8')
        return {'initialized': True, 'reused_existing_key': False, **identity}

    def validate_public_identity(self):
        identity = read_json(PUBLIC / 'certificates.json')
        if identity['legacy_sha256'] != LEGACY_SHA256 or identity['rotation_min_sdk'] != ROTATION_SDK:
            raise ValueError('Unexpected signing identity or rotation policy')
        for role in ['legacy', 'release']:
            if sha256(PUBLIC / f'{role}-certificate.der') != identity[f'{role}_sha256']:
                raise ValueError('Public certificate fingerprint mismatch')
        if sha256(PUBLIC / 'release.lineage') != identity['lineage_sha256']:
            raise ValueError('Public signing lineage hash mismatch')
        if identity.get('package') != 'io.github.localledger.app' or identity.get('legacy_capabilities') != dict(
                installed_data=True, shared_uid=False, permission=True, rollback=False, auth=False):
            raise ValueError('Unexpected legacy trust capabilities')
        return identity

    def validate_material(self):
        identity = self.validate_public_identity()
        env = self.signing_environment()
        for legacy in [True, False]:
            # Compare a fresh certificate export to the committed public certificate without overwriting it.
            variable = 'PURE_LEDGER_LEGACY_PASSWORD' if legacy else 'PURE_LEDGER_RELEASE_PASSWORD'
            store = PRIVATE / ('legacy-debug.keystore' if legacy else 'pure-ledger-release.p12')
            alias = 'androiddebugkey' if legacy else 'pure-ledger-release'
            output = self.run([self.keytool, '-exportcert', '-rfc', '-keystore', store, '-alias', alias,
                               '-storepass:env', variable], env=env)
            import base64
            pem = re.search(r'-----BEGIN CERTIFICATE-----\s*(.*?)\s*-----END CERTIFICATE-----', output, re.S).group(1)
            fingerprint = hashlib.sha256(base64.b64decode(pem)).hexdigest()
            if fingerprint != identity['legacy_sha256' if legacy else 'release_sha256']:
                raise ValueError('Private keystore and public certificate do not match')
        return identity

    def sign(self, source: Path, target: Path, *, rotate=True):
        self.validate_material()
        target.parent.mkdir(parents=True, exist_ok=True)
        aligned = target.with_name(target.stem + '-aligned.apk')
        if target.exists() or aligned.exists():
            raise ValueError('Output exists; choose a new artifact path instead of overwriting a signed APK')
        self.run([self.zipalign, '-P', '16', '-f', '4', source, aligned])
        options = self.signer_options(True)
        if rotate:
            options += ['--next-signer', *self.signer_options(False), '--lineage', PUBLIC / 'release.lineage',
                        '--rotation-min-sdk-version', str(ROTATION_SDK)]
        try:
            self.apk_signer('sign', '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true',
                            '--v3-signing-enabled', 'true', '--v4-signing-enabled', 'false',
                            *options, '--out', target, aligned, env=self.signing_environment())
        finally:
            aligned.unlink(missing_ok=True)

    def verify(self, apk: Path, *, require_release=True):
        identity = self.validate_public_identity()
        results = {}
        for name, low, high, expected in [('android_8_to_12L', 26, 32, identity['legacy_sha256']),
                                           ('android_13_plus', 33, 36, identity['release_sha256'])]:
            output = self.apk_signer('verify', '--verbose', '--print-certs', '--min-sdk-version', str(low),
                                     '--max-sdk-version', str(high), apk)
            if any(signature_for_sdk(output, sdk) != expected for sdk in range(low, high + 1)):
                raise ValueError(f'Unexpected signer for {name}')
            if low >= ROTATION_SDK and 'Verified using v3.1 scheme (APK Signature Scheme v3.1): true' not in output:
                raise ValueError('Missing v3.1 signing rotation proof')
            results[name] = expected
        self.run([self.zipalign, '-c', '-P', '16', '4', apk])
        lineage = json.loads(self.run([self.java, '--class-path', self.signer, ROOT / 'scripts/VerifySigningLineage.java',
                                      apk, PUBLIC / 'release.lineage']))
        badging = self.run([self.aapt, 'dump', 'badging', apk])
        match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
        if not match or match.group(1) != identity['package']:
            raise ValueError('Wrong application package')
        if require_release and 'application-debuggable' in badging:
            raise ValueError('A debuggable APK cannot be distributed as a production release')
        with zipfile.ZipFile(apk) as bundle:
            if bundle.testzip() is not None:
                raise ValueError('Corrupted APK')
            for name in ['GPL-3.0-only.txt', 'Apache-2.0.txt', 'Lucide.txt']:
                if bundle.read('assets/licenses/' + name) != (ROOT / 'app/src/main/assets/licenses' / name).read_bytes():
                    raise ValueError('Missing license asset')
            for name in bundle.namelist():
                data = bundle.read(name)
                if name.endswith('.dex') and any(token in data for token in [b'Llocalprobe/', b'Lcom/jarstones/', b'NativeUpgradeDataTest', b'NativeUpdateUiTest', b'ChineseTestApplication']):
                    raise ValueError('Test or legacy code in production APK')
                if name.endswith('.so') and any(abi in name for abi in ['arm64-v8a', 'x86_64']):
                    if data[:6] != b'\x7fELF\x02\x01':
                        raise ValueError('Unsupported native library format')
                    start = struct.unpack_from('<Q', data, 32)[0]
                    size, count = struct.unpack_from('<HH', data, 54)
                    for index in range(count):
                        header = start + index * size
                        if struct.unpack_from('<I', data, header)[0] == 1:
                            offset, address = struct.unpack_from('<QQ', data, header + 8)
                            if struct.unpack_from('<Q', data, header + 48)[0] < 16384 or (address - offset) % 16384:
                                raise ValueError('Native library is not 16KB aligned')
        permissions = self.run([self.aapt, 'dump', 'permissions', apk])
        if 'android.permission.INTERNET' not in permissions or 'android.permission.REQUEST_INSTALL_PACKAGES' in permissions:
            raise ValueError('Unexpected production network/installation permissions')
        return dict(apk=apk.relative_to(ROOT).as_posix(), bytes=apk.stat().st_size, sha256=sha256(apk),
                    package=match.group(1), version_code=int(match.group(2)), version=match.group(3),
                    debuggable='application-debuggable' in badging, signers=results,
                    lineage_sha256=identity['lineage_sha256'], rotation_min_sdk=ROTATION_SDK,
                    lineage_checks=lineage, public_release=False)

    def build(self, output: Path | None):
        self.validate_material()  # Missing/mismatched keys stop before a build; no debug-signing fallback.
        props = dict(re.findall(r'^(versionName|versionCode|versionCodeBase)=(.+)$', (ROOT / 'gradle.properties').read_text(), re.M))
        validate_version_properties(props)
        version = props['versionName']
        if not re.fullmatch(r'\d+\.\d+\.\d+', version):
            raise ValueError('Invalid version')
        output = output or ROOT / f'artifacts/pure-ledger-{version}.apk'
        if output.exists():
            raise ValueError('Release artifact exists; choose a new output or advance the version')
        folder = ROOT / 'artifacts/validation/signing'
        folder.mkdir(parents=True, exist_ok=True)
        wrapper = ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
        tasks = [wrapper, ':app:assembleRelease', ':app:lintRelease', '--console=plain']
        with (folder / 'build-release.log').open('w', encoding='utf-8') as log:
            result = subprocess.run(list(map(str, tasks)), cwd=ROOT, env=self.env, stdout=log, stderr=subprocess.STDOUT)
        if result.returncode:
            raise RuntimeError('Release build failed; see artifacts/validation/signing/build-release.log')
        self.sign(ROOT / 'app/build/outputs/apk/release/app-release-unsigned.apk', output)
        summary = self.verify(output)
        if summary['version'] != version or summary['version_code'] != int(props['versionCode']):
            raise ValueError('APK version does not match the source configuration')
        (output.with_suffix('.apk.sha256')).write_text(f'{summary["sha256"]}  {output.name}\n', encoding='utf-8')
        (folder / 'release-signing.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
        return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['init', 'build', 'verify'])
    parser.add_argument('--legacy-keystore', type=Path)
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    tools = ReleaseTools()
    if args.action == 'init':
        result = tools.initialize(args.legacy_keystore)
    elif args.action == 'build':
        result = tools.build(args.output.resolve() if args.output else None)
    else:
        if args.apk is None:
            parser.error('verify requires --apk')
        result = tools.verify(args.apk.resolve())
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
