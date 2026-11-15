package com.agentpaygate.policy;

import java.security.PublicKey;
import java.util.Map;

/** Lookup of issuer public keys by key id. Supplied by the caller so the engine never does I/O. */
@FunctionalInterface
public interface IssuerKeys {

    /** Returns the registered key for {@code keyId}, or {@code null} when unknown. */
    PublicKey publicKeyFor(String keyId);

    static IssuerKeys none() {
        return keyId -> null;
    }

    /** A fixed registry, copied so later mutation of the caller's map cannot change decisions. */
    static IssuerKeys of(Map<String, PublicKey> keys) {
        Map<String, PublicKey> copy = Map.copyOf(keys);
        return copy::get;
    }
}
