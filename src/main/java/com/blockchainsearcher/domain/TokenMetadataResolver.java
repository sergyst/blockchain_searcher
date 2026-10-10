package com.blockchainsearcher.domain;

import java.util.Optional;

@FunctionalInterface
public interface TokenMetadataResolver {

    /** @param tokenAddress lower-case 0x address */
    Optional<TokenMetadata> resolve(String tokenAddress);

    static TokenMetadataResolver none() {
        return token -> Optional.empty();
    }
}
