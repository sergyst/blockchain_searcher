package com.blockchainsearcher.blockscout;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** Subset of the Blockscout API v2 response shapes this app reads. */
public final class BlockscoutModels {

    private BlockscoutModels() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Page<T>(List<T> items, @JsonProperty("next_page_params") Map<String, Object> nextPageParams) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AddressRef(String hash, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Log(Integer index, AddressRef address, List<String> topics, String data, Decoded decoded) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Decoded(@JsonProperty("method_call") String methodCall,
                          @JsonProperty("method_id") String methodId,
                          List<Parameter> parameters) {
    }

    /** {@code value} is a string for scalars and a list for arrays/tuples. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Parameter(String name, String type, Boolean indexed, Object value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InternalTransaction(Integer index, String type, AddressRef from, AddressRef to, String value,
                                      Boolean success, String error,
                                      @JsonProperty("created_contract") AddressRef createdContract) {
    }
}
