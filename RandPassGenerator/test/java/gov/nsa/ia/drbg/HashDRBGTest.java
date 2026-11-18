package gov.nsa.ia.drbg;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.nsa.ia.TestSupport;
import gov.nsa.ia.util.LousyEntropySource;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class HashDRBGTest {

    @Test
    void builtInKnownAnswerTestsPass() {
        assertTrue(HashDRBG.performKnownAnswerTests(TestSupport.quietLogger()));
    }

    @Test
    void builtInSelfTestPasses() {
        HashDRBG drbg = new HashDRBG("selftest", new LousyEntropySource());
        drbg.setLogger(null);
        assertTrue(drbg.performSelfTest());
    }

    @Test
    void strengthSelectsHash() {
        int[][] expect = { { 64, 80 }, { 80, 80 }, { 128, 128 }, { 192, 192 }, { 256, 256 } };
        for (int[] e : expect) {
            HashDRBG drbg = new HashDRBG("s", new LousyEntropySource());
            drbg.setLogger(null);
            assertEquals(DRBGConstants.STATUS_SUCCESS, drbg.instantiate(e[0], false, null, null));
            assertEquals(e[1], drbg.getStrength(), "requested " + e[0]);
        }
    }

    @Test
    void refusesStrengthOutsideSupportedRange() {
        for (int s : new int[] { 32, 63, 257, 512 }) {
            HashDRBG drbg = new HashDRBG("s", new LousyEntropySource());
            drbg.setLogger(null);
            assertNotEquals(DRBGConstants.STATUS_SUCCESS, drbg.instantiate(s, false, null, null), "strength " + s);
        }
    }

    @Test
    void refusesGenerateBeforeInstantiate() {
        HashDRBG drbg = new HashDRBG("s", new LousyEntropySource());
        drbg.setLogger(null);
        assertNotEquals(DRBGConstants.STATUS_SUCCESS, drbg.generate(16, 0, false, null, new byte[16]));
    }

    @Test
    void refusesRequestAboveInstantiatedStrength() {
        HashDRBG drbg = TestSupport.fixedDrbg("above");
        assertNotEquals(DRBGConstants.STATUS_SUCCESS, drbg.generate(16, 256, false, null, new byte[16]));
        assertEquals(DRBGConstants.STATUS_SUCCESS, drbg.generate(16, 192, false, null, new byte[16]));
    }

    @Test
    void refusesPredictionResistanceNotRequestedAtInstantiation() {
        HashDRBG drbg = TestSupport.fixedDrbg("pr");
        assertNotEquals(DRBGConstants.STATUS_SUCCESS, drbg.generate(16, 0, true, null, new byte[16]));
    }

    @Test
    void sameSeedSameOutputDifferentSeedDifferentOutput() {
        byte[] a = new byte[64], b = new byte[64], c = new byte[64];
        TestSupport.fixedDrbg("x").generate(64, 0, false, null, a);
        TestSupport.fixedDrbg("x").generate(64, 0, false, null, b);
        TestSupport.fixedDrbg("y").generate(64, 0, false, null, c);
        assertArrayEquals(a, b);
        assertFalse(Arrays.equals(a, c));
    }

    /**
     * SP 800-90A section 9.3.1 step 7.2: if the reseed that prediction
     * resistance requires fails, generate must return an error without
     * producing output. HashDRBG used to log the failure and generate anyway.
     */
    @Test
    void failedPredictionResistanceReseedProducesNoOutput() {
        EntropySource onceOnly = new EntropySource() {
            private int calls = 0;

            public byte[] getEntropy(int bits, int minBytes, int maxBytes) {
                calls++;
                if (calls > 1) return null;
                byte[] ret = new byte[minBytes];
                Arrays.fill(ret, (byte) 0x5a);
                return ret;
            }

            public boolean performSelfTest() {
                return true;
            }

            public byte[] getSelfTestEntropy() {
                return null;
            }

            public void dispose() {
            }
        };
        HashDRBG drbg = new HashDRBG("pr-fail", onceOnly);
        drbg.setLogger(null);
        assertEquals(DRBGConstants.STATUS_SUCCESS, drbg.instantiate(192, true, null, null));
        byte[] out = new byte[32];
        assertNotEquals(DRBGConstants.STATUS_SUCCESS, drbg.generate(32, 0, true, null, out));
        assertArrayEquals(new byte[32], out, "output buffer must be left untouched");
    }

    @Test
    void byteChiSquaredUniformity() {
        HashDRBG drbg = TestSupport.fixedDrbg("bytes");
        long[] counts = new long[256];
        byte[] buf = new byte[4096];
        for (int i = 0; i < 64; i++) {
            assertEquals(DRBGConstants.STATUS_SUCCESS, drbg.generate(buf.length, 0, false, null, buf));
            for (byte x : buf) counts[x & 0xff]++;
        }
        double chi = TestSupport.chiSquared(counts);
        assertTrue(chi < TestSupport.chiSquaredLimit999(255), "chi-squared " + chi);
    }
}
