package com.blockchainsearcher.analysis;

import com.blockchainsearcher.abi.DecodedEvent;
import com.blockchainsearcher.abi.EventRegistry;
import com.blockchainsearcher.abi.Hex;
import com.blockchainsearcher.domain.Match;
import com.blockchainsearcher.domain.MatchType;
import com.blockchainsearcher.domain.TokenMetadata;
import com.blockchainsearcher.domain.TokenMetadataResolver;
import com.blockchainsearcher.domain.TxContext;
import com.blockchainsearcher.domain.TxContext.ExternalEventDecoding;
import com.blockchainsearcher.domain.TxContext.ExternalParam;
import com.blockchainsearcher.domain.TxContext.InternalCall;
import com.blockchainsearcher.domain.TxContext.LogEntry;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Pure logic: given a normalized transaction, finds every place the address shows up.
 * No I/O and no framework dependencies, so it is trivially unit-testable.
 */
public class InvolvementAnalyzer {

    private static final int DEFAULT_TOKEN_DECIMALS = 18;
    /** BENJI's {@code rate} and {@code price} are 18-decimal fixed point. */
    private static final int FIXED_POINT_SCALE = 18;

    private final EventRegistry registry;

    public InvolvementAnalyzer(EventRegistry registry) {
        this.registry = registry;
    }

    public List<Match> analyze(TxContext tx, String address, TokenMetadataResolver tokens) {
        String target = Hex.normalizeAddress(address);
        List<Match> matches = new ArrayList<>();

        if (target.equals(tx.from())) {
            matches.add(new Match(MatchType.TX_SENDER, "Address sent (signed) the transaction",
                    null, null, null, "rpc", details("from", Hex.checksum(target))));
        }
        if (target.equals(tx.to())) {
            matches.add(new Match(MatchType.TX_RECIPIENT, "Address is the transaction's 'to'",
                    null, Hex.checksum(target), null, "rpc",
                    details("to", Hex.checksum(target), "methodId", methodId(tx.input()),
                            "valueWei", String.valueOf(tx.valueWei()))));
        }
        if (target.equals(tx.createdContract())) {
            matches.add(new Match(MatchType.CONTRACT_CREATED, "Transaction deployed a contract at the address",
                    null, Hex.checksum(target), null, "rpc", details("contract", Hex.checksum(target))));
        }
        calldataMatch(tx.input(), target).ifPresent(matches::add);

        Map<Integer, DecodedEvent> decodedLogs = new LinkedHashMap<>();
        for (LogEntry log : tx.logs()) {
            registry.decode(log.topics(), log.data()).ifPresent(d -> decodedLogs.put(log.logIndex(), d));
        }

        for (LogEntry log : tx.logs()) {
            DecodedEvent decoded = decodedLogs.get(log.logIndex());
            String eventLabel;
            if (decoded != null) {
                matches.addAll(matchDecoded(log, decoded, target, tx, decodedLogs, tokens));
                eventLabel = decoded.definition().canonicalSignature();
            } else {
                ExternalEventDecoding external = tx.externalDecodings() == null
                        ? null : tx.externalDecodings().get(log.logIndex());
                matches.addAll(matchUndecoded(log, target, external));
                eventLabel = external != null ? external.methodCall() : topic0(log);
            }
            if (target.equals(log.address())) {
                matches.add(new Match(MatchType.EVENT_EMITTER, "Address emitted log #" + log.logIndex(),
                        log.logIndex(), Hex.checksum(target), eventLabel, "rpc", details("topic0", topic0(log))));
            }
        }

        if (tx.internalCalls() != null) {
            for (InternalCall call : tx.internalCalls()) {
                boolean isCaller = target.equals(call.from());
                boolean isCallee = target.equals(call.to());
                if (!isCaller && !isCallee) {
                    continue;
                }
                String role = isCaller && isCallee ? "caller+callee" : isCaller ? "caller" : "callee";
                matches.add(new Match(MatchType.INTERNAL_CALL,
                        "Address is the " + role + " of internal " + call.type() + " " + call.traceAddress(),
                        null, call.to() == null ? null : Hex.checksum(call.to()), null, tx.internalCallsSource(),
                        details("type", call.type(), "from", Hex.checksum(call.from()),
                                "to", call.to() == null ? null : Hex.checksum(call.to()),
                                "valueWei", String.valueOf(call.valueWei()), "traceAddress", call.traceAddress(),
                                "failed", call.failed())));
            }
        }
        return matches;
    }

    // ------------------------------------------------------------------ decoded events

