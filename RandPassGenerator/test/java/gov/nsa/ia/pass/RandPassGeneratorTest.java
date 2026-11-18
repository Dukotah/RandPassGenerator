package gov.nsa.ia.pass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.nsa.ia.gen.CharacterSet;
import gov.nsa.ia.gen.WordSet;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end checks through the same entry points main() uses, with the
 * real entropy source (JavaSecRandEntropySource) and the startup
 * known-answer and self-tests. Only the shape of the output is checked.
 */
class RandPassGeneratorTest {

    @TempDir
    Path dir;

    private StringWriter out;
    private RandPassGenerator rpg;

    @BeforeEach
    void start() {
        out = new StringWriter();
        rpg = new RandPassGenerator(dir.resolve("randpass.log").toString(), new PrintWriter(out), false);
    }

    @AfterEach
    void stop() {
        rpg.close();
    }

    private List<String> lines() {
        return Arrays.asList(out.toString().split("\\R"));
    }

    @Test
    void defaultPasswordsAre27CharactersFromTheDefaultSet() {
        assertEquals(5, rpg.generatePasswords(5, 160, null, null));
        List<String> pw = lines();
        assertEquals(5, pw.size());
        for (String p : pw) {
            assertEquals(27, p.length(), p);          // ceil(160 / 6)
            for (char c : p.toCharArray()) assertTrue(CharacterSet.DEFAULT_USABLE.indexOf(c) >= 0, p);
        }
    }

    @Test
    void passwordCharsetPatternSelectsWholeSets() {
        assertEquals(3, rpg.generatePasswords(3, 160, "a0", null));
        for (String p : lines()) {
            assertEquals(31, p.length(), p);          // 36 characters: ceil(160 / 5.17)
            assertTrue(p.matches("[a-z0-9]+"), p);
        }
    }

    @Test
    void customCharsetFile() throws Exception {
        Path f = dir.resolve("chars.txt");
        Files.write(f, List.of("abcd", "abcd", "wxyz"));   // 8 distinct characters, 3 bits each
        assertEquals(2, rpg.generatePasswords(2, 96, null, f.toString()));
        for (String p : lines()) {
            assertEquals(32, p.length(), p);
            assertTrue(p.matches("[abcdwxyz]+"), p);
        }
    }

    @Test
    void chunkFormatting() {
        rpg.setChunkFormatting(5, "/");
        assertEquals(1, rpg.generatePasswords(1, 128, null, null));
        String p = lines().get(0);
        assertTrue(p.matches("([^/]{5}/){4}[^/]{2}"), p);   // 22 characters in chunks of 5
    }

    @Test
    void keysAreHexWithKeyIds() {
        assertEquals(3, rpg.generateKeys(3, 256, false));
        List<String> l = lines();
        assertEquals(9, l.size());
        for (int i = 0; i < 9; i += 3) {
            assertTrue(l.get(i).matches("[0-9a-f]{64}"), l.get(i));
            assertEquals("Key ID:", l.get(i + 1));
            assertTrue(l.get(i + 2).matches("\\d{8}_\\d{6}[0-9a-f]{16}"), l.get(i + 2));
        }
    }

    @Test
    void passphraseWordCountMatchesStrength() {
        WordSet ws = new WordSet(null, gov.nsa.ia.TestSupport.quietLogger());
        ws.setLengthRange(RandPassGenerator.MIN_WORD_LEN, RandPassGenerator.MAX_WORD_LEN);
        int words = (int) Math.ceil(128 / ws.bitsPerItem());

        assertEquals(4, rpg.generatePassphrases(4, 128, null, 0, 0, 0));
        for (String p : lines()) {
            String[] w = p.split(" ");
            assertEquals(words, w.length, p);
            for (String x : w) assertTrue(x.matches("[a-z]{3,8}"), p);
        }
    }

    @Test
    void passphraseUpcaseOptionsOnlyAffectTheirEnds() {
        assertEquals(20, rpg.generatePassphrases(20, 160, null, 0, 1, 1));
        for (String p : lines()) {
            for (String x : p.split(" ")) {
                assertTrue(x.matches("[a-zA-Z][a-z]*[a-zA-Z]"), x);
            }
        }
    }

    @Test
    void usernamesAreAWordAndFourDigits() {
        assertEquals(10, rpg.generateUsernames(10, null, 0, 0));
        for (String u : lines()) {
            assertTrue(u.matches("[a-z]{3,8}[0-9]{4}"), u);
        }
    }

    @Test
    void refusesStrengthAboveTwiceTheDrbgStrength() {
        assertEquals(-1, rpg.generatePasswords(1, 385, null, null));
        assertEquals(-1, rpg.generatePassphrases(1, 385, null, 0, 0, 0));
        assertEquals(-1, rpg.generateKeys(1, 392, false));
        assertEquals("", out.toString());
    }

    @Test
    void refusesNonPositiveCounts() {
        assertEquals(-1, rpg.generatePasswords(0, 128, null, null));
        assertEquals(-1, rpg.generatePassphrases(0, 128, null, 0, 0, 0));
        assertEquals(-1, rpg.generateKeys(0, 128, false));
        assertEquals(-1, rpg.generateUsernames(0, null, 0, 0));
    }
}
