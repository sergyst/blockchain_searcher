package com.blockchainsearcher.api;

import com.blockchainsearcher.service.InvolvementService;
import com.blockchainsearcher.service.NetworkRegistry;
import com.blockchainsearcher.service.Web3jInvolvementService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Constraint annotations on the parameters are enforced by Spring MVC's built-in method
 * validation; violations become a 400 problem response (see GlobalExceptionHandler).
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class InvolvementController {

    static final String TX_HASH = "^0x[0-9a-fA-F]{64}$";
    static final String ADDRESS = "^0x[0-9a-fA-F]{40}$";
    static final String NETWORK = "^[A-Za-z0-9_-]{1,64}$";

    private final InvolvementService involvementService;
    private final Web3jInvolvementService web3jInvolvementService;
    private final NetworkRegistry networkRegistry;

    /**
     * Example: {@code GET /api/v1/transactions/0xb74c…3f10/involvement?address=0x6B01…18fa&network=sepolia}
     */
    @GetMapping("/transactions/{hash}/involvement")
    public InvolvementResponse involvement(
            @PathVariable("hash")
            @Pattern(regexp = TX_HASH, message = "must be a 0x-prefixed 32-byte hex transaction hash")
            String hash,
            @RequestParam("address")
            @Pattern(regexp = ADDRESS, message = "must be a 0x-prefixed 20-byte hex address")
            String address,
            @RequestParam(name = "network", required = false)
            @Pattern(regexp = NETWORK, message = "must be a configured network name")
            String network) {
        return involvementService.findInvolvement(network, hash, address);
    }

    /**
     * Same search through Web3j. Optional {@code contract} keeps only matches from logs that contract emitted,
     * e.g. {@code &contract=0x645Ba1c8aB1cb65Cf541F97a4f0cf30f58A471E4} for the BENJI dividend contract.
     */
    @GetMapping("/transactions/{hash}/involvement/web3j")
    public InvolvementResponse involvementWeb3j(
            @PathVariable("hash")
            @Pattern(regexp = TX_HASH, message = "must be a 0x-prefixed 32-byte hex transaction hash")
            String hash,
            @RequestParam("address")
            @Pattern(regexp = ADDRESS, message = "must be a 0x-prefixed 20-byte hex address")
            String address,
            @RequestParam(name = "network", required = false)
            @Pattern(regexp = NETWORK, message = "must be a configured network name")
            String network,
            @RequestParam(name = "contract", required = false)
            @Pattern(regexp = ADDRESS, message = "must be a 0x-prefixed 20-byte hex address")
            String contract) {
        return web3jInvolvementService.findInvolvementWeb3j(network, hash, address, contract);
    }

    @GetMapping("/networks")
    public List<NetworkInfo> networks() {
        String defaultNetwork = networkRegistry.defaultNetwork();
        return networkRegistry.configured().entrySet().stream()
                .map(e -> new NetworkInfo(e.getKey(), e.getValue().chainId(), e.getKey().equals(defaultNetwork),
                        e.getValue().hasBlockscout(), e.getValue().traceEnabled()))
                .toList();
    }
}