    private List<Match> matchDecoded(LogEntry log, DecodedEvent event, String target, TxContext tx,
                                     Map<Integer, DecodedEvent> decodedLogs, TokenMetadataResolver tokens) {
        String id = event.definition().id();
        if (EventRegistry.DIVIDEND_DISTRIBUTED.equals(id)) {
            return isAddress(event.value("account"), target)
                    ? List.of(dividendMatch(log, event, target, tx, decodedLogs, tokens)) : List.of();
        }
        if (EventRegistry.ERC20_TRANSFER.equals(id)) {
            return erc20TransferMatch(log, event, target, tokens).map(List::of).orElse(List.of());
        }
        if (EventRegistry.ERC721_TRANSFER.equals(id)) {
            String from = lower(event.value("from"));
            String to = lower(event.value("to"));
            if (!target.equals(from) && !target.equals(to)) {
                return List.of();
            }
            String direction = direction(target, from, to);
            return List.of(new Match(MatchType.NFT_TRANSFER,
                    "NFT #" + event.value("tokenId") + " " + ("IN".equals(direction) ? "received" : "sent"),
                    log.logIndex(), Hex.checksum(log.address()), event.definition().canonicalSignature(), "rpc",
                    details("collection", Hex.checksum(log.address()), "from", event.value("from"),
                            "to", event.value("to"), "tokenId", str(event.value("tokenId")),
                            "direction", direction)));
        }
        if (EventRegistry.ERC1155_TRANSFER_SINGLE.equals(id) || EventRegistry.ERC1155_TRANSFER_BATCH.equals(id)) {
            List<String> roles = addressRoles(event, target);
            if (roles.isEmpty()) {
                return List.of();
            }
            Map<String, Object> d = decodedValues(event);
            d.put("matchedParameters", roles);
            return List.of(new Match(MatchType.MULTI_TOKEN_TRANSFER, "ERC-1155 transfer with address as " + roles,
                    log.logIndex(), Hex.checksum(log.address()), event.definition().canonicalSignature(), "rpc", d));
        }
        List<String> roles = addressRoles(event, target);
        if (roles.isEmpty()) {
            return List.of();
        }
        Map<String, Object> d = decodedValues(event);
        d.put("matchedParameters", roles);
        return List.of(new Match(MatchType.EVENT_PARAMETER,
                event.definition().name() + " event with address as " + String.join(", ", roles),
                log.logIndex(), Hex.checksum(log.address()), event.definition().canonicalSignature(), "rpc", d));
    }

    private Optional<Match> erc20TransferMatch(LogEntry log, DecodedEvent event, String target,
                                               TokenMetadataResolver tokens) {
        String from = lower(event.value("from"));
        String to = lower(event.value("to"));
        if (!target.equals(from) && !target.equals(to)) {
            return Optional.empty();
        }
        BigInteger value = (BigInteger) event.value("value");
        TokenMetadata meta = tokens.resolve(log.address()).orElse(new TokenMetadata(null, null));
        int decimals = meta.decimals() != null ? meta.decimals() : DEFAULT_TOKEN_DECIMALS;
        String kind = Hex.ZERO_ADDRESS.equals(from) ? "MINT" : Hex.ZERO_ADDRESS.equals(to) ? "BURN" : "TRANSFER";
        String direction = direction(target, from, to);
        String symbol = meta.symbol() != null ? meta.symbol() : "tokens";
        String amount = scaled(value, decimals);

        String verb = "IN".equals(direction) ? "Received" : "OUT".equals(direction) ? "Sent" : "Self-transferred";
        String description = verb + " " + amount + " " + symbol + ("TRANSFER".equals(kind) ? "" : " (" + kind.toLowerCase(Locale.ROOT) + ")");

        Map<String, Object> d = details(
                "token", Hex.checksum(log.address()), "symbol", meta.symbol(), "decimals", decimals,
                "decimalsAssumed", meta.decimals() == null,
                "from", event.value("from"), "to", event.value("to"),
                "direction", direction, "kind", kind,
                "amountRaw", value.toString(), "amount", amount);
        return Optional.of(new Match(MatchType.TOKEN_TRANSFER, description, log.logIndex(),
                Hex.checksum(log.address()), event.definition().canonicalSignature(), "rpc", d));
    }

