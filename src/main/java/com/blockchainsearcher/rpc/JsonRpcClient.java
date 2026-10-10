package com.blockchainsearcher.rpc;

import com.blockchainsearcher.rpc.RpcModels.CallFrame;
import com.blockchainsearcher.rpc.RpcModels.RpcBlock;
import com.blockchainsearcher.rpc.RpcModels.RpcReceipt;
import com.blockchainsearcher.rpc.RpcModels.RpcRequest;
import com.blockchainsearcher.rpc.RpcModels.RpcResponse;
import com.blockchainsearcher.rpc.RpcModels.RpcTransaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Thin Ethereum JSON-RPC client over Spring's {@link RestClient}. */
@Slf4j
public class JsonRpcClient {

    private static final ParameterizedTypeReference<RpcResponse<RpcTransaction>> TX_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<RpcResponse<RpcReceipt>> RECEIPT_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<RpcResponse<RpcBlock>> BLOCK_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<RpcResponse<CallFrame>> TRACE_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<RpcResponse<String>> STRING_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final String network;
    private final RestClient restClient;
    private final AtomicLong ids = new AtomicLong();

    public JsonRpcClient(String network, RestClient restClient) {
        this.network = network;
        this.restClient = restClient;
    }

    /** @return the transaction, or null if the node does not know it */
    public RpcTransaction getTransactionByHash(String hash) {
        return call("eth_getTransactionByHash", List.of(hash), TX_TYPE);
    }

    /** @return the receipt, or null while the transaction is pending */
    public RpcReceipt getTransactionReceipt(String hash) {
        return call("eth_getTransactionReceipt", List.of(hash), RECEIPT_TYPE);
    }

    public RpcBlock getBlockByNumber(String blockNumberHex) {
        return call("eth_getBlockByNumber", List.of(blockNumberHex, false), BLOCK_TYPE);
    }

    /** Requires a node that exposes the debug namespace with the built-in callTracer. */
    public CallFrame traceTransaction(String hash) {
        return call("debug_traceTransaction", List.of(hash, Map.of("tracer", "callTracer")), TRACE_TYPE);
    }

    /** {@code eth_call} against the latest block; returns the raw 0x return data. */
    public String ethCall(String to, String data) {
        return call("eth_call", List.of(Map.of("to", to, "data", data), "latest"), STRING_TYPE);
    }

    private <T> T call(String method, List<?> params, ParameterizedTypeReference<RpcResponse<T>> type) {
        RpcRequest request = new RpcRequest("2.0", ids.incrementAndGet(), method, params);
        log.debug("[{}] -> {} {}", network, method, params);
        RpcResponse<T> response;
        try {
            response = restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(type);
        } catch (RestClientException e) {
            throw new RpcException(method, "transport failure: " + e.getMessage(), null, e);
        }
        if (response == null) {
            throw new RpcException(method, "empty response body", null, null);
        }
        if (response.error() != null) {
            throw new RpcException(method, response.error().message(), response.error().code(), null);
        }
        return response.result();
    }
}
