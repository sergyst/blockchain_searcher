package com.blockchainsearcher.domain;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Source-independent view of a transaction, assembled from JSON-RPC (source of truth)
 * plus optional enrichment. Addresses are lower-case and 0x-prefixed.
 *
 * @param internalCalls        null when no internal-call source was available
 * @param internalCallsSource  "rpc-trace", "blockscout" or null
 * @param externalDecodings    explorer-decoded events by log index, used only for logs
 *                             the local {@code EventRegistry} cannot decode
 */
public record TxContext(
        String hash,
        TxStatus status,
        String from,
        String to,
        String input,
        BigInteger valueWei,
        Long blockNumber,
        Instant timestamp,
        String createdContract,
        List<LogEntry> logs,
        List<InternalCall> internalCalls,
        String internalCallsSource,
        Map<Integer, ExternalEventDecoding> externalDecodings) {

    public enum TxStatus { SUCCESS, FAILED, PENDING }

    public record LogEntry(int logIndex, String address, List<String> topics, String data) {
    }

    /** One internal call/message; {@code traceAddress} is e.g. "0.2.1" or an explorer index. */
    public record InternalCall(String type, String from, String to, BigInteger valueWei, String traceAddress,
                               boolean failed) {
    }

    public record ExternalEventDecoding(String methodCall, List<ExternalParam> params) {
    }

    public record ExternalParam(String name, String type, boolean indexed, String value) {
    }
}
