import com.android.apksig.SigningCertificateLineage;
import java.io.File;
import java.util.Arrays;

/** Uses the installed Android build tools to verify the embedded proof and its trust capabilities. */
public class VerifySigningLineage {
    public static void main(String[] args) throws Exception {
        var expected = SigningCertificateLineage.readFromFile(new File(args[1]));
        var actual = SigningCertificateLineage.readFromApkFile(new File(args[0]));
        if (actual == null || !Arrays.equals(expected.getBytes(), actual.getBytes())) {
            throw new IllegalStateException("APK signing lineage differs from the public release lineage");
        }
        var certificates = actual.getCertificatesInLineage();
        if (certificates.size() != 2) throw new IllegalStateException("Expected exactly two certificates");
        var previous = actual.getSignerCapabilities(certificates.get(0));
        if (!previous.hasInstalledData() || !previous.hasPermission() || previous.hasSharedUid()
                || previous.hasRollback() || previous.hasAuth()) {
            throw new IllegalStateException("Unexpected previous-signer trust capabilities");
        }
        System.out.println("{\"embedded_lineage_matches\":true,\"certificate_count\":2,\"legacy_rollback\":false}");
    }
}
