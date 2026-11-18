package gov.nsa.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gov.nsa.ia.drbg.DRBGConstants;
import gov.nsa.ia.drbg.HashDRBG;
import gov.nsa.ia.util.FixedValuesEntropySource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared helpers for the test suite.
 *
 * The statistical tests draw from a HashDRBG seeded with fixed bytes, so a
 * given test sees the same sequence on every run and cannot flake: it either
 * always passes or always fails.
 */
public final class TestSupport {

    private TestSupport() {
    }

    /** A logger that discards everything; the library classes require one. */
    public static Logger quietLogger() {
        Logger log = Logger.getLogger("randpassgen-tests");
        log.setUseParentHandlers(false);
        log.setLevel(Level.OFF);
        return log;
    }

    /**
     * A 192-bit HashDRBG (SHA-384, the strength RandManager uses) whose seed
     * is derived from {@code label}, so different tests use different but
     * fixed streams.
     */
    public static HashDRBG fixedDrbg(String label) {
        byte[] seed = new byte[888 / 8];
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            byte[] a = md.digest(("seed-a:" + label).getBytes(StandardCharsets.US_ASCII));
            byte[] b = md.digest(("seed-b:" + label).getBytes(StandardCharsets.US_ASCII));
            System.arraycopy(a, 0, seed, 0, a.length);
            System.arraycopy(b, 0, seed, a.length, seed.length - a.length);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        FixedValuesEntropySource src = new FixedValuesEntropySource();
        src.addValue(seed);
        HashDRBG drbg = new HashDRBG("test-" + label, src);
        drbg.setLogger(null);
        int status = drbg.instantiate(192, false, null, label.getBytes(StandardCharsets.US_ASCII));
        assertEquals(DRBGConstants.STATUS_SUCCESS, status, "instantiate fixed test DRBG");
        return drbg;
    }

    /** Pearson's chi-squared statistic of observed counts against a uniform expectation. */
    public static double chiSquared(long[] counts) {
        long total = 0;
        for (long c : counts) total += c;
        double expected = (double) total / counts.length;
        double sum = 0.0;
        for (long c : counts) {
            double d = c - expected;
            sum += d * d / expected;
        }
        return sum;
    }

    /**
     * Upper 0.1% point of the chi-squared distribution with {@code df}
     * degrees of freedom (Wilson-Hilferty; within 0.5% of the exact value
     * for df >= 9, which is all this suite uses). A uniform source exceeds
     * this one time in a thousand, and the fixed seeds make each test's
     * outcome repeatable.
     */
    public static double chiSquaredLimit999(int df) {
        double z = 3.0902; // upper 0.1% point of the standard normal
        double a = 2.0 / (9.0 * df);
        double t = 1.0 - a + z * Math.sqrt(a);
        return df * t * t * t;
    }
}