    private Match dividendMatch(LogEntry log, DecodedEvent event, String target, TxContext tx,
                                Map<Integer, DecodedEvent> decodedLogs, TokenMetadataResolver tokens) {
        BigInteger date = (BigInteger) event.value("date");
        BigInteger rate = (BigInteger) event.value("rate");
        BigInteger price = (BigInteger) event.value("price");
        BigInteger shares = (BigInteger) event.value("shares");
        BigInteger cash = (BigInteger) event.value("dividendCashAmount");
        BigInteger basis = (BigInteger) event.value("dividendBasis");
        Boolean negative = (Boolean) event.value("isNegativeYield");

        // The dividend contract is not the token: the shares are minted (or burned, for negative
        // yield) by a separate ERC-20 Transfer in the same tx. Pair them to learn the token.
        LogEntry pairedTransfer = findPairedTransfer(tx, decodedLogs, target, shares, log.logIndex(),
                Boolean.TRUE.equals(negative));
        TokenMetadata meta = pairedTransfer == null ? new TokenMetadata(null, null)
                : tokens.resolve(pairedTransfer.address()).orElse(new TokenMetadata(null, null));
        int decimals = meta.decimals() != null ? meta.decimals() : DEFAULT_TOKEN_DECIMALS;

        Instant dateInstant = Instant.ofEpochSecond(date.longValueExact());
        BigDecimal rateDecimal = new BigDecimal(rate, FIXED_POINT_SCALE);
        String ratePercent = plain(rateDecimal.movePointRight(2));
        String annualized = plain(rateDecimal.multiply(BigDecimal.valueOf(365)).movePointRight(2));
        String symbol = meta.symbol() != null ? meta.symbol() : "tokens";

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("account", Hex.checksum(target));
        d.put("date", date.toString());
        d.put("dateUtc", dateInstant.atOffset(ZoneOffset.UTC).toString());
        d.put("rate", rate.toString());
        d.put("rateDecimal", plain(rateDecimal));
        d.put("ratePercent", ratePercent);
        d.put("simpleAnnualizedPercent", annualized);
        d.put("price", price.toString());
        d.put("priceDecimal", scaled(price, FIXED_POINT_SCALE));
        d.put("sharesRaw", shares.toString());
        d.put("shares", scaled(shares, decimals));
        d.put("dividendCashAmountRaw", cash.toString());
        d.put("dividendCashAmount", scaled(cash, decimals));
        d.put("dividendBasisRaw", basis.toString());
        d.put("dividendBasis", scaled(basis, decimals));
        d.put("isNegativeYield", negative);
        d.put("amountDecimals", decimals);
        d.put("decimalsAssumed", meta.decimals() == null);
        if (pairedTransfer != null) {
            d.put("token", details("address", Hex.checksum(pairedTransfer.address()), "symbol", meta.symbol(),
                    "decimals", meta.decimals(), "transferLogIndex", pairedTransfer.logIndex()));
        }

        String description = String.format(Locale.ROOT,
                "%s of %s %s for %s at %s%% per day (~%s%% simple annualized), %s %s shares on a balance of %s",
                Boolean.TRUE.equals(negative) ? "Negative-yield adjustment" : "Dividend",
                scaled(cash, decimals), symbol, dateInstant.atOffset(ZoneOffset.UTC).toLocalDate(),
                ratePercent, annualized, Boolean.TRUE.equals(negative) ? "burning" : "reinvested as",
                scaled(shares, decimals), scaled(basis, decimals));

        return new Match(MatchType.DIVIDEND_DISTRIBUTED, description, log.logIndex(), Hex.checksum(log.address()),
                event.definition().canonicalSignature(), "rpc", d);
    }

