package gov.nsa.ia.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.nsa.ia.TestSupport;
import gov.nsa.ia.drbg.HashDRBG;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

class CharacterSetTest {

    private static final Logger LOG = TestSupport.quietLogger();

    @Test
    void defaultSetHas64UniqueCharactersSoSixBitsEach() {
        CharacterSet cs = new CharacterSet(LOG, CharacterSet.DEFAULT_USABLE);
        assertEquals(64, cs.size());
        assertEquals(6.0, cs.bitsPerItem(), 1e-9);
    }

    @Test
    void defaultSetLeavesOutLookAlikeCharacters() {
        for (char c : "O01l".toCharArray()) {
            assertEquals(-1, CharacterSet.DEFAULT_USABLE.indexOf(c), "should not contain " + c);
        }
    }

    @Test
    void duplicateCharactersDoNotAddEntropy() {
        // the same input as test 4 of CharacterSet.main: 32 distinct characters
        CharacterSet cs = new CharacterSet(LOG);
        cs.addSet("abcdefghijklmnopqrstuvwxyz012345aaaaaabbbbcccc0000000000tttttttttttttttttt");
        cs.addSet("ggggggggghhhhhhhhhhhhjjjjjjjjjjjj");
        assertEquals(32, cs.size());
        assertEquals(5.0, cs.bitsPerItem(), 1e-9);
    }

    @Test
    void lengthIsSmallestThatMeetsRequestedStrength() {
        HashDRBG drbg = TestSupport.fixedDrbg("cs-length");
        String[] sets = {
            CharacterSet.DEFAULT_USABLE,
            CharacterSet.DIGITS,
            CharacterSet.LOWERCASE_LETTERS + CharacterSet.DIGITS,
            CharacterSet.LOWERCASE_LETTERS + CharacterSet.UPPERCASE_LETTERS
                + CharacterSet.DIGITS + CharacterSet.PUNCTUATION,
        };
        for (String set : sets) {
            CharacterSet cs = new CharacterSet(LOG, set);
            double bits = cs.bitsPerItem();
            for (int strength : new int[] { 1, 40, 64, 96, 128, 160, 192, 256, 384 }) {
                String pw = cs.getRandomStringByEntropy(strength, drbg);
                assertNotNull(pw);
                int len = pw.length();
                assertEquals((int) Math.ceil(strength / bits), len, set + " @ " + strength);
                assertTrue(len * bits >= strength);
                assertTrue((len - 1) * bits < strength);
            }
        }
    }

    @Test
    void refusesStrengthAboveTwiceDrbgStrength() {
        HashDRBG drbg = TestSupport.fixedDrbg("cs-limit");
        CharacterSet cs = new CharacterSet(LOG, CharacterSet.DEFAULT_USABLE);
        assertNull(cs.getRandomStringByEntropy(385, drbg));
        assertNull(cs.getRandomStringByEntropy(0, drbg));
        assertNull(cs.getRandomString(0, drbg));
    }

    @Test
    void outputUsesOnlyCharactersFromTheSet() {
        HashDRBG drbg = TestSupport.fixedDrbg("cs-members");
        String[] sets = {
            CharacterSet.DEFAULT_USABLE, CharacterSet.LOWERCASE_LETTERS, CharacterSet.UPPERCASE_LETTERS,
            CharacterSet.DIGITS, CharacterSet.PUNCTUATION, CharacterSet.SPECIAL_CHARS, "xy" };
        for (String set : sets) {
            CharacterSet cs = new CharacterSet(LOG, set);
            for (int i = 0; i < 200; i++) {
                for (char c : cs.getRandomString(50, drbg).toCharArray()) {
                    assertTrue(set.indexOf(c) >= 0, "'" + c + "' not in " + set);
                }
            }
        }
    }

    private static long[] draw(CharacterSet cs, String set, int n, HashDRBG drbg) {
        Map<Character, Integer> index = new HashMap<>();
        for (int i = 0; i < set.length(); i++) index.put(set.charAt(i), i);
        long[] counts = new long[set.length()];
        int remaining = n;
        while (remaining > 0) {
            int len = Math.min(1000, remaining);
            for (char c : cs.getRandomString(len, drbg).toCharArray()) counts[index.get(c)]++;
            remaining -= len;
        }
        return counts;
    }

    @Test
    void defaultSetIsUniform() {
        String set = CharacterSet.DEFAULT_USABLE;
        long[] counts = draw(new CharacterSet(LOG, set), set, 64 * 2000, TestSupport.fixedDrbg("cs-uniform64"));
        double chi = TestSupport.chiSquared(counts);
        assertTrue(chi < TestSupport.chiSquaredLimit999(63), "chi-squared " + chi);
    }

    @Test
    void digitsAreUniform() {
        String set = CharacterSet.DIGITS;
        long[] counts = draw(new CharacterSet(LOG, set), set, 10 * 10000, TestSupport.fixedDrbg("cs-uniform10"));
        double chi = TestSupport.chiSquared(counts);
        assertTrue(chi < TestSupport.chiSquaredLimit999(9), "chi-squared " + chi);
    }

    /**
     * Draws are 15-bit values (0..32767). For a 20000-character set, taking
     * the value mod 20000 without rejection would make the first 12768
     * characters twice as likely as the rest; CharacterSet rejects values at
     * or above 20000 instead. This checks that the low and high parts of the
     * set come up in proportion to their size.
     */
    @Test
    void rejectionSamplingRemovesModuloBias() {
        int size = 20000;
        StringBuilder sb = new StringBuilder(size);
        for (int i = 0; i < size; i++) sb.append((char) (0x4E00 + i));
        String set = sb.toString();
        CharacterSet cs = new CharacterSet(LOG, set);
        assertEquals(size, cs.size());

        int n = 200000;
        long[] counts = draw(cs, set, n, TestSupport.fixedDrbg("cs-rejection"));
        long low = 0;
        for (int i = 0; i < 32768 - size; i++) low += counts[i];
        double p = (32768.0 - size) / size;                 // 0.6384 when unbiased
        double sd = Math.sqrt(n * p * (1 - p));
        // with modulo bias the expected fraction would be 2*12768/32768 = 0.779
        assertTrue(Math.abs(low - n * p) < 4 * sd, "low part " + low + " of " + n + ", expected " + (n * p));
        double chi = TestSupport.chiSquared(counts);
        assertTrue(chi < TestSupport.chiSquaredLimit999(size - 1), "chi-squared " + chi);
    }
}
