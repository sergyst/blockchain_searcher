package com.blockchainsearcher.service;

import com.blockchainsearcher.abi.AbiDecoder;
import com.blockchainsearcher.domain.TokenMetadata;
import com.blockchainsearcher.domain.TokenMetadataResolver;
import com.blockchainsearcher.rpc.JsonRpcClient;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Reads ERC-20 {@code symbol()} / {@code decimals()} over eth_call, cached per token. */
@Slf4j
public class TokenMetadataService implements TokenMetadataResolver {

    private static final String DECIMALS_SELECTOR = "0x313ce567";
    private static final String SYMBOL_SELECTOR = "0x95d89b41";

    private final JsonRpcClient rpc;
    private final Map<String, Optional<TokenMetadata>> cache = new ConcurrentHashMap<>();

    public TokenMetadataService(JsonRpcClient rpc) {
        this.rpc = rpc;
    }

    @Override
    public Optional<TokenMetadata> resolve(String tokenAddress) {
        return cache.computeIfAbsent(tokenAddress, this::load);
    }

    private Optional<TokenMetadata> load(String token) {
        Integer decimals = null;
        String symbol = null;
        try {
            BigInteger raw = AbiDecoder.decodeUintReturn(rpc.ethCall(token, DECIMALS_SELECTOR));
            if (raw != null && raw.bitLength() <= 8) {
                decimals = raw.intValue();
            }
        } catch (RuntimeException e) {
            log.debug("decimals() failed for {}: {}", token, e.getMessage());
        }
        try {
            symbol = AbiDecoder.decodeStringReturn(rpc.ethCall(token, SYMBOL_SELECTOR));
        } catch (RuntimeException e) {
            log.debug("symbol() failed for {}: {}", token, e.getMessage());
        }
        return decimals == null && symbol == null ? Optional.empty() : Optional.of(new TokenMetadata(symbol, decimals));
    }
}
