package com.blockchainsearcher.service;

import com.blockchainsearcher.abi.Hex;
import com.blockchainsearcher.error.UpstreamServiceException;

/**
 * Handling for a node that knows a mined transaction but answers {@code null} to eth_getTransactionReceipt.
 * That happens with load-balanced public RPCs whose backends are out of sync. Without a receipt there are
 * no logs, so silently treating the tx as pending would wrongly report "not involved".
 */
final class ReceiptRetry {

    static final int ATTEMPTS = 3;
    private static final long BASE_DELAY_MS = 500;

    private ReceiptRetry() {
    }

    static void pause(int attempt) {
        try {
            Thread.sleep(BASE_DELAY_MS * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static UpstreamServiceException missingReceipt(String network, String hash, String blockNumberHex) {
        UpstreamServiceException ex = new UpstreamServiceException(network,
                "The node returned transaction " + hash + " as mined in block " + Hex.quantity(blockNumberHex)
                        + " but no receipt after " + (ATTEMPTS + 1) + " attempts, so its logs cannot be checked."
                        + " The RPC endpoint is likely lagging or load-balanced; set a different rpc-url"
                        + " (e.g. SEPOLIA_RPC_URL) and try again.", null);
        ex.getBody().setProperty("txHash", hash);
        ex.getBody().setProperty("blockNumber", Hex.quantity(blockNumberHex).longValueExact());
        return ex;
    }
}
