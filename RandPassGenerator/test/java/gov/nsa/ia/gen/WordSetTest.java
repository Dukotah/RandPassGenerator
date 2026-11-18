package gov.nsa.ia.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.nsa.ia.TestSupport;
import gov.nsa.ia.drbg.HashDRBG;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WordSetTest {

    private static final Logger LOG = TestSupport.quietLogger();

    private static List<String> bundledWords() throws IOException {
        List<String> ret = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                 WordSetTest.class.getClassLoader().getResourceAsStream(WordSet.DATA_PATH),
                 StandardCharsets.US_ASCII))) {
            String line;
            while ((line = br.readLine()) != null) ret.add(line);
        }
        return ret;
    }

    /**
     * Entropy per word is counted from the number of distinct words, and
     * -rcc/-rrcc are not counted at all. Two entries that differ only in
     * case, or mixed-case entries, would make that count too high.
     */
    @Test
    void bundledWordlistIsDistinctLowercaseWordsOfThreeOrMoreLetters() throws IOException {
        List<String> words = bundledWords();
        Set<String> seen = new HashSet<>();
        for (String w : words) {
            assertTrue(w.matches("[a-z]{3,}"), "bad entry '" + w + "'");
            assertTrue(seen.add(w), "duplicate '" + w + "'");
        }
        assertTrue(words.size() > 100000, "only " + words.size() + " words");
    }

    @Test
    void entropyPerWordIsLog2OfUsableWords() {
        WordSet ws = new WordSet(null, LOG);
        assertTrue(ws.size() > 0);
        assertEquals(Math.log(ws.size()) / Math.log(2.0), ws.bitsPerItem(), 1e-9);

        int cnt = ws.setLengthRange(3, 8);
        assertEquals(cnt, ws.size());
        assertTrue(ws.bitsPerItem() > 15.0, "bits per word " + ws.bitsPerItem());
    }

    @Test
    void wordCountIsSmallestThatMeetsRequestedStrength() {
        HashDRBG drbg = TestSupport.fixedDrbg("ws-count");
        WordSet ws = new WordSet(null, LOG);
        ws.setLengthRange(3, 8);
        double bits = ws.bitsPerItem();
        for (int strength : new int[] { 16, 64, 128, 160, 256, 384 }) {
            String[] words = ws.getRandomWordListByEntropy(strength, drbg);
            assertNotNull(words);
            assertEquals((int) Math.ceil(strength / bits), words.length, "strength " + strength);
            for (String w : words) {
                assertTrue(w.length() >= 3 && w.length() <= 8, w);
            }
        }
        assertNull(ws.getRandomWordListByEntropy(385, drbg));
    }

    @Test
    void lengthRangeIsRespected() {
        HashDRBG drbg = TestSupport.fixedDrbg("ws-range");
        WordSet ws = new WordSet(null, LOG);
        ws.setLengthRange(4, 5);
        for (String w : ws.getRandomWordList(2000, drbg)) {
            assertTrue(w.length() == 4 || w.length() == 5, w);
        }
    }

    @Test
    void duplicateWordsInACustomListAreIgnored(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("words.txt");
        Files.write(f, List.of("alpha", "bravo", "alpha", "  bravo  ", "charlie", ""));
        WordSet ws = new WordSet(f.toUri().toURL(), LOG);
        assertEquals(3, ws.size());
    }

    @Test
    void customListIsUniform(@TempDir Path dir) throws IOException {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < 50; i++) list.add("word" + (char) ('a' + i / 26) + (char) ('a' + i % 26));
        Path f = dir.resolve("words.txt");
        Files.write(f, list);
        URL url = f.toUri().toURL();
        WordSet ws = new WordSet(url, LOG);
        assertEquals(50, ws.size());

        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < list.size(); i++) index.put(list.get(i), i);
        long[] counts = new long[list.size()];
        HashDRBG drbg = TestSupport.fixedDrbg("ws-uniform");
        for (String w : ws.getRandomWordList(50 * 2000, drbg)) counts[index.get(w)]++;
        double chi = TestSupport.chiSquared(counts);
        assertTrue(chi < TestSupport.chiSquaredLimit999(49), "chi-squared " + chi);
    }

    @Test
    void randomUpcaseTouchesOnlyTheFirstNLettersAtHalfProbability() {
        HashDRBG drbg = TestSupport.fixedDrbg("ws-upcase");
        WordSet ws = new WordSet(null, LOG);
        int trials = 20000, upper = 0;
        for (int i = 0; i < trials; i++) {
            String s = ws.randomUpcase("abcdefgh", 3, drbg);
            assertEquals("defgh", s.substring(3));
            assertEquals("abc", s.substring(0, 3).toLowerCase());
            for (int j = 0; j < 3; j++) if (Character.isUpperCase(s.charAt(j))) upper++;
        }
        assertBinomialHalf(upper, 3 * trials);
        assertEquals("abc", ws.randomUpcase("abc", 0, drbg));
    }

    @Test
    void reverseRandomUpcaseTouchesOnlyTheLastNLettersAtHalfProbability() {
        HashDRBG drbg = TestSupport.fixedDrbg("ws-rupcase");
        WordSet ws = new WordSet(null, LOG);
        int trials = 20000, upper = 0;
        for (int i = 0; i < trials; i++) {
            String s = ws.reverseRandomUpcase("abcdefgh", 3, drbg);
            assertEquals("abcde", s.substring(0, 5));
            assertEquals("fgh", s.substring(5).toLowerCase());
            for (int j = 5; j < 8; j++) if (Character.isUpperCase(s.charAt(j))) upper++;
        }
        assertBinomialHalf(upper, 3 * trials);
        assertEquals("abc", ws.reverseRandomUpcase("abc", 0, drbg));
        // n longer than the word: every letter is a candidate
        String s = ws.reverseRandomUpcase("abcdefghijklmnopqrstuvwxyz", 100, drbg);
        assertEquals("abcdefghijklmnopqrstuvwxyz", s.toLowerCase());
    }

    private static void assertBinomialHalf(int hits, int n) {
        double sd = Math.sqrt(n * 0.25);
        assertTrue(Math.abs(hits - n / 2.0) < 3.29 * sd, hits + " of " + n);
    }
}
