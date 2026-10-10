package com.blockchainsearcher.error;

import org.springframework.http.HttpStatus;

/** The JSON-RPC node (source of truth) failed, so no reliable answer can be given. */
public class UpstreamServiceException extends ApiException {

    public UpstreamServiceException(String network, String detail, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "upstream-failure", "Upstream node error", detail, cause);
        getBody().setProperty("network", network);
    }
}
