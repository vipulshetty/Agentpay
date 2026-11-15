# ADR-002 — Ed25519 and the canonical mandate form

**Status:** accepted
**Date:** 2026-11-15

## Context

Mandates must be signed by a human issuer key and verified outside the model. The spec allows
ECDSA P-256 or Ed25519 via the JDK, and requires a documented deterministic canonical form.
The mandate format is AP2-inspired; it is **not** AP2-compliant and nothing here claims otherwise.

## Decision

- **Algorithm: Ed25519** (`Signature.getInstance("Ed25519")`, JDK 21, no third-party crypto).
  Chosen over ECDSA P-256 because Ed25519 signatures are deterministic: signing the same mandate
  with the same key twice yields identical signature bytes, which keeps demo fixtures, golden
  files and audit records reproducible. ECDSA in the JDK picks a random `k` per signature, so the
  bytes differ every run even though verification is stable.
- **Canonical form:** a line-based encoding with a version tag, fixed field order, sorted
  allowlists and no optional fields (`CanonicalForm`). Ids reject `|` and newlines at
  construction so values cannot smuggle separators.
- Signature bytes are base64url without padding.
- Key generation lives in `mandate-cli`, not in `policy-core`, because `KeyPairGenerator` needs
  `SecureRandom` and the pure core forbids it.
- Verification never throws on untrusted input: wrong key type, bad base64 and bad signature all
  return `false`, which the engine maps to `BAD_SIGNATURE`.

## Consequences

The gateway stores issuer public keys by `issuerKeyId` and verifies with the same
`MandateSignature.verify` code path, so there is one implementation of the rule. A tampered
mandate fails because the signature covers every field.