    private LogEntry findPairedTransfer(TxContext tx, Map<Integer, DecodedEvent> decodedLogs, String target,
                                        BigInteger shares, int dividendLogIndex, boolean negativeYield) {
        LogEntry best = null;
        for (LogEntry candidate : tx.logs()) {
            DecodedEvent e = decodedLogs.get(candidate.logIndex());
            if (e == null || !EventRegistry.ERC20_TRANSFER.equals(e.definition().id())) {
                continue;
            }
            String from = lower(e.value("from"));
            String to = lower(e.value("to"));
            boolean shapeMatches = negativeYield
                    ? target.equals(from) && Hex.ZERO_ADDRESS.equals(to)
                    : Hex.ZERO_ADDRESS.equals(from) && target.equals(to);
            if (!shapeMatches || !shares.equals(e.value("value"))) {
                continue;
            }
            if (best == null || Math.abs(candidate.logIndex() - dividendLogIndex)
                    < Math.abs(best.logIndex() - dividendLogIndex)) {
                best = candidate;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ undecoded logs

    private List<Match> matchUndecoded(LogEntry log, String target, ExternalEventDecoding external) {
        if (external != null) {
            List<String> roles = new ArrayList<>();
            Map<String, Object> params = new LinkedHashMap<>();
            for (ExternalParam p : external.params()) {
                params.put(p.name(), p.value());
                if ("address".equals(p.type()) && target.equalsIgnoreCase(p.value())) {
                    roles.add(p.name());
                }
            }
            if (!roles.isEmpty()) {
                Map<String, Object> d = new LinkedHashMap<>(params);
                d.put("matchedParameters", roles);
                return List.of(new Match(MatchType.EVENT_PARAMETER,
                        "Event decoded by explorer with address as " + String.join(", ", roles),
                        log.logIndex(), Hex.checksum(log.address()), external.methodCall(), "blockscout", d));
            }
        }

        List<Match> matches = new ArrayList<>();
        String word = Hex.addressAsWord(target);
        List<Integer> topicPositions = new ArrayList<>();
        for (int i = 1; i < log.topics().size(); i++) {
            if (log.topics().get(i) != null && word.equalsIgnoreCase(Hex.strip0x(log.topics().get(i)))) {
                topicPositions.add(i);
            }
        }
        if (!topicPositions.isEmpty()) {
            matches.add(new Match(MatchType.LOG_TOPIC, "Address is in indexed topic(s) " + topicPositions
                    + " of an undecoded log", log.logIndex(), Hex.checksum(log.address()), topic0(log), "rpc",
                    details("topicIndexes", topicPositions, "topics", log.topics(), "data", log.data())));
        }
        String data = Hex.strip0x(log.data()).toLowerCase(Locale.ROOT);
        List<Integer> wordPositions = new ArrayList<>();
        for (int i = 0; i + 64 <= data.length(); i += 64) {
            if (word.equals(data.substring(i, i + 64))) {
                wordPositions.add(i / 64);
            }
        }
        if (!wordPositions.isEmpty()) {
            matches.add(new Match(MatchType.LOG_DATA, "Address is in data word(s) " + wordPositions
                    + " of an undecoded log", log.logIndex(), Hex.checksum(log.address()), topic0(log), "rpc",
                    details("wordIndexes", wordPositions, "topics", log.topics(), "data", log.data())));
        }
        return matches;
    }

    // ------------------------------------------------------------------ calldata

    private Optional<Match> calldataMatch(String input, String target) {
        String hex = Hex.strip0x(input).toLowerCase(Locale.ROOT);
        String needle = Hex.strip0x(target);
        List<Integer> byteOffsets = new ArrayList<>();
        boolean topLevelArgument = false;
        for (int pos = hex.indexOf(needle); pos >= 0; pos = hex.indexOf(needle, pos + 1)) {
            if (pos % 2 != 0) {
                continue; // not byte-aligned, coincidental
            }
            int offset = pos / 2;
            byteOffsets.add(offset);
            // a top-level address argument sits in the last 20 bytes of a word after the 4-byte selector
            if (offset >= 16 && (offset - 4) % 32 == 12) {
                topLevelArgument = true;
            }
        }
        if (byteOffsets.isEmpty()) {
            return Optional.empty();
        }
        String description = "Address appears " + byteOffsets.size() + " time(s) in the transaction input"
                + (topLevelArgument ? "" : " (inside nested calldata)");
        return Optional.of(new Match(MatchType.CALLDATA, description, null, null, null, "rpc",
                details("methodId", methodId(input), "occurrences", byteOffsets.size(), "byteOffsets", byteOffsets,
                        "topLevelArgument", topLevelArgument)));
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> addressRoles(DecodedEvent event, String target) {
        List<String> roles = new ArrayList<>();
        for (DecodedEvent.Value v : event.values()) {
            if ("address".equals(v.type()) && isAddress(v.value(), target)) {
                roles.add(v.name());
            } else if ("address[]".equals(v.type()) && v.value() instanceof List<?> list
                    && list.stream().anyMatch(item -> isAddress(item, target))) {
                roles.add(v.name());
            }
        }
        return roles;
    }

    private static Map<String, Object> decodedValues(DecodedEvent event) {
        Map<String, Object> d = new LinkedHashMap<>();
        for (DecodedEvent.Value v : event.values()) {
            d.put(v.name(), str(v.value()));
        }
        return d;
    }

    private static boolean isAddress(Object value, String target) {
        return value instanceof String s && target.equalsIgnoreCase(s);
    }

    private static String direction(String target, String from, String to) {
        if (target.equals(from) && target.equals(to)) {
            return "SELF";
        }
        return target.equals(to) ? "IN" : "OUT";
    }

    private static String lower(Object address) {
        return address == null ? null : Hex.normalizeAddress(address.toString());
    }

    private static String topic0(LogEntry log) {
        return log.topics().isEmpty() ? null : log.topics().get(0);
    }

    private static String methodId(String input) {
        String hex = Hex.strip0x(input);
        return hex.length() >= 8 ? "0x" + hex.substring(0, 8).toLowerCase(Locale.ROOT) : null;
    }

    static String scaled(BigInteger raw, int decimals) {
        return plain(new BigDecimal(raw, decimals));
    }

    private static String plain(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static Object str(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(InvolvementAnalyzer::str).toList();
        }
        return value == null ? null : value instanceof Boolean ? value : value.toString();
    }

    /** Ordered map from alternating key/value arguments; null values are kept out. */
    private static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put((String) keyValues[i], keyValues[i + 1]);
            }
        }
        return map;
    }
}
