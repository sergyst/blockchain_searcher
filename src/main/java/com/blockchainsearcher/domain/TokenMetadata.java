package com.blockchainsearcher.domain;

/** ERC-20 metadata read from the token contract; either field may be null if the call failed. */
public record TokenMetadata(String symbol, Integer decimals) {
}
