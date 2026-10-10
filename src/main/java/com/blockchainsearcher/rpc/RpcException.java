package com.blockchainsearcher.rpc;

import lombok.Getter;

/** A JSON-RPC call failed, either in transport or with a JSON-RPC error object. */
@Getter
public class RpcException extends RuntimeException {

    private final String method;
    private final Integer rpcErrorCode;

    public RpcException(String method, String message, Integer rpcErrorCode, Throwable cause) {
        super(method + ": " + message, cause);
        this.method = method;
        this.rpcErrorCode = rpcErrorCode;
    }
}
