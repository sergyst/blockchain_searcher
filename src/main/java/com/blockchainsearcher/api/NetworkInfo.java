package com.blockchainsearcher.api;

public record NetworkInfo(String name, Long chainId, boolean isDefault, boolean blockscoutEnrichment,
                          boolean traceEnabled) {
}
