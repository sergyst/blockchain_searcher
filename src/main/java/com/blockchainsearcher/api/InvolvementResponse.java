package com.blockchainsearcher.api;

import com.blockchainsearcher.domain.Match;
import com.blockchainsearcher.domain.MatchType;

import java.util.List;
import java.util.Map;

/**
 * @param involved true when at least one match was found
 * @param summary  number of matches per type, in the order they appear
 * @param sources  which data sources contributed, so a reader knows how complete the answer is
 * @param warnings non-fatal problems (e.g. Blockscout unreachable), meaning some checks were skipped
 */
public record InvolvementResponse(
        String network,
        Long chainId,
        String txHash,
        String address,
        boolean involved,
        TransactionSummary transaction,
        Map<MatchType, Long> summary,
        List<Match> matches,
        DataSources sources,
        List<String> warnings) {

    public record TransactionSummary(
            String status,
            Long blockNumber,
            String timestamp,
            String from,
            String to,
            String valueWei,
            String methodId,
            int logCount) {
    }

    /**
     * @param rpc           always "ok" when a response is returned (RPC is required)
     * @param internalCalls "rpc-trace", "blockscout" or "unavailable"
     * @param blockscout    "ok", "failed" or "not-configured"
     */
    public record DataSources(String rpc, String internalCalls, String blockscout) {
    }
}
