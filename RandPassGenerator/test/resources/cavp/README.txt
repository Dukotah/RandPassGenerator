NIST CAVP test vectors for SP 800-90A Hash_DRBG, used by
test/java/gov/nsa/ia/drbg/HashDRBGCavpTest.java.

Source: https://csrc.nist.gov/CSRC/media/Projects/Cryptographic-Algorithm-Validation-Program/documents/drbg/drbgtestvectors.zip
  sha256 5f7e5658ebd5b4e6785a7b12fa32333511d2acc2f2d9c5ae1ffa16b699377769 (downloaded 2026-11-17)
  CAVS 14.3 response files, generated 2013-04-02

  Hash_DRBG_no_reseed.rsp.gz  from drbgvectors_no_reseed.zip/Hash_DRBG.rsp
  Hash_DRBG_pr_false.rsp.gz   from drbgvectors_pr_false.zip/Hash_DRBG.rsp
  Hash_DRBG_pr_true.rsp.gz    from drbgvectors_pr_true.zip/Hash_DRBG.rsp

Only the [SHA-1], [SHA-256], [SHA-384] and [SHA-512] sections are kept,
because HashDRBG picks one of those four hashes from the requested
strength. Nothing else in the files was changed. 960 vectors per file,
2880 in total.
