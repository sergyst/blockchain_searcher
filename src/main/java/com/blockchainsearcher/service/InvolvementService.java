package com.blockchainsearcher.service;

import com.blockchainsearcher.abi.Hex;
import com.blockchainsearcher.analysis.InvolvementAnalyzer;
import com.blockchainsearcher.api.InvolvementResponse;
import com.blockchainsearcher.api.InvolvementResponse.DataSources;
import com.blockchainsearcher.blockscout.BlockscoutException;
import com.blockchainsearcher.blockscout.BlockscoutModels;
import com.blockchainsearcher.domain.Match;
import com.blockchainsearcher.domain.TxContext;
import com.blockchainsearcher.domain.TxContext.ExternalEventDecoding;
import com.blockchainsearcher.domain.TxContext.ExternalParam;
import com.blockchainsearcher.domain.TxContext.InternalCall;
import com.blockchainsearcher.domain.TxContext.LogEntry;
import com.blockchainsearcher.domain.TxContext.TxStatus;
import com.blockchainsearcher.error.TransactionNotFoundException;
import com.blockchainsearcher.error.UpstreamServiceException;
import com.blockchainsearcher.rpc.RpcException;
import com.blockchainsearcher.rpc.RpcModels.CallFrame;
import com.blockchainsearcher.rpc.RpcModels.RpcBlock;
import com.blockchainsearcher.rpc.RpcModels.RpcLog;
import com.blockchainsearcher.rpc.RpcModels.RpcReceipt;
import com.blockchainsearcher.rpc.RpcModels.RpcTransaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Collects the transaction from JSON-RPC (required), enriches it from the trace API and/or
 * Blockscout when available, then hands it to the {@link InvolvementAnalyzer}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvolvementService {

    private final NetworkRegistry networks;
    private final InvolvementAnalyzer analyzer;

    public InvolvementResponse findInvolvement(String networkName, String txHash, String address) {
        ChainClients chain = networks.get(networkName);
        String hash = txHash.toLowerCase(Locale.ROOT);
        String target = Hex.normalizeAddress(address);
        List<String> warnings = new ArrayList<>();
        log.info("[{}] checking {} for {}", chain.name(), hash, target);

        RpcTransaction tx = required(chain, () -> chain.rpc().getTransactionByHash(hash));
        if (tx == null) {
            throw new TransactionNotFoundException(chain.name(), hash);
        }
        RpcReceipt receipt = required(chain, () -> chain.rpc().getTransactionReceipt(hash));
        for (int attempt = 1; receipt == null && tx.blockNumber() != null && attempt <= ReceiptRetry.ATTEMPTS;
             attempt++) {
            log.warn("[{}] node returned no receipt for mined tx {} (block {}), retry {}/{}",
                    chain.name(), hash, tx.blockNumber(), attempt, ReceiptRetry.ATTEMPTS);
            ReceiptRetry.pause(attempt);
            receipt = required(chain, () -> chain.rpc().getTransactionReceipt(hash));
        }
        if (receipt == null && tx.blockNumber() != null) {
            throw ReceiptRetry.missingReceipt(chain.name(), hash, tx.blockNumber());
        }
        boolean mined = receipt != null;

        Instant timestamp = blockTimestamp(chain, tx, warnings);

        List<InternalCall> internalCalls = null;
        String internalSource = null;
        if (mined && chain.properties().traceEnabled()) {
            try {
                internalCalls = flatten(chain.rpc().traceTransaction(hash));
                internalSource = "rpc-trace";
            } catch (RpcException e) {
                warnings.add("debug_traceTransaction failed, falling back to Blockscout if configured: "
                        + e.getMessage());
            }
        }

        String blockscoutStatus = chain.blockscout() == null ? "not-configured" : "ok";
        Map<Integer, ExternalEventDecoding> externalDecodings = Map.of();
        if (mined && chain.blockscout() != null) {
            try {
                externalDecodings = toExternalDecodings(chain.blockscout().getLogs(hash));
            } catch (BlockscoutException e) {
                blockscoutStatus = "failed";
                warnings.add("Blockscout logs unavailable; events unknown to this service stay undecoded: "
                        + e.getMessage());
            }
            if (internalCalls == null) {
                try {
                    internalCalls = toInternalCalls(chain.blockscout().getInternalTransactions(hash));
                    internalSource = "blockscout";
                } catch (BlockscoutException e) {
                    blockscoutStatus = "failed";
                    warnings.add("Blockscout internal transactions unavailable: " + e.getMessage());
                }
            }
        }
        if (!mined) {
            warnings.add("Transaction is pending: only sender, recipient and calldata were checked");
        } else if (internalCalls == null) {
            warnings.add("Internal calls were not checked (enable trace-enabled or configure blockscout-url)");
        }

        List<LogEntry> logs = mined && receipt.logs() != null
                ? receipt.logs().stream().map(InvolvementService::toLogEntry).toList()
                : List.of();
        TxContext context = new TxContext(
                hash,
                !mined ? TxStatus.PENDING : "0x1".equals(receipt.status()) ? TxStatus.SUCCESS : TxStatus.FAILED,
                Hex.normalizeAddress(tx.from()),
                Hex.normalizeAddress(tx.to()),
                tx.input(),
                Hex.quantity(tx.value()),
                toLong(tx.blockNumber()),
                timestamp,
                mined ? Hex.normalizeAddress(receipt.contractAddress()) : null,
                logs,
                internalCalls,
                internalSource,
                externalDecodings);

        List<Match> matches = analyzer.analyze(context, target, chain.tokens());
        log.info("[{}] {} for {}: {} match(es)", chain.name(), hash, target, matches.size());

        return InvolvementResponses.of(chain, context, target, logs.size(), matches,
                new DataSources("ok", internalSource == null ? "unavailable" : internalSource, blockscoutStatus),
                warnings);
    }

    private static <T> T required(ChainClients chain, Supplier<T> call) {
        try {
            return call.get();
        } catch (RpcException e) {
            throw new UpstreamServiceException(chain.name(), "JSON-RPC " + e.getMessage(), e);
        }
    }

    private static Instant blockTimestamp(ChainClients chain, RpcTransaction tx, List<String> warnings) {
        if (tx.blockNumber() == null) {
            return null;
        }
        try {
            RpcBlock block = chain.rpc().getBlockByNumber(tx.blockNumber());
            return block == null || block.timestamp() == null
                    ? null : Instant.ofEpochSecond(Hex.quantity(block.timestamp()).longValueExact());
        } catch (RpcException e) {
            warnings.add("Block timestamp unavailable: " + e.getMessage());
            return null;
        }
    }

    private static LogEntry toLogEntry(RpcLog log) {
        return new LogEntry(
                Hex.quantity(log.logIndex()).intValueExact(),
                Hex.normalizeAddress(log.address()),
                log.topics() == null ? List.of()
                        : log.topics().stream().map(t -> t.toLowerCase(Locale.ROOT)).toList(),
                log.data() == null ? "0x" : log.data());
    }

    /** Flattens a callTracer tree; the root frame is the transaction itself and is skipped. */
    static List<InternalCall> flatten(CallFrame root) {
        List<InternalCall> calls = new ArrayList<>();
        if (root != null && root.calls() != null) {
            for (int i = 0; i < root.calls().size(); i++) {
                flatten(root.calls().get(i), String.valueOf(i), calls);
            }
        }
        return calls;
    }

    private static void flatten(CallFrame frame, String path, List<InternalCall> out) {
        out.add(new InternalCall(
                frame.type() == null ? "call" : frame.type().toLowerCase(Locale.ROOT),
                Hex.normalizeAddress(frame.from()),
                Hex.normalizeAddress(frame.to()),
                frame.value() == null ? BigInteger.ZERO : Hex.quantity(frame.value()),
                path,
                frame.error() != null));
        if (frame.calls() != null) {
            for (int i = 0; i < frame.calls().size(); i++) {
                flatten(frame.calls().get(i), path + "." + i, out);
            }
        }
    }

    private static Map<Integer, ExternalEventDecoding> toExternalDecodings(List<BlockscoutModels.Log> logs) {
        Map<Integer, ExternalEventDecoding> out = new LinkedHashMap<>();
        for (BlockscoutModels.Log log : logs) {
            if (log.index() == null || log.decoded() == null || log.decoded().methodCall() == null) {
                continue;
            }
            List<ExternalParam> params = log.decoded().parameters() == null ? List.of()
                    : log.decoded().parameters().stream()
                    .map(p -> new ExternalParam(p.name(), p.type(), Boolean.TRUE.equals(p.indexed()),
                            p.value() == null ? null : String.valueOf(p.value())))
                    .toList();
            out.put(log.index(), new ExternalEventDecoding(log.decoded().methodCall(), params));
        }
        return out;
    }

    private static List<InternalCall> toInternalCalls(List<BlockscoutModels.InternalTransaction> items) {
        return items.stream()
                .map(item -> new InternalCall(
                        item.type() == null ? "call" : item.type().toLowerCase(Locale.ROOT),
                        item.from() == null ? null : Hex.normalizeAddress(item.from().hash()),
                        item.to() != null ? Hex.normalizeAddress(item.to().hash())
                                : item.createdContract() == null ? null
                                : Hex.normalizeAddress(item.createdContract().hash()),
                        item.value() == null ? BigInteger.ZERO : Hex.quantity(item.value()),
                        "#" + item.index(),
                        Boolean.FALSE.equals(item.success())))
                .toList();
    }

    private static Long toLong(String quantity) {
        BigInteger value = Hex.quantity(quantity);
        return value == null ? null : value.longValueExact();
    }
}
