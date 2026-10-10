package com.blockchainsearcher.rpc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** JSON-RPC wire types. Quantities stay as the node's hex strings; conversion happens in the service. */
public final class RpcModels {

    private RpcModels() {
    }

    public record RpcRequest(String jsonrpc, long id, String method, List<?> params) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RpcResponse<T>(String jsonrpc, Object id, T result, RpcError error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RpcError(Integer code, String message, Object data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RpcTransaction(
            String hash,
            String from,
            String to,
            String input,
            String value,
            String nonce,
            String blockNumber,
            String blockHash,
            String transactionIndex,
            String type) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RpcReceipt(
            String transactionHash,
            String status,
            String blockNumber,
            String gasUsed,
            String effectiveGasPrice,
            String contractAddress,
            List<RpcLog> logs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RpcLog(String address, List<String> topics, String data, String logIndex, Boolean removed) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RpcBlock(String number, String hash, String timestamp) {
    }

    /** One frame of the {@code callTracer} output of {@code debug_traceTransaction}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CallFrame(
            String type,
            String from,
            String to,
            String value,
            String error,
            List<CallFrame> calls) {
    }
}
