package com.blockchainsearcher.service;

import com.blockchainsearcher.blockscout.BlockscoutClient;
import com.blockchainsearcher.config.BlockchainProperties.NetworkProperties;
import com.blockchainsearcher.rpc.JsonRpcClient;
import org.web3j.protocol.Web3j;

/**
 * Everything needed to talk to one configured network. {@code blockscout} is null when not configured.
 * Both {@code rpc} (RestClient) and {@code web3j} point at the same JSON-RPC endpoint.
 */
public record ChainClients(
        String name,
        NetworkProperties properties,
        JsonRpcClient rpc,
        BlockscoutClient blockscout,
        TokenMetadataService tokens,
        Web3j web3j,
        Web3jTokenMetadataService web3jTokens) {
}
