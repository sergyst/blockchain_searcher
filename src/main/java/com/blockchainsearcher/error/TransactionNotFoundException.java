package com.blockchainsearcher.error;

import org.springframework.http.HttpStatus;

public class TransactionNotFoundException extends ApiException {

    public TransactionNotFoundException(String network, String txHash) {
        super(HttpStatus.NOT_FOUND, "transaction-not-found", "Transaction not found",
                "Transaction " + txHash + " was not found on network '" + network + "'", null);
        getBody().setProperty("network", network);
        getBody().setProperty("txHash", txHash);
    }
}
