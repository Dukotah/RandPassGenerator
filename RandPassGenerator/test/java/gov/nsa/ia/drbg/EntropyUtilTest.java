package gov.nsa.ia.drbg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.nsa.ia.TestSupport;

import org.junit.jupiter.api.Test;

class EntropyUtilTest {

    private static byte[] allByteValues() {
        byte[] buf = new byte[256];
        for (int i = 0; i < 256; i++) buf[i] = (byte) i;
        return buf;
    }

    @Test
    void byteEntropyOfEveryValueOnceIsEightBits() {
        assertEquals(8.0, EntropyUtil.computeByteEntropy(allByteValues(), 0), 1e-6);
    }

    @Test
    void byteEntropyOfConstantBufferIsZero() {
        assertEquals(0.0, EntropyUtil.computeByteEntropy(new byte[256], 0), 1e-12);
    }

    @Test
    void byteEntropyOfTwoValuesIsOneBit() {
        byte[] buf = new byte[256];
        for (int i = 0; i < buf.length; i += 2) buf[i] = 1;
        assertEquals(1.0, EntropyUtil.computeByteEntropy(buf, 0), 1e-6);
    }

    @Test
    void chiSquaredOfPerfectlyUniformNibblesIsZero() {
        assertEquals(0.0, EntropyUtil.chiSquaredStatistic(allByteValues(), 0, 4), 1e-12);
    }

    /**
     * Upper 5% points of chi-squared with 2^bits - 1 degrees of freedom,
     * from standard tables (df = 3, 7, 15, 31, 63, 255).
     */
    @Test
    void chiSquaredLimitUsesTwoToTheBitsMinusOneDegreesOfFreedom() {
        double[][] table = {
            { 2, 7.815 }, { 3, 14.067 }, { 4, 24.996 }, { 5, 44.985 }, { 6, 82.529 }, { 8, 293.248 } };
        for (double[] row : table) {
            int bits = (int) row[0];
            double exact = row[1];
            assertTrue(EntropyUtil.testChiSquared(exact * 0.99, bits), bits + " bits, just under");
            assertFalse(EntropyUtil.testChiSquared(exact * 1.01, bits), bits + " bits, just over");
        }
    }

    /** The old table rejected good DRBG output most of the time for 4-bit chunks. */
    @Test
    void goodRandomDataPassesFourBitChiSquaredTest() {
        HashDRBG drbg = TestSupport.fixedDrbg("entropyutil");
        int pass = 0;
        byte[] buf = new byte[512];
        for (int i = 0; i < 100; i++) {
            drbg.generate(buf.length, 0, false, null, buf);
            if (EntropyUtil.testChiSquared(EntropyUtil.chiSquaredStatistic(buf, 0, 4), 4)) pass++;
        }
        // about 95 expected at the 5% level; fixed seed makes the count repeatable
        assertTrue(pass >= 85, "passed " + pass + " of 100");
    }

    @Test
    void heavilyBiasedDataFailsChiSquaredTest() {
        byte[] buf = allByteValues();
        for (int i = 0; i < 64; i++) buf[i] = 0;
        assertFalse(EntropyUtil.testChiSquared(EntropyUtil.chiSquaredStatistic(buf, 0, 4), 4));
    }
}
