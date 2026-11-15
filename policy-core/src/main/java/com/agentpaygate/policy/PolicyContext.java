package com.agentpaygate.policy;

/**
 * Everything the policy engine needs besides the request: the evaluation instant, the issuer key
 * registry and whether the nonce has been seen before. Passing them explicitly keeps
 * {@link PolicyEngine} a pure function of its inputs.
 */
public record PolicyContext(long nowEpochSecond, IssuerKeys issuerKeys, boolean nonceAlreadyUsed) {

    public PolicyContext {
        if (issuerKeys == null) {
            throw new IllegalArgumentException("issuerKeys is required");
        }
    }

    public static PolicyContext at(long nowEpochSecond, IssuerKeys issuerKeys) {
        return new PolicyContext(nowEpochSecond, issuerKeys, false);
    }

    public PolicyContext withNonceAlreadyUsed(boolean alreadyUsed) {
        return new PolicyContext(nowEpochSecond, issuerKeys, alreadyUsed);
    }
}
