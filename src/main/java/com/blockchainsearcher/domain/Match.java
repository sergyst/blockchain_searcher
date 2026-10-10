package com.blockchainsearcher.domain;

import java.util.Map;

/**
 * One piece of evidence that the address is involved in the transaction.
 * Numeric values in {@code details} are strings so large integers survive JSON clients.
 *
 * @param logIndex null for matches not tied to a log
 * @param contract the contract the evidence comes from (log emitter / call target), checksummed
 * @param event    decoded event signature, when known
 * @param source   "rpc", "rpc-trace" or "blockscout"
 */
public record Match(
        MatchType type,
        String description,
        Integer logIndex,
        String contract,
        String event,
        String source,
        Map<String, Object> details) {
}
