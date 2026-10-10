package com.blockchainsearcher.service;

import com.blockchainsearcher.abi.Hex;
import com.blockchainsearcher.api.InvolvementResponse;
import com.blockchainsearcher.api.InvolvementResponse.DataSources;
import com.blockchainsearcher.api.InvolvementResponse.TransactionSummary;
import com.blockchainsearcher.domain.Match;
import com.blockchainsearcher.domain.MatchType;
import com.blockchainsearcher.domain.TxContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Builds the API response the same way for every data-access path (RestClient or Web3j). */
final class InvolvementResponses {

    private InvolvementResponses() {
    }

    static InvolvementResponse of(ChainClients chain, TxContext tx, String target, int logCount,
                                  List<Match> matches, DataSources sources, List<String> warnings) {
        Map<MatchType, Long> summary = matches.stream()
                .collect(Collectors.groupingBy(Match::type, LinkedHashMap::new, Collectors.counting()));
        return new InvolvementResponse(
                chain.name(),
                chain.properties().chainId(),
                tx.hash(),
                Hex.checksum(target),
                !matches.isEmpty(),
                new TransactionSummary(
                        tx.status().name(),
                        tx.blockNumber(),
                        tx.timestamp() == null ? null : tx.timestamp().toString(),
                        Hex.checksum(tx.from()),
                        Hex.checksum(tx.to()),
                        String.valueOf(tx.valueWei()),
                        methodId(tx.input()),
                        logCount),
                summary,
                matches,
                sources,
                warnings);
    }

    static String methodId(String input) {
        String hex = Hex.strip0x(input);
        return hex.length() >= 8 ? "0x" + hex.substring(0, 8).toLowerCase(Locale.ROOT) : null;
    }
}
