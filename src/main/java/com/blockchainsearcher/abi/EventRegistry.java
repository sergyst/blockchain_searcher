package com.blockchainsearcher.abi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Event ABIs the analyzer can decode itself, straight from raw RPC logs.
 * Several definitions may share one topic0 (ERC-20 and ERC-721 {@code Transfer});
 * the one whose indexed-parameter count matches the log's topic count wins.
 */
public class EventRegistry {

    public static final String ERC20_TRANSFER = "ERC20_TRANSFER";
    public static final String ERC721_TRANSFER = "ERC721_TRANSFER";
    public static final String ERC20_APPROVAL = "ERC20_APPROVAL";
    public static final String ERC721_APPROVAL = "ERC721_APPROVAL";
    public static final String APPROVAL_FOR_ALL = "APPROVAL_FOR_ALL";
    public static final String ERC1155_TRANSFER_SINGLE = "ERC1155_TRANSFER_SINGLE";
    public static final String ERC1155_TRANSFER_BATCH = "ERC1155_TRANSFER_BATCH";
    public static final String WETH_DEPOSIT = "WETH_DEPOSIT";
    public static final String WETH_WITHDRAWAL = "WETH_WITHDRAWAL";
    /** Franklin Templeton BENJI (FOBXX) transfer-agent dividend event. */
    public static final String DIVIDEND_DISTRIBUTED = "DIVIDEND_DISTRIBUTED";
    public static final String CUSTOM = "CUSTOM";

    private final Map<String, List<EventDefinition>> byTopic0 = new LinkedHashMap<>();

    public static EventRegistry withBuiltIns() {
        EventRegistry registry = new EventRegistry();
        registry.register(ERC20_TRANSFER, "Transfer(address indexed from, address indexed to, uint256 value)");
        registry.register(ERC721_TRANSFER,
                "Transfer(address indexed from, address indexed to, uint256 indexed tokenId)");
        registry.register(ERC20_APPROVAL,
                "Approval(address indexed owner, address indexed spender, uint256 value)");
        registry.register(ERC721_APPROVAL,
                "Approval(address indexed owner, address indexed approved, uint256 indexed tokenId)");
        registry.register(APPROVAL_FOR_ALL,
                "ApprovalForAll(address indexed owner, address indexed operator, bool approved)");
        registry.register(ERC1155_TRANSFER_SINGLE, "TransferSingle(address indexed operator, address indexed from, "
                + "address indexed to, uint256 id, uint256 value)");
        registry.register(ERC1155_TRANSFER_BATCH, "TransferBatch(address indexed operator, address indexed from, "
                + "address indexed to, uint256[] ids, uint256[] values)");
        registry.register(WETH_DEPOSIT, "Deposit(address indexed dst, uint256 wad)");
        registry.register(WETH_WITHDRAWAL, "Withdrawal(address indexed src, uint256 wad)");
        registry.register(DIVIDEND_DISTRIBUTED, "DividendDistributed(address indexed account, uint256 indexed date, "
                + "int256 rate, uint256 price, uint256 shares, uint256 dividendCashAmount, uint256 dividendBasis, "
                + "bool isNegativeYield)");
        return registry;
    }

    public EventDefinition register(String id, String humanReadableSignature) {
        EventDefinition definition = EventDefinition.parse(id, humanReadableSignature);
        byTopic0.computeIfAbsent(definition.topic0().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                .add(definition);
        return definition;
    }

    /** Decodes a log if any registered definition fits it. */
    public Optional<DecodedEvent> decode(List<String> topics, String data) {
        if (topics == null || topics.isEmpty() || topics.get(0) == null) {
            return Optional.empty();
        }
        List<EventDefinition> candidates = byTopic0.get(topics.get(0).toLowerCase(Locale.ROOT));
        if (candidates == null) {
            return Optional.empty();
        }
        for (EventDefinition candidate : candidates) {
            if (candidate.indexedCount() != topics.size() - 1) {
                continue;
            }
            try {
                return Optional.of(AbiDecoder.decode(candidate, topics, data));
            } catch (AbiDecodingException ignored) {
                // try the next candidate; fall back to raw scanning if none fits
            }
        }
        return Optional.empty();
    }

    public Collection<EventDefinition> definitions() {
        return byTopic0.values().stream().flatMap(List::stream).toList();
    }
}
