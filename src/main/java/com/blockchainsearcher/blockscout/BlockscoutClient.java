package com.blockchainsearcher.blockscout;

import com.blockchainsearcher.blockscout.BlockscoutModels.InternalTransaction;
import com.blockchainsearcher.blockscout.BlockscoutModels.Log;
import com.blockchainsearcher.blockscout.BlockscoutModels.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Blockscout API v2 client, used only for optional enrichment. */
@Slf4j
public class BlockscoutClient {

    private static final ParameterizedTypeReference<Page<Log>> LOG_PAGE = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<Page<InternalTransaction>> INTERNAL_PAGE =
            new ParameterizedTypeReference<>() {
            };

    private final String network;
    private final RestClient restClient;
    private final int maxPages;

    public BlockscoutClient(String network, RestClient restClient, int maxPages) {
        this.network = network;
        this.restClient = restClient;
        this.maxPages = maxPages;
    }

    public List<Log> getLogs(String txHash) {
        return fetchAll("/api/v2/transactions/" + txHash + "/logs", LOG_PAGE);
    }

    public List<InternalTransaction> getInternalTransactions(String txHash) {
        return fetchAll("/api/v2/transactions/" + txHash + "/internal-transactions", INTERNAL_PAGE);
    }

    private <T> List<T> fetchAll(String path, ParameterizedTypeReference<Page<T>> type) {
        List<T> all = new ArrayList<>();
        Map<String, Object> next = Map.of();
        for (int page = 0; page < maxPages; page++) {
            Map<String, Object> query = next;
            Page<T> body;
            try {
                body = restClient.get()
                        .uri(builder -> {
                            builder.path(path);
                            query.forEach((key, value) -> builder.queryParam(key, String.valueOf(value)));
                            return builder.build();
                        })
                        .retrieve()
                        .body(type);
            } catch (RestClientException e) {
                throw new BlockscoutException("GET " + path + " failed: " + e.getMessage(), e);
            }
            if (body == null || body.items() == null) {
                return all;
            }
            all.addAll(body.items());
            if (body.nextPageParams() == null || body.nextPageParams().isEmpty()) {
                return all;
            }
            next = body.nextPageParams();
        }
        log.warn("[{}] {} has more than {} pages; using the first {} items", network, path, maxPages, all.size());
        return all;
    }
}
