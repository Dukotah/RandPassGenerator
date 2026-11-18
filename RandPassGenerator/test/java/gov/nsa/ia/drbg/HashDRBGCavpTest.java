package gov.nsa.ia.drbg;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import gov.nsa.ia.util.FixedValuesEntropySource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs HashDRBG against the NIST CAVP SP 800-90A Hash_DRBG response files
 * (CAVS 14.3, drbgtestvectors.zip). See test/resources/cavp/README.txt for
 * where the files come from and which parts were kept.
 *
 * HashDRBG chooses its hash from the requested strength (80 -> SHA-1,
 * 128 -> SHA-256, 192 -> SHA-384, 256 -> SHA-512), so those four hash
 * sections are tested. SHA-224 and the SHA-512/t variants cannot be selected.
 *
 * HashDRBG takes the personalization string as a java.lang.String and calls
 * getBytes() on it, which cannot carry arbitrary bytes. Instantiate hashes
 * entropy_input || nonce || personalization_string, so the test passes
 * nonce || personalization_string as the nonce and null as the string. The
 * DRBG sees exactly the seed material SP 800-90A section 10.1.1.2 specifies.
 */
class HashDRBGCavpTest {

    private static final Map<String, Integer> STRENGTH_FOR_HASH = Map.of(
        "SHA-1", 80, "SHA-256", 128, "SHA-384", 192, "SHA-512", 256);

    /** One COUNT block plus the bracketed parameters it was listed under. */
    static final class Vector {
        final String file;
        final Map<String, String> params;
        final List<Map.Entry<String, String>> fields = new ArrayList<>();

        Vector(String file, Map<String, String> params) {
            this.file = file;
            this.params = params;
        }

        String first(String key) {
            for (Map.Entry<String, String> e : fields) {
                if (e.getKey().equals(key)) return e.getValue();
            }
            throw new IllegalStateException("missing " + key);
        }

        List<String> all(String key) {
            List<String> ret = new ArrayList<>();
            for (Map.Entry<String, String> e : fields) {
                if (e.getKey().equals(key)) ret.add(e.getValue());
            }
            return ret;
        }

        String name() {
            return params.get("hash") + " PR=" + params.get("PredictionResistance")
                + " P=" + params.get("PersonalizationStringLen")
                + " A=" + params.get("AdditionalInputLen")
                + " COUNT=" + first("COUNT");
        }
    }

    static List<Vector> load(String file) throws IOException {
        List<Vector> ret = new ArrayList<>();
        Map<String, String> params = new HashMap<>();
        Vector cur = null;
        boolean inParams = false;
        try (InputStream raw = HashDRBGCavpTest.class.getResourceAsStream("/cavp/" + file);
             BufferedReader br = new BufferedReader(new InputStreamReader(
                 new GZIPInputStream(raw), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("[")) {
                    if (!inParams) {
                        params = new HashMap<>();
                        inParams = true;
                    }
                    String body = line.substring(1, line.length() - 1);
                    int eq = body.indexOf('=');
                    if (eq < 0) params.put("hash", body.trim());
                    else params.put(body.substring(0, eq).trim(), body.substring(eq + 1).trim());
                    continue;
                }
                inParams = false;
                int eq = line.indexOf('=');
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();
                if (key.equals("COUNT")) {
                    cur = new Vector(file, params);
                    ret.add(cur);
                }
                cur.fields.add(new SimpleEntry<>(key, val));
            }
        }
        return ret;
    }

    static byte[] hex(String s) {
        byte[] ret = new byte[s.length() / 2];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return ret;
    }

    static byte[] orNull(String s) {
        return s.isEmpty() ? null : hex(s);
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] ret = new byte[a.length + b.length];
        System.arraycopy(a, 0, ret, 0, a.length);
        System.arraycopy(b, 0, ret, a.length, b.length);
        return ret;
    }

    /** Run one vector and return the bytes from the second generate call. */
    static byte[] run(Vector v) {
        boolean pr = Boolean.parseBoolean(v.params.get("PredictionResistance"));
        boolean reseed = v.fields.stream().anyMatch(e -> e.getKey().equals("EntropyInputReseed"));
        int strength = STRENGTH_FOR_HASH.get(v.params.get("hash"));
        int outBytes = Integer.parseInt(v.params.get("ReturnedBitsLen")) / 8;

        // entropy is handed out in the order the DRBG will ask for it
        FixedValuesEntropySource src = new FixedValuesEntropySource();
        src.addValue(hex(v.first("EntropyInput")));
        if (reseed) src.addValue(hex(v.first("EntropyInputReseed")));
        for (String e : v.all("EntropyInputPR")) src.addValue(hex(e));

        HashDRBG drbg = new HashDRBG("cavp", src);
        drbg.setLogger(null);

        byte[] nonce = concat(hex(v.first("Nonce")), hex(v.first("PersonalizationString")));
        assertEquals(DRBGConstants.STATUS_SUCCESS, drbg.instantiate(strength, pr, null, nonce), "instantiate");
        if (reseed) {
            assertEquals(DRBGConstants.STATUS_SUCCESS,
                         drbg.reseed(orNull(v.first("AdditionalInputReseed"))), "reseed");
        }
        List<String> addl = v.all("AdditionalInput");
        byte[] out = new byte[outBytes];
        for (int i = 0; i < 2; i++) {
            assertEquals(DRBGConstants.STATUS_SUCCESS,
                         drbg.generate(outBytes, 0, pr, orNull(addl.get(i)), out), "generate " + (i + 1));
        }
        return out;
    }

    private static Stream<DynamicNode> suite(String file) throws IOException {
        List<Vector> vectors = load(file);
        Map<String, List<DynamicTest>> byHash = new java.util.LinkedHashMap<>();
        for (Vector v : vectors) {
            String hash = v.params.get("hash");
            if (!STRENGTH_FOR_HASH.containsKey(hash)) continue;
            byHash.computeIfAbsent(hash, k -> new ArrayList<>()).add(
                DynamicTest.dynamicTest(v.name(),
                    () -> assertArrayEquals(hex(v.first("ReturnedBits")), run(v), v.name())));
        }
        return byHash.entrySet().stream()
            .map(e -> DynamicContainer.dynamicContainer(e.getKey(), e.getValue()));
    }

    @TestFactory
    Stream<DynamicNode> noReseed() throws IOException {
        return suite("Hash_DRBG_no_reseed.rsp.gz");
    }

    @TestFactory
    Stream<DynamicNode> predictionResistanceFalse() throws IOException {
        return suite("Hash_DRBG_pr_false.rsp.gz");
    }

    @TestFactory
    Stream<DynamicNode> predictionResistanceTrue() throws IOException {
        return suite("Hash_DRBG_pr_true.rsp.gz");
    }
}
