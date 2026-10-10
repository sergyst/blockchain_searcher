package com.blockchainsearcher.error;

import org.springframework.http.HttpStatus;

import java.util.Collection;
import java.util.List;

public class UnknownNetworkException extends ApiException {

    public UnknownNetworkException(String network, Collection<String> configured) {
        super(HttpStatus.BAD_REQUEST, "unknown-network", "Unknown network",
                "Network '" + network + "' is not configured", null);
        getBody().setProperty("network", network);
        getBody().setProperty("configuredNetworks", List.copyOf(configured));
    }
}
