# LANE 8 report: RandPassGenerator

## 1. Upstream contribution rules (CONTRIBUTING.md)

- The only rule is a CC0 1.0 waiver: by opening a PR, a non-US-government contributor releases the change under CC0. There is no CLA, no DCO, no style guide, and no issue or PR template.
- In practice there has been one merged PR (#1, 2022, by nealziring, who seems to be the original author). The last commit was a direct push of v1.3.4 on 2023-12-22. Outside PRs #2 and #3 (2023) have had no maintainer action.

## 2. Branches

All branches are pushed to `origin` (Dukotah/RandPassGenerator). `master` is untouched.

| Branch | Base | Bound for | Head |
|---|---|---|---|
| `fix/ci-build` (A) | upstream/master | upstream PR | `d07f8a7` Add GitHub Actions build and self-test workflow |
| `fix/selftest-fail-closed` | upstream/master | upstream PR (optional) | `7cb19dd` Stop when the startup self-test or initialization fails |
| `fix/hashdrbg-sp800-90a` | upstream/master | upstream PR (optional) | `14739e4` Make HashDRBG match SP 800-90A for leading zeros and PR |
| `lane/junit-native` (B) | `fix/ci-build` | fork only | A + 5 commits listed below, plus this report |

### A: `fix/ci-build` (one commit, 2 files)

- **`.github/workflows/build.yml`.** Runs on JDK 17, 21 and 25 (Temurin). Steps:
  1. `ant jar`, which is BUILD.txt step 4.
  2. Runs every self-test `main()` listed under BUILD.txt "UNIT TESTING" (HashDRBG and its KAT, JavaSecRand and UnixDevRandom entropy sources, Hash, FixedValuesEntropySource, CharacterSet, HexKeyGen, WordSet with the bundled list, RandManager). Most of these mains exit 0 even when a check fails, so the workflow also requires each one to print its own success line.
  3. Runs BUILD.txt step 5 (`-v -str 128 -k 7`), plus `-pw 5` and `-pp 3`, and checks the shape of the output.
- **`RandManager.java`, a fix to a test that never worked as published.** The `main()` unit test asks for 32-byte keys, but the DRBG is instantiated at 192 bits (24 bytes). `generateKey()` correctly refuses, so `java gov.nsa.ia.pass.RandManager <file>` has always stopped with exit status 1. `TEST_KEY_SIZE` is now `DRBG_STRENGTH / 8`. Only that test main uses it. No generation code changed.
- Nothing else failed to build: javac 17, 21 and 25 all compile the tree with warnings only.

### `fix/selftest-fail-closed` (one commit, `RandPassGenerator.java`)

This is a real defect in a security tool. README.txt says: "If the tests don't pass, the tool reports failure and refuses to run." When `RandManager.performSelfTest()` returned false, the constructor printed "Randomness self-test failed.  Exiting." and then **kept going**: it generated passwords and exited 0. A false return from `initialize()` was not reported at all.

Both cases now set the existing `die` flag, so `main` exits 6 through the same path setup exceptions already use. I proved the bug with fault injection (section 3).

### `fix/hashdrbg-sp800-90a` (one commit, `HashDRBG.java`)

Running the NIST CAVP Hash_DRBG vectors showed two places where HashDRBG doesn't conform to SP 800-90A. 494 of 2880 vectors failed; after this commit all 2880 pass.

1. **Leading zero bytes of V were dropped.** Generate steps 2 and 5, Hashgen, and reseed hashed `pbi2ba(V)`, a minimal encoding that drops leading zero bytes, instead of V as a seedlen-bit string. Whenever V's top byte is 0 (about 1 operation in 256), the output differs from the spec. This caused 14 vector failures spread across all modes.
2. **Additional input was used twice under prediction resistance.** With prediction resistance on, generate reseeded with `additionalInput` and then hashed it into V again. Section 9.3.1 step 7.4 says to set it to Null after the reseed. All 480 PR vectors that have additional input failed. Separately, a failed reseed was logged but generation continued; it now returns the error first.

Impact on the tool: it never uses PR or additional input, so only item 1 affects it. Its output was not weaker, since it is still a hash of the full state; it was just non-conformant. The built-in KAT still passes. Treat this as a behaviour change for the maintainers to judge: 1 in 256 outputs now differ from what v1.3.4 would produce for the same seed.

### B: `lane/junit-native` (fork only)

Commits on top of A:

1. The self-test fix (same change as `fix/selftest-fail-closed`).
2. The HashDRBG fix (same change as `fix/hashdrbg-sp800-90a`).
3. **EntropyUtil chi-squared limit.** `testChiSquared` looked its limit up by chunk *bits* (for example 9.49 for 4 bits), but the statistic has 2^bits-1 degrees of freedom. With 4-bit chunks, good DRBG output passed only 14 times in 100. The limit now uses the Wilson-Hilferty approximation at the correct degrees of freedom. Only a disabled debug block calls this, so tool behaviour is unchanged. The class's own test main also printed a stale statistic; that is fixed too.
4. **Folds in PR #3 and PR #2, with credit (`Co-authored-by`).**
   - PR #3 (AndrewSill): `-rrcc N` randomly upcases the last N letters of each passphrase word. The code is as submitted, re-indented.
   - PR #2 (sfc9982): `-un N` and `-unurl U` generate usernames as a word plus 4 random digits. I re-implemented it on 1.3.4 without the PR's 1800-line reformat.

   Neither option is counted toward strength. Both are documented in README.md and README.txt.
5. **JUnit 5 suite, Maven build and native image.** `pom.xml` reuses `src/` and `data/wordlist.txt`, so `ant jar` and plain javac are untouched (A's job keeps checking them). It adds 2928 tests:
   - **HashDRBGCavpTest:** all 2880 NIST CAVP Hash_DRBG vectors for SHA-1/256/384/512, across the no-reseed, PR=false and PR=true files. The vectors are stored gzipped (985 KB) with provenance and the zip's sha256 in `test/resources/cavp/README.txt`. Personalization strings are passed as nonce‖pers because the API takes a `String`; the seed material is identical.
   - **HashDRBGTest:** the built-in KAT and self-test, strength-to-hash mapping, error paths, and a "failed PR reseed produces no output" regression test.
   - **EntropyUtilTest, CharacterSetTest, WordSetTest, HexKeyGenTest:**
     - entropy-per-item values and length = ceil(strength/bits), with minimality checked;
     - charset membership, dedup, and the default set omitting O, 0, 1 and l;
     - the 384-bit strength cap;
     - the bundled wordlist being all distinct lowercase words of 3 or more letters;
     - the -rcc and -rrcc positions and their 1/2 probability.
   - **Chi-squared uniformity checks:** on bytes, the 64-character default set, digits, 50 custom words and hex digits. One more check confirms that CharacterSet's rejection sampling removes modulo bias, using a 20000-character set where a plain modulo would make 63.8% of draws come out as 77.9%. Every statistical test draws from a HashDRBG seeded with fixed bytes and uses 0.1% limits. Each outcome is fixed, so the tests cannot flake.
   - **RandPassGeneratorTest:** end to end through the real entropy source. Checks password, passphrase, key, Key ID and username formats, chunking, custom charset files, and the strength and count limits.
   - **Native executable:** `native/` holds the GraalVM reachability metadata that keeps `wordlist.txt` in the binary. The workflow adds a JUnit job on JDK 17, 21 and 25, and a GraalVM CE 25 job. That job builds `randpassgen`, times it against the jar, smoke-tests `-k`, `-pw`, `-pp -rcc -rrcc` and `-un`, checks that two runs print different keys (so seeding happens at run time, not at image build), and uploads the binary as the `randpassgen-linux-x64` artifact.

Left alone (noted only):
- `WordSet.getRandomWord` has dead branches for `intsize` 3 and 2, because `rlimit` is always at least 2^30.
- BUILD.txt still says "Oracle Java SDK 1.9" and "src.zip".
- README.md still says 1.3.3.

## 3. Evidence

**A on GitHub Actions** (run 44245862628): all 3 jobs green.

```
✓ JDK 17 in 14s   ✓ JDK 21 in 17s   ✓ JDK 25 in 11s
JDK 25 | BUILD SUCCESSFUL
JDK 25 | ##[group]drbg.HashDRBG (exit 0) ... ##[group]pass.RandManager (exit 0)   (9 self-test mains)
JDK 25 | Generated 7 keys at strength 128 / Generated 5 passwords at strength 160 / Generated 3 passphrases at strength 128
```

The same steps replayed locally under WSL (Temurin 17.0.21, 21.0.13 and 25.0.5, Ant 1.10.15) gave rc=0 for every step. The RandManager test on unmodified upstream, built with JDK 21:

```
$ java -cp build gov.nsa.ia.pass.RandManager /tmp/neg/s.dat ; echo rc=$?
KGM self-test passed!
Key generate failed on bulk test iteration 0
upstream RandManager rc=1
```

**Self-test fault injection.** I edited `RandManager.performSelfTest()` to `if (true) return false;` in a /tmp copy only, then ran `RandPassGenerator -pw 2 -log none`:

```
== upstream: exit=0, passwords printed=2
Randomness self-test failed.  Exiting.
== fixed: exit=6, passwords printed=0
Randomness self-test failed.  Exiting.
Could not initialize RandPassGenerator: java.lang.RuntimeException: Error in RandPassGen startup.
```

**CAVP before the HashDRBG fix** (`mvn test -Dtest=HashDRBGCavpTest` against upstream HashDRBG). Each line shows failures out of 60 vectors in that group; groups with no failures are omitted.

```
Tests run: 2880, Failures: 494
   1/60  noReseed SHA-1 PR=False P=0 A=0
   1/60  noReseed SHA-256 PR=False P=0 A=0            (14 failures in 13 groups across all modes: dropped leading zero of V)
   2/60  predictionResistanceFalse SHA-256 PR=False P=256 A=256
  60/60  predictionResistanceTrue SHA-1 PR=True P=0 A=128
  60/60  predictionResistanceTrue SHA-256 PR=True P=256 A=256   (8 groups x 60 = 480: additional input used twice)
```

After the fix: `Tests run: 2880, Failures: 0`. The built-in KAT still prints `performKnownAnswerTests result: true` on both fix branches, which I built and ran from `git archive` of each branch.

**The new tests run against upstream sources** (drbg and gen tests only, since PR #2/#3 APIs don't exist upstream):

```
EntropyUtilTest.goodRandomDataPassesFourBitChiSquaredTest  FAIL  passed 14 of 100
EntropyUtilTest.chiSquaredLimitUsesTwoToTheBitsMinusOneDegreesOfFreedom  FAIL
HashDRBGTest.failedPredictionResistanceReseedProducesNoOutput  FAIL
CharacterSetTest 9/9, HexKeyGenTest 4/4, WordSetTest 7/7 pass (upstream selection code is sound)
```

**B, local** (`mvn -B -ntp verify`, Temurin 25.0.5, Maven 3.10.1):

```
Tests run: 10   RandPassGeneratorTest      Tests run: 2880 HashDRBGCavpTest
Tests run: 7    EntropyUtilTest            Tests run: 10   HashDRBGTest
Tests run: 9    CharacterSetTest           Tests run: 4    HexKeyGenTest
Tests run: 8    WordSetTest
Tests run: 2928, Failures: 0, Errors: 0, Skipped: 0
```

**B on GitHub Actions** (run 44247795839): all 7 jobs green. These are Ant self-tests on JDK 17/21/25, JUnit on JDK 17/21/25 (`Tests run: 2928, Failures: 0`), and GraalVM native image.

```
Java version: 25.0.2+10, vendor version: GraalVM CE 25.0.2+10.1
Finished generating 'randpassgen' in 59.3s.
binary size: 18221312 bytes
randpassgen: ELF 64-bit LSB pie executable, x86-64, dynamically linked, stripped
jvm -pw 1: 255 ms, max RSS 126956 KB       native -pw 1: 15 ms, max RSS 21636 KB
jvm -pw 1: 213 ms, max RSS 127468 KB       native -pw 1: 16 ms, max RSS 21568 KB
jvm -pw 1: 212 ms, max RSS 129484 KB       native -pw 1: 16 ms, max RSS 21760 KB
```

The native binary is 17.4 MiB. One password, including the startup KAT and self-tests, takes about 15 ms and 21 MB RSS, against about 215 ms and 127 MB on the JVM (GitHub ubuntu-latest runner).

I did not build the native image locally: the shared WSL had about 450 MB free. `-enc` and `-decrypt` (AES key wrap and PBKDF2, interactive prompts) were not tested in the native binary.

## 4. Competing work check

`gh pr list -R nsacyber/RandPassGenerator --state all`, run at the start and again before finishing, shows:
- **#1** nealziring, MERGED.
- **#2** sfc9982, OPEN.
- **#3** AndrewSill, OPEN.
- **#5** jerdog, CLOSED ("wrong repo", a Python port).

No AI-PR spray accounts appear. `gh issue list --state all` is empty, and searching for "DRBG" or "self-test" finds nothing.

- Nothing upstream covers CI, the self-test fail-open, or the Hash_DRBG conformance bugs, so A and both fix branches duplicate nothing.
- #2 and #3 are folded into B only, with `Co-authored-by` credit. They are not in any upstream-bound branch.
- **Recon correction:** nealziring's "Very cool feature!" comment is on **#2** (usernames), not #3.

## 5. Draft upstream text

### PR for `fix/ci-build`

**Title:** Add GitHub Actions build and self-test workflow

**Body:**

> The repository has no CI. This adds one workflow that runs on JDK 17, 21 and 25 (Temurin) on push and pull request. It:
>
> - builds with `ant jar` (BUILD.txt step 4);
> - runs the self-test `main()` methods listed under UNIT TESTING in BUILD.txt. Most of them exit 0 even when a check fails, so the workflow also requires each one to print its success line;
> - runs BUILD.txt step 5 (`-v -str 128 -k 7`), plus `-pw 5` and `-pp 3`, and checks the output shape.
>
> It also fixes one test that never passed: `RandManager.main()` asks for 32-byte keys from a DRBG instantiated at 192 bits (24 bytes). `generateKey()` correctly refuses, so `java gov.nsa.ia.pass.RandManager <file>` always stopped with "Key generate failed on bulk test iteration 0" and exit status 1. `TEST_KEY_SIZE` is now `DRBG_STRENGTH / 8`. Only the test main uses it, so no generation code changes.
>
> Green run on my fork: https://github.com/Dukotah/RandPassGenerator/actions/runs/44245862628
>
> I'm not a U.S. government employee; I agree to the CC0 waiver in CONTRIBUTING.md.
>
> 🤖 Generated with [Claude Code](https://claude.com/claude-code)

### PR for `fix/selftest-fail-closed`

**Title:** Stop when the startup self-test or initialization fails

**Body:**

> README.txt says that if the self-tests don't pass, "the tool reports failure and refuses to run". In `RandPassGenerator`'s constructor, a known-answer-test failure does exit. But when `RandManager.performSelfTest()` returns false, the tool prints "Randomness self-test failed.  Exiting." and then keeps going: passwords and keys are still generated and the exit status is 0. A false return from `RandManager.initialize()` isn't reported at all.
>
> This sets the existing `die` flag in both cases. `main()` then exits 6 ("Could not initialize RandPassGenerator"), the same path used for exceptions during setup.
>
> How I checked it: I temporarily made `performSelfTest()` return false and ran `-pw 2`. Before this change it printed 2 passwords and exited 0. After, it prints none and exits 6.
>
> I'm not a U.S. government employee; I agree to the CC0 waiver in CONTRIBUTING.md.
>
> 🤖 Generated with [Claude Code](https://claude.com/claude-code)

### PR for `fix/hashdrbg-sp800-90a`

**Title:** Make HashDRBG match SP 800-90A (NIST CAVP Hash_DRBG vectors)

**Body:**

> I ran HashDRBG against the NIST CAVP Hash_DRBG response files (CAVS 14.3, `drbgtestvectors.zip`, the SHA-1/256/384/512 sections that HashDRBG can select). 494 of 2880 vectors failed, for two reasons:
>
> 1. **V lost its leading zero bytes.** Generate steps 2 and 5, Hashgen and reseed hash `pbi2ba(V)`, which drops leading zero bytes. SP 800-90A hashes V as a seedlen-bit string. Whenever the top byte of V (or V+i in Hashgen) is zero, about one operation in 256, the output differs from the specification. 14 vectors failed this way. V is now always encoded as `seedlen/8` bytes.
> 2. **Additional input was applied twice under prediction resistance.** With prediction resistance, generate reseeds with `additionalInput` and then hashes it into V again in step 2. Section 9.3.1 step 7.4 sets additional_input to Null after the reseed. All 480 PR vectors with additional input failed. Also, if that reseed failed, generate logged it and still produced output; it now returns the error (step 7.2).
>
> With this change all 2880 vectors pass, and `performKnownAnswerTests` still passes.
>
> RandPassGenerator itself never requests prediction resistance or passes additional input, so only (1) affects it. Its output was still a hash of the full DRBG state, so this is a conformance fix, not an entropy fix. For the same seed, about 1 output in 256 will differ from v1.3.4.
>
> The test harness (JUnit 5 + the vector files) is on my fork if you'd like it: https://github.com/Dukotah/RandPassGenerator/tree/lane/junit-native/RandPassGenerator/test
>
> I'm not a U.S. government employee; I agree to the CC0 waiver in CONTRIBUTING.md.
>
> 🤖 Generated with [Claude Code](https://claude.com/claude-code)

### Issue text, if the owner prefers to report before sending code

**Title:** HashDRBG fails NIST CAVP Hash_DRBG vectors; self-test failure does not stop the tool

**Body:**

> Two findings from testing 1.3.4:
>
> 1. **The self-test failure path is fail-open.** If `RandManager.performSelfTest()` returns false, `RandPassGenerator` prints "Randomness self-test failed.  Exiting." but continues and generates output, with exit status 0. README.txt says it refuses to run.
> 2. **HashDRBG fails 494 of 2880 NIST CAVP Hash_DRBG vectors** (SHA-1/256/384/512):
>    - 14 fail because V is hashed without its leading zero bytes (`pbi2ba`), which happens about once in 256 operations;
>    - 480 fail because, with prediction resistance, the additional input is used in the reseed and again in generate step 2 (SP 800-90A 9.3.1 step 7.4).
>
> Patches and a JUnit harness: https://github.com/Dukotah/RandPassGenerator (branches `fix/selftest-fail-closed`, `fix/hashdrbg-sp800-90a`, `lane/junit-native`).

## 6. Land probability and next steps

- **A, `fix/ci-build`: Low-Med.** The PR is small, costs nothing to review and changes no code, but no outside PR has ever been merged, and the repo has been quiet since a direct push in Dec 2023.
- **`fix/selftest-fail-closed`: Med-Low.** This is the strongest of the three: a 5-line fix to a documented safety promise. Repo inactivity is still the limit.
- **`fix/hashdrbg-sp800-90a`: Low-Med.** The comments show the original reviewers asked for NIST-vector validation, so the evidence should land well. But it changes DRBG output for about 1 in 256 operations, which a conservative maintainer may want to re-review internally.
- **B:** fork-only by design. Green.

What the owner should do next:
1. Decide whether to open these upstream. If so, send the self-test fix first, either as a PR or as the issue text, and mention @nealziring, the only person who has ever merged a PR there.
2. Send A and the HashDRBG fix as separate PRs. Do not bundle them.
3. If wanted, open a fork release attaching the `randpassgen-linux-x64` artifact from the latest `lane/junit-native` run.
