package com.blockchainsearcher.service;

import com.blockchainsearcher.abi.Hex;
import com.blockchainsearcher.analysis.InvolvementAnalyzer;
import com.blockchainsearcher.api.InvolvementResponse;
import com.blockchainsearcher.api.InvolvementResponse.DataSources;
import com.blockchainsearcher.domain.Match;
import com.blockchainsearcher.domain.TxContext;
import com.blockchainsearcher.domain.TxContext.LogEntry;
import com.blockchainsearcher.domain.TxContext.TxStatus;
import com.blockchainsearcher.error.TransactionNotFoundException;
import com.blockchainsearcher.error.UpstreamServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Same question as {@link InvolvementService}, answered with Web3j instead of the hand-written
 * RestClient JSON-RPC client. Only the node is used (no Blockscout, no tracing), and the result goes
 * through the same {@link InvolvementAnalyzer}, so both paths return identical matches for the logs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Web3jInvolvementService {

    private final NetworkRegistry networks;
    private final InvolvementAnalyzer analyzer;

    public InvolvementResponse findInvolvementWeb3j(String networkName, String txHash, String address) {
        return findInvolvementWeb3j(networkName, txHash, address, null);
    }

    /**
     * @param contractAddress optional; when given, only matches from logs emitted by this contract are
     *                        returned (e.g. the BENJI dividend contract). Transaction-level matches
     *                        (sender, recipient, calldata, internal calls) are left out in that mode.
     */
    public InvolvementResponse findInvolvementWeb3j(String networkName, String txHash, String address,
                                                    String contractAddress) {
        ChainClients chain = networks.get(networkName);
        Web3j web3j = chain.web3j();
        String hash = txHash.toLowerCase(Locale.ROOT);
        String target = Hex.normalizeAddress(address);
        List<String> warnings = new ArrayList<>();
        log.info("[{}] (web3j) checking {} for {}{}", chain.name(), hash, target,
                contractAddress == null ? "" : " in logs of " + contractAddress);

        Transaction tx = send(chain, web3j.ethGetTransactionByHash(hash)).getTransaction()
                .orElseThrow(() -> new TransactionNotFoundException(chain.name(), hash));

        Optional<TransactionReceipt> receiptOpt = send(chain, web3j.ethGetTransactionReceipt(hash))
                .getTransactionReceipt();
        // A mined tx always has a receipt. Load-balanced public RPCs sometimes route the receipt call to a
        // backend that has not indexed it yet and answer null: retry, then fail instead of reporting "pending".
        for (int attempt = 1; receiptOpt.isEmpty() && tx.getBlockNumberRaw() != null
                && attempt <= ReceiptRetry.ATTEMPTS; attempt++) {
            log.warn("[{}] (web3j) node returned no receipt for mined tx {} (block {}), retry {}/{}",
                    chain.name(), hash, tx.getBlockNumberRaw(), attempt, ReceiptRetry.ATTEMPTS);
            ReceiptRetry.pause(attempt);
            receiptOpt = send(chain, web3j.ethGetTransactionReceipt(hash)).getTransactionReceipt();
        }
        if (receiptOpt.isEmpty() && tx.getBlockNumberRaw() != null) {
            throw ReceiptRetry.missingReceipt(chain.name(), hash, tx.getBlockNumberRaw());
        }
        if (receiptOpt.isEmpty()) {
            warnings.add("Transaction is pending: only sender, recipient and calldata were checked");
        }
        warnings.add("Internal calls are not checked on the Web3j path");

        List<Log> allLogs = receiptOpt.map(TransactionReceipt::getLogs).orElse(List.of());
        List<Log> contractLogs = contractAddress == null ? allLogs : allLogs.stream()
                .filter(l -> contractAddress.equalsIgnoreCase(l.getAddress()))
                .toList();

        Instant timestamp = blockTimestamp(chain, tx, warnings);

        // The analyzer always sees every log, so a dividend can still be paired with its share mint
        // (emitted by the token contract) even when the caller filters to the dividend contract.
        TxContext context = new TxContext(
                hash,
                receiptOpt.map(r -> "0x1".equals(r.getStatus()) ? TxStatus.SUCCESS : TxStatus.FAILED)
                        .orElse(TxStatus.PENDING),
                Hex.normalizeAddress(tx.getFrom()),
                Hex.normalizeAddress(tx.getTo()),
                tx.getInput(),
                tx.getValue(),
                tx.getBlockNumberRaw() == null ? null : Hex.quantity(tx.getBlockNumberRaw()).longValueExact(),
                timestamp,
                receiptOpt.map(r -> Hex.normalizeAddress(r.getContractAddress())).orElse(null),
                allLogs.stream().map(Web3jInvolvementService::toLogEntry).toList(),
                null,
                null,
                Map.of());

        List<Match> matches = analyzer.analyze(context, target, chain.web3jTokens());
        if (contractAddress != null) {
            Set<Integer> wanted = contractLogs.stream()
                    .map(l -> Hex.quantity(l.getLogIndexRaw()).intValueExact())
                    .collect(Collectors.toSet());
            matches = matches.stream().filter(m -> m.logIndex() != null && wanted.contains(m.logIndex())).toList();
        }
        log.info("[{}] (web3j) {} for {}: {} match(es)", chain.name(), hash, target, matches.size());

        return InvolvementResponses.of(chain, context, target, contractLogs.size(), matches,
                new DataSources("web3j", "unavailable", "not-used"), warnings);
    }

    private static Instant blockTimestamp(ChainClients chain, Transaction tx, List<String> warnings) {
        if (tx.getBlockNumberRaw() == null) {
            return null;
        }
        try {
            EthBlock.Block block = chain.web3j()
                    .ethGetBlockByNumber(DefaultBlockParameter.valueOf(Hex.quantity(tx.getBlockNumberRaw())), false)
                    .send()
                    .getBlock();
            return block == null ? null : Instant.ofEpochSecond(block.getTimestamp().longValueExact());
        } catch (Exception e) {
            warnings.add("Block timestamp unavailable: " + e.getMessage());
            return null;
        }
    }

    private static LogEntry toLogEntry(Log log) {
        return new LogEntry(
                Hex.quantity(log.getLogIndexRaw()).intValueExact(),
                Hex.normalizeAddress(log.getAddress()),
                log.getTopics() == null ? List.of()
                        : log.getTopics().stream().map(t -> t.toLowerCase(Locale.ROOT)).toList(),
                log.getData() == null ? "0x" : log.getData());
    }

    /** Sends a Web3j request; transport failures and JSON-RPC errors become a 502. */
    private static <T extends Response<?>> T send(ChainClients chain, Request<?, T> request) {
        T response;
        try {
            response = request.send();
        } catch (Exception e) {
            throw new UpstreamServiceException(chain.name(),
                    "Web3j " + request.getMethod() + " failed: " + e.getMessage(), e);
        }
        if (response.hasError()) {
            throw new UpstreamServiceException(chain.name(),
                    "Web3j " + request.getMethod() + ": " + response.getError().getMessage(), null);
        }
        return response;
    }
}
