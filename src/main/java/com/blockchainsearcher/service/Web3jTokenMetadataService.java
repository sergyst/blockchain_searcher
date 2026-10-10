package com.blockchainsearcher.service;

import com.blockchainsearcher.abi.AbiDecoder;
import com.blockchainsearcher.domain.TokenMetadata;
import com.blockchainsearcher.domain.TokenMetadataResolver;
import lombok.extern.slf4j.Slf4j;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;

import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** ERC-20 {@code symbol()} / {@code decimals()} via Web3j eth_call, cached per token. */
@Slf4j
public class Web3jTokenMetadataService implements TokenMetadataResolver {

    private static final String DECIMALS_SELECTOR = "0x313ce567";
    private static final String SYMBOL_SELECTOR = "0x95d89b41";

    private final Web3j web3j;
    private final Map<String, Optional<TokenMetadata>> cache = new ConcurrentHashMap<>();

    public Web3jTokenMetadataService(Web3j web3j) {
        this.web3j = web3j;
    }

    @Override
    public Optional<TokenMetadata> resolve(String tokenAddress) {
        return cache.computeIfAbsent(tokenAddress, this::load);
    }

    private Optional<TokenMetadata> load(String token) {
        Integer decimals = null;
        String symbol = null;
        try {
            BigInteger raw = AbiDecoder.decodeUintReturn(call(token, DECIMALS_SELECTOR));
            if (raw != null && raw.bitLength() <= 8) {
                decimals = raw.intValue();
            }
        } catch (Exception e) {
            log.debug("decimals() failed for {}: {}", token, e.getMessage());
        }
        try {
            symbol = AbiDecoder.decodeStringReturn(call(token, SYMBOL_SELECTOR));
        } catch (Exception e) {
            log.debug("symbol() failed for {}: {}", token, e.getMessage());
        }
        return decimals == null && symbol == null ? Optional.empty() : Optional.of(new TokenMetadata(symbol, decimals));
    }

    private String call(String token, String selector) throws Exception {
        EthCall response = web3j.ethCall(Transaction.createEthCallTransaction(null, token, selector),
                DefaultBlockParameterName.LATEST).send();
        if (response.hasError()) {
            throw new IllegalStateException(response.getError().getMessage());
        }
        return response.getValue();
    }
}
