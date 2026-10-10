package com.blockchainsearcher.api;

import com.blockchainsearcher.domain.Match;
import com.blockchainsearcher.domain.MatchType;
import com.blockchainsearcher.support.StubChainServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static com.blockchainsearcher.support.StubChainServer.TX_HASH;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full HTTP round trip: controller → service → RestClients → stubbed node/explorer serving the
 * recorded Sepolia transaction. Proves the dividend for 0x6B01…18fa is found end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InvolvementControllerIntegrationTest {

    static final String MY_ADDRESS = "0x6B017Cb1a9A13d05b6ac24e978E0e6E683B418fa";
    static final StubChainServer STUB = new StubChainServer();
    static final ParameterizedTypeReference<Map<String, Object>> PROBLEM = new ParameterizedTypeReference<>() {
    };

    @Value("${local.server.port}")
    int port;

    RestClient client;

    @DynamicPropertySource
    static void networks(DynamicPropertyRegistry registry) {
        registry.add("blockchain.default-network", () -> "testnet");
        // trace enabled on purpose: the stub rejects debug_traceTransaction, so the Blockscout fallback is exercised
        registry.add("blockchain.networks.testnet.chain-id", () -> "11155111");
        registry.add("blockchain.networks.testnet.rpc-url", () -> STUB.baseUrl() + "/rpc");
        registry.add("blockchain.networks.testnet.blockscout-url", () -> STUB.baseUrl() + "/blockscout");
        registry.add("blockchain.networks.testnet.trace-enabled", () -> "true");
        registry.add("blockchain.networks.no-explorer.rpc-url", () -> STUB.baseUrl() + "/rpc");
        registry.add("blockchain.networks.lagging-node.rpc-url", () -> STUB.baseUrl() + "/rpc-no-receipt");
        registry.add("blockchain.networks.broken-explorer.rpc-url", () -> STUB.baseUrl() + "/rpc");
        registry.add("blockchain.networks.broken-explorer.blockscout-url", () -> STUB.baseUrl() + "/broken-blockscout");
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @BeforeEach
    void setUp() {
        client = RestClient.create("http://localhost:" + port);
    }

    @Test
    void findsMyBenjiDividendInTheTransaction() {
        InvolvementResponse response = involvement(TX_HASH, MY_ADDRESS, "testnet");

        assertThat(response.involved()).isTrue();
        assertThat(response.address()).isEqualTo(MY_ADDRESS);
        assertThat(response.transaction().status()).isEqualTo("SUCCESS");
        assertThat(response.transaction().blockNumber()).isEqualTo(11852115L);
        assertThat(response.transaction().timestamp()).isEqualTo("2026-10-05T23:47:12Z");
        assertThat(response.summary()).containsOnlyKeys(
                MatchType.CALLDATA, MatchType.TOKEN_TRANSFER, MatchType.DIVIDEND_DISTRIBUTED);

        Match dividend = single(response, MatchType.DIVIDEND_DISTRIBUTED);
        assertThat(dividend.logIndex()).isEqualTo(280);
        assertThat(dividend.details())
                .containsEntry("account", MY_ADDRESS)
                .containsEntry("dateUtc", "2026-10-05T21:00Z")
                .containsEntry("ratePercent", "0.010188")
                .containsEntry("dividendCashAmount", "0.199579979550020262")
                .containsEntry("dividendCashAmountRaw", "199579979550020262")
                .containsEntry("dividendBasis", "1958.971138103850236557")
                .containsEntry("isNegativeYield", false);
        assertThat(dividend.details().get("token")).isInstanceOf(Map.class);
        assertThat(asMap(dividend.details().get("token")))
                .containsEntry("symbol", "FUND")
                .containsEntry("transferLogIndex", 279);

        Match mint = single(response, MatchType.TOKEN_TRANSFER);
        assertThat(mint.logIndex()).isEqualTo(279);
        assertThat(mint.details()).containsEntry("kind", "MINT").containsEntry("amount", "0.199579979550020262");

        assertThat(response.sources().internalCalls()).isEqualTo("blockscout");
        assertThat(response.sources().blockscout()).isEqualTo("ok");
        assertThat(response.warnings()).anyMatch(w -> w.contains("debug_traceTransaction"));
    }

    @Test
    void acceptsLowerCaseAddressAndUsesDefaultNetwork() {
        InvolvementResponse response = client.get()
                .uri("/api/v1/transactions/{hash}/involvement?address={address}", TX_HASH, MY_ADDRESS.toLowerCase())
                .retrieve()
                .body(InvolvementResponse.class);

        assertThat(response.network()).isEqualTo("testnet");
        assertThat(single(response, MatchType.DIVIDEND_DISTRIBUTED).details())
                .containsEntry("dividendCashAmount", "0.199579979550020262");
    }

    @Test
    void stillFindsTheDividendWhenTheExplorerIsDown() {
        InvolvementResponse response = involvement(TX_HASH, MY_ADDRESS, "broken-explorer");

        assertThat(single(response, MatchType.DIVIDEND_DISTRIBUTED).details())
                .containsEntry("dividendCashAmount", "0.199579979550020262");
        assertThat(response.sources().blockscout()).isEqualTo("failed");
        assertThat(response.sources().internalCalls()).isEqualTo("unavailable");
        assertThat(response.warnings()).isNotEmpty();
    }

    @Test
    void explorerNamesEventsTheServiceCannotDecodeItself() {
        String dividendContract = "0x645Ba1c8aB1cb65Cf541F97a4f0cf30f58A471E4";

        Match withExplorer = last(involvement(TX_HASH, dividendContract, "testnet"), 285);
        assertThat(withExplorer.type()).isEqualTo(MatchType.EVENT_PARAMETER);
        assertThat(withExplorer.event()).startsWith("TransactionExecuted(");

        Match withoutExplorer = last(involvement(TX_HASH, dividendContract, "no-explorer"), 285);
        assertThat(withoutExplorer.type()).isEqualTo(MatchType.LOG_TOPIC);
    }

    @Test
    void reportsNoInvolvementForAnUnrelatedAddress() {
        InvolvementResponse response = involvement(TX_HASH, "0x1111111111111111111111111111111111111111", "testnet");

        assertThat(response.involved()).isFalse();
        assertThat(response.matches()).isEmpty();
    }

    @Test
    void rejectsMalformedInputWithProblemDetails() {
        var result = problem("/api/v1/transactions/0x1234/involvement?address=not-an-address&network=testnet");

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.body()).containsEntry("title", "Invalid request");
        assertThat(asMap(result.body().get("errors"))).containsKeys("hash", "address");
    }

    @Test
    void unknownNetworkIsABadRequest() {
        var result = problem("/api/v1/transactions/" + TX_HASH + "/involvement?address=" + MY_ADDRESS
                + "&network=polygon");

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.body()).containsEntry("type", "urn:blockchain-searcher:problem:unknown-network");
    }

    @Test
    void unknownTransactionIsNotFound() {
        String unknown = "0x" + "ab".repeat(32);
        var result = problem("/api/v1/transactions/" + unknown + "/involvement?address=" + MY_ADDRESS
                + "&network=testnet");

        assertThat(result.status()).isEqualTo(404);
        assertThat(result.body()).containsEntry("type", "urn:blockchain-searcher:problem:transaction-not-found");
    }

    @Test
    void listsConfiguredNetworks() {
        List<NetworkInfo> networks = client.get().uri("/api/v1/networks").retrieve()
                .body(new ParameterizedTypeReference<>() {
                });

        assertThat(networks).extracting(NetworkInfo::name).contains("testnet", "no-explorer", "sepolia");
    }

    // ------------------------------------------------------------------ Web3j path

    @Test
    void web3jFindsMyBenjiDividendInTheTransaction() {
        InvolvementResponse response = web3j(MY_ADDRESS, null);

        assertThat(response.involved()).isTrue();
        assertThat(response.sources().rpc()).isEqualTo("web3j");
        assertThat(response.transaction().timestamp()).isEqualTo("2026-10-05T23:47:12Z");

        Match dividend = single(response, MatchType.DIVIDEND_DISTRIBUTED);
        assertThat(dividend.logIndex()).isEqualTo(280);
        assertThat(dividend.details())
                .containsEntry("dividendCashAmount", "0.199579979550020262")
                .containsEntry("dividendBasis", "1958.971138103850236557");
        assertThat(asMap(dividend.details().get("token"))).containsEntry("symbol", "FUND");
    }

    @Test
    void web3jAndRestClientPathsReturnTheSameMatches() {
        List<Match> viaWeb3j = web3j(MY_ADDRESS, null).matches();
        List<Match> viaRestClient = involvement(TX_HASH, MY_ADDRESS, "testnet").matches();

        assertThat(viaWeb3j).isEqualTo(viaRestClient);
    }

    @Test
    void web3jContractFilterKeepsOnlyLogsOfThatContract() {
        String dividendContract = "0x645Ba1c8aB1cb65Cf541F97a4f0cf30f58A471E4";

        InvolvementResponse response = web3j(MY_ADDRESS, dividendContract);

        assertThat(response.summary()).containsOnlyKeys(MatchType.DIVIDEND_DISTRIBUTED);
        assertThat(response.transaction().logCount()).isEqualTo(10);
        // the share mint is emitted by the token contract, yet it is still used to resolve the token
        assertThat(asMap(single(response, MatchType.DIVIDEND_DISTRIBUTED).details().get("token")))
                .containsEntry("symbol", "FUND");

        String token = "0xEb0a4C608feD1B82DCDC98b565008316893CD6a0";
        assertThat(web3j(MY_ADDRESS, token).summary()).containsOnlyKeys(MatchType.TOKEN_TRANSFER);
    }

    @Test
    void minedTransactionWithoutReceiptIsAnUpstreamErrorNotPending() {
        String query = "/involvement?address=" + MY_ADDRESS + "&network=lagging-node";

        for (String path : List.of(query, "/involvement/web3j?address=" + MY_ADDRESS + "&network=lagging-node")) {
            var result = problem("/api/v1/transactions/" + TX_HASH + path);

            assertThat(result.status()).isEqualTo(502);
            assertThat(result.body()).containsEntry("blockNumber", 11852115);
            assertThat((String) result.body().get("detail")).contains("no receipt");
        }
    }

    @Test
    void web3jUnknownTransactionIsNotFound() {
        var result = problem("/api/v1/transactions/0x" + "ab".repeat(32) + "/involvement/web3j?address="
                + MY_ADDRESS + "&network=testnet");

        assertThat(result.status()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    private InvolvementResponse web3j(String address, String contract) {
        String uri = "/api/v1/transactions/{hash}/involvement/web3j?address={address}&network=testnet"
                + (contract == null ? "" : "&contract=" + contract);
        return client.get().uri(uri, TX_HASH, address).retrieve().body(InvolvementResponse.class);
    }

    private InvolvementResponse involvement(String hash, String address, String network) {
        return client.get()
                .uri("/api/v1/transactions/{hash}/involvement?address={address}&network={network}",
                        hash, address, network)
                .retrieve()
                .body(InvolvementResponse.class);
    }

    private record ProblemResult(int status, Map<String, Object> body) {
    }

    private ProblemResult problem(String uri) {
        return client.get().uri(uri)
                .exchange((request, response) -> new ProblemResult(response.getStatusCode().value(),
                        response.bodyTo(PROBLEM)));
    }

    private static Match single(InvolvementResponse response, MatchType type) {
        List<Match> found = response.matches().stream().filter(m -> m.type() == type).toList();
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private static Match last(InvolvementResponse response, int logIndex) {
        List<Match> found = response.matches().stream()
                .filter(m -> m.logIndex() != null && m.logIndex() == logIndex).toList();
        assertThat(found).isNotEmpty();
        return found.get(found.size() - 1);
    }
}
