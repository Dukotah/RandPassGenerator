package gov.nsa.ia.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.nsa.ia.TestSupport;
import gov.nsa.ia.drbg.HashDRBG;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class HexKeyGenTest {

    private final HexKeyGen kg = new HexKeyGen(TestSupport.quietLogger());

    @Test
    void keyHasFourBitsPerLowercaseHexDigit() {
        HashDRBG drbg = TestSupport.fixedDrbg("hex-length");
        for (int strength : new int[] { 8, 64, 128, 160, 192, 256, 384 }) {
            String key = kg.generateKey(strength, drbg);
            assertNotNull(key);
            assertEquals(strength / 4, key.length(), "strength " + strength);
            assertTrue(key.matches("[0-9a-f]+"), key);
        }
    }

    @Test
    void refusesStrengthThatIsNotAWholeNumberOfBytesOrTooLarge() {
        HashDRBG drbg = TestSupport.fixedDrbg("hex-bad");
        assertNull(kg.generateKey(0, drbg));
        assertNull(kg.generateKey(100, drbg));
        assertNull(kg.generateKey(392, drbg));
    }

    @Test
    void keysDoNotRepeat() {
        HashDRBG drbg = TestSupport.fixedDrbg("hex-repeat");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10000; i++) {
            assertTrue(seen.add(kg.generateKey(128, drbg)));
        }
    }

    @Test
    void hexDigitsAreUniform() {
        HashDRBG drbg = TestSupport.fixedDrbg("hex-uniform");
        long[] counts = new long[16];
        for (int i = 0; i < 2000; i++) {
            for (char c : kg.generateKey(256, drbg).toCharArray()) counts[Character.digit(c, 16)]++;
        }
        double chi = TestSupport.chiSquared(counts);
        assertTrue(chi < TestSupport.chiSquaredLimit999(15), "chi-squared " + chi);
    }
}
