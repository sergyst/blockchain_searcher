package com.blockchainsearcher.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * {@code blockchain.*} settings from application.yml.
 *
 * @param defaultNetwork     network used when the request does not name one
 * @param networks           networks by name (the name is what clients pass as {@code ?network=})
 * @param http               timeouts shared by all upstream clients
 * @param blockscoutMaxPages safety cap when following Blockscout pagination
 * @param extraEvents        additional event signatures to decode locally, e.g.
 *                           {@code "Paid(address indexed payee, uint256 amount)"}
 */
@Validated
@ConfigurationProperties(prefix = "blockchain")
public record BlockchainProperties(
        @NotBlank String defaultNetwork,
        @NotEmpty Map<String, @Valid NetworkProperties> networks,
        @Valid @DefaultValue HttpProperties http,
        @DefaultValue("10") @Min(1) int blockscoutMaxPages,
        @DefaultValue List<String> extraEvents) {

    /**
     * @param chainId       informational, echoed back in responses
     * @param rpcUrl        JSON-RPC endpoint (source of truth, required)
     * @param blockscoutUrl Blockscout base URL, e.g. https://eth-sepolia.blockscout.com (optional enricher)
     * @param traceEnabled  call {@code debug_traceTransaction} for internal calls (needs a node that allows it)
     */
    public record NetworkProperties(
            Long chainId,
            @NotBlank String rpcUrl,
            String blockscoutUrl,
            @DefaultValue("false") boolean traceEnabled) {

        public boolean hasBlockscout() {
            return blockscoutUrl != null && !blockscoutUrl.isBlank();
        }
    }

    public record HttpProperties(
            @NotNull @DefaultValue("5s") Duration connectTimeout,
            @NotNull @DefaultValue("20s") Duration readTimeout) {
    }
}
