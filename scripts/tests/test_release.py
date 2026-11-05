"""Release checks must not confuse a legacy compatibility signer with the active signer."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from release import signature_for_sdk, validate_version_properties


class SignerSelectionTest(unittest.TestCase):
    legacy = '1' * 64
    release = '2' * 64

    def test_legacy_output_has_one_certificate_for_every_requested_sdk(self):
        output = f'Signer #1 certificate SHA-256 digest: {self.legacy}\n'
        self.assertEqual(self.legacy, signature_for_sdk(output, 26))
        self.assertEqual(self.legacy, signature_for_sdk(output, 32))

    def test_selects_active_signer_instead_of_first_or_last_printed_certificate(self):
        rows = [f'Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: {self.release}',
                f'Signer (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: {self.legacy}']
        for output in ['\n'.join(rows), '\n'.join(reversed(rows))]:
            self.assertEqual(self.legacy, signature_for_sdk(output, 32))
            self.assertEqual(self.release, signature_for_sdk(output, 33))
            self.assertEqual(self.release, signature_for_sdk(output, 36))

    def test_missing_overlapping_and_multiple_signers_fail_closed(self):
        samples = ['', f'Signer #1 certificate SHA-256 digest: {self.legacy}\nSigner #2 certificate SHA-256 digest: {self.release}',
                   f'Signer (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: {self.legacy}',
                   f'Signer (minSdkVersion=24, maxSdkVersion=36) certificate SHA-256 digest: {self.legacy}\n'
                   f'Signer (minSdkVersion=33, maxSdkVersion=36) certificate SHA-256 digest: {self.release}']
        for output in samples:
            with self.assertRaises(ValueError):
                signature_for_sdk(output, 33)


class PublicVersionResetTest(unittest.TestCase):
    def test_replacement_revision_preserves_upgrade_order(self):
        original = validate_version_properties(dict(versionName='0.1.1', versionCode='60500', versionCodeBase='50400'))
        replacement = validate_version_properties(dict(versionName='0.1.1', versionCode='60501', versionCodeBase='50400', versionRevision='1'))
        last_revision = validate_version_properties(dict(versionName='0.1.1', versionCode='60599', versionCodeBase='50400', versionRevision='99'))
        following = validate_version_properties(dict(versionName='0.1.2', versionCode='60600', versionCodeBase='50400'))
        self.assertLess(original, replacement)
        self.assertLess(replacement, last_revision)
        self.assertLess(last_revision, following)

    def test_revision_cannot_overlap_a_different_public_patch(self):
        for revision, code in [('-1', '60499'), ('100', '60600'), ('1', '60500')]:
            with self.assertRaises(ValueError):
                validate_version_properties(dict(versionName='0.1.1', versionCode=code, versionCodeBase='50400', versionRevision=revision))

    def test_public_first_release_still_upgrades_private_development_builds(self):
        first = validate_version_properties(dict(versionName='0.1.0', versionCode='60400', versionCodeBase='50400'))
        following = validate_version_properties(dict(versionName='0.1.1', versionCode='60500', versionCodeBase='50400'))
        self.assertGreater(first, 60300)
        self.assertGreater(following, first)

    def test_resetting_internal_code_or_exceeding_android_limit_is_rejected(self):
        for version, code, base in [('0.1.0', '10000', '50400'), ('2100.0.0', '2100050400', '50400')]:
            with self.assertRaises(ValueError):
                validate_version_properties(dict(versionName=version, versionCode=code, versionCodeBase=base))


if __name__ == '__main__':
    unittest.main()
