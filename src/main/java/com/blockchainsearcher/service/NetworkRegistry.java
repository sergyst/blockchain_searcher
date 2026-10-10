package com.blockchainsearcher.service;

import com.blockchainsearcher.blockscout.BlockscoutClient;
import com.blockchainsearcher.config.BlockchainProperties;
import com.blockchainsearcher.config.BlockchainProperties.NetworkProperties;
import com.blockchainsearcher.error.UnknownNetworkException;
import com.blockchainsearcher.rpc.JsonRpcClient;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

import java.net.http.HttpClient;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Builds one set of clients per network in application.yml at startup. */
@Slf4j
@Component
public class NetworkRegistry implements DisposableBean {

    private final BlockchainProperties properties;
    private final Map<String, ChainClients> networks = new LinkedHashMap<>();

    public NetworkRegistry(BlockchainProperties properties) {
        this.properties = properties;
        JdkClientHttpRequestFactory requestFactory = requestFactory(properties.http());
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(properties.http().connectTimeout())
                .readTimeout(properties.http().readTimeout())
                .build();
        properties.networks().forEach((name, network) -> {
            String key = name.toLowerCase(Locale.ROOT);
            JsonRpcClient rpc = new JsonRpcClient(key, restClient(requestFactory, network.rpcUrl()));
            BlockscoutClient blockscout = network.hasBlockscout()
                    ? new BlockscoutClient(key, restClient(requestFactory, network.blockscoutUrl()),
                    properties.blockscoutMaxPages())
                    : null;
            Web3j web3j = Web3j.build(new HttpService(network.rpcUrl(), okHttpClient));
            networks.put(key, new ChainClients(key, network, rpc, blockscout, new TokenMetadataService(rpc),
                    web3j, new Web3jTokenMetadataService(web3j)));
            log.info("Network '{}' (chainId={}): rpc={}, blockscout={}, trace={}", key, network.chainId(),
                    redact(network.rpcUrl()), network.hasBlockscout() ? network.blockscoutUrl() : "off",
                    network.traceEnabled());
        });
    }

    /** @param name network name; null or blank selects the default network */
    public ChainClients get(String name) {
        String key = (name == null || name.isBlank() ? properties.defaultNetwork() : name).toLowerCase(Locale.ROOT);
        ChainClients clients = networks.get(key);
        if (clients == null) {
            throw new UnknownNetworkException(key, networks.keySet());
        }
        return clients;
    }

    public Map<String, NetworkProperties> configured() {
        Map<String, NetworkProperties> out = new LinkedHashMap<>();
        networks.forEach((key, clients) -> out.put(key, clients.properties()));
        return Collections.unmodifiableMap(out);
    }

    public String defaultNetwork() {
        return properties.defaultNetwork().toLowerCase(Locale.ROOT);
    }

    @Override
    public void destroy() {
        networks.values().forEach(clients -> clients.web3j().shutdown());
    }

    private static JdkClientHttpRequestFactory requestFactory(BlockchainProperties.HttpProperties http) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(http.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(http.readTimeout());
        return factory;
    }

    private static RestClient restClient(JdkClientHttpRequestFactory requestFactory, String baseUrl) {
        return RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, "blockchain-searcher/0.1")
                .build();
    }

    /** RPC URLs often embed API keys in the path; keep them out of logs. */
    private static String redact(String url) {
        int schemeEnd = url.indexOf("://");
        int pathStart = schemeEnd < 0 ? -1 : url.indexOf('/', schemeEnd + 3);
        return pathStart < 0 ? url : url.substring(0, pathStart) + "/***";
    }
}
