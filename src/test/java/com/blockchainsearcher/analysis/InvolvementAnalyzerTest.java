package com.blockchainsearcher.analysis;

import com.blockchainsearcher.abi.EventRegistry;
import com.blockchainsearcher.domain.Match;
import com.blockchainsearcher.domain.MatchType;
import com.blockchainsearcher.domain.TokenMetadata;
import com.blockchainsearcher.domain.TokenMetadataResolver;
import com.blockchainsearcher.domain.TxContext;
import com.blockchainsearcher.domain.TxContext.ExternalEventDecoding;
import com.blockchainsearcher.domain.TxContext.ExternalParam;
import com.blockchainsearcher.domain.TxContext.LogEntry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the analyzer on logs and calldata taken from Sepolia tx 0xb74ce488…3f10. */
class InvolvementAnalyzerTest {

    static final String MY_ADDRESS = "0x6B017Cb1a9A13d05b6ac24e978E0e6E683B418fa";
    static final String TOKEN = "0xeb0a4c608fed1b82dcdc98b565008316893cd6a0";
    static final String DIVIDEND_CONTRACT = "0x645ba1c8ab1cb65cf541f97a4f0cf30f58a471e4";
    static final String RELAYER = "0x3263f8e2ca060bae03bab3d7d41afb645ec2b370";
    static final String MULTISIG = "0xe8add70ce3d1ff164201976d0c6dc62007352e9b";

    static final String TRANSFER = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    static final String DIVIDEND = "0xe0b019f23e4f4948c15bdd9dfa8808b046568a2fda0f2978492dcc284fb79c9a";
    static final String EXECUTED = "0x4caa3d439f4283405894f4e0cfd5048dd9d2053f060359ddb97be19cadf8095e";
    static final String ZERO_TOPIC = "0x" + "0".repeat(64);
    static final String DATE_TOPIC = "0x000000000000000000000000000000000000000000000000000000006ac40fd0";

    private final InvolvementAnalyzer analyzer = new InvolvementAnalyzer(EventRegistry.withBuiltIns());
    private final TokenMetadataResolver fund = token -> TOKEN.equals(token)
            ? Optional.of(new TokenMetadata("FUND", 18)) : Optional.empty();

    @Test
    void findsTheBenjiDividendForMyAddress() throws IOException {
        List<Match> matches = analyzer.analyze(transaction(Map.of()), MY_ADDRESS, fund);

        assertThat(matches).extracting(Match::type)
                .containsExactly(MatchType.CALLDATA, MatchType.TOKEN_TRANSFER, MatchType.DIVIDEND_DISTRIBUTED);

        Match dividend = matches.get(2);
        assertThat(dividend.logIndex()).isEqualTo(280);
        assertThat(dividend.contract()).isEqualTo("0x645Ba1c8aB1cb65Cf541F97a4f0cf30f58A471E4");
        assertThat(dividend.details())
                .containsEntry("account", MY_ADDRESS)
                .containsEntry("dateUtc", "2026-10-05T21:00Z")
                .containsEntry("rateDecimal", "0.00010188")
                .containsEntry("ratePercent", "0.010188")
                .containsEntry("dividendCashAmount", "0.199579979550020262")
                .containsEntry("shares", "0.199579979550020262")
                .containsEntry("dividendBasis", "1958.971138103850236557")
                .containsEntry("isNegativeYield", false)
                .containsEntry("decimalsAssumed", false);
        assertThat(dividend.details().get("token")).isEqualTo(Map.of(
                "address", "0xEb0a4C608feD1B82DCDC98b565008316893CD6a0",
                "symbol", "FUND", "decimals", 18, "transferLogIndex", 279));
        assertThat(dividend.description()).startsWith("Dividend of 0.199579979550020262 FUND for 2026-10-05");

        Match mint = matches.get(1);
        assertThat(mint.logIndex()).isEqualTo(279);
        assertThat(mint.details())
                .containsEntry("kind", "MINT")
                .containsEntry("direction", "IN")
                .containsEntry("amount", "0.199579979550020262");

        assertThat(matches.get(0).details()).containsEntry("topLevelArgument", false);
    }

    @Test
    void doesNotAttributeSomeoneElsesDividend() throws IOException {
        // 0xB051… got its own dividend (logs 277/278); it must be paired with its own mint, not mine
        List<Match> matches = analyzer.analyze(transaction(Map.of()), "0xB051bDf1A4efdDA77DAbEAFcc7bAA242AcBF2121",
                fund);

        assertThat(matches).extracting(Match::type)
                .containsExactly(MatchType.CALLDATA, MatchType.TOKEN_TRANSFER, MatchType.DIVIDEND_DISTRIBUTED);
        assertThat(matches.get(2).logIndex()).isEqualTo(278);
        assertThat(matches.get(2).details()).containsEntry("dividendCashAmount", "221.961374075826641086");
    }

    @Test
    void reportsSenderRecipientAndEmitter() throws IOException {
        TxContext tx = transaction(Map.of());

        assertThat(analyzer.analyze(tx, RELAYER, fund)).extracting(Match::type)
                .containsExactly(MatchType.TX_SENDER);
        assertThat(analyzer.analyze(tx, MULTISIG, fund)).extracting(Match::type)
                .containsExactly(MatchType.TX_RECIPIENT, MatchType.EVENT_EMITTER);
    }

    @Test
    void usesRawTopicMatchWithoutExplorerAndNamedParameterWithIt() throws IOException {
        assertThat(analyzer.analyze(transaction(Map.of()), DIVIDEND_CONTRACT, fund))
                .extracting(Match::type)
                .containsExactly(MatchType.CALLDATA, MatchType.EVENT_EMITTER, MatchType.EVENT_EMITTER,
                        MatchType.LOG_TOPIC);

        Map<Integer, ExternalEventDecoding> explorer = Map.of(285, new ExternalEventDecoding(
                "TransactionExecuted(address indexed destination, bytes indexed result)",
                List.of(new ExternalParam("destination", "address", true, "0x645Ba1c8aB1cb65Cf541F97a4f0cf30f58A471E4"),
                        new ExternalParam("result", "bytes", true, "0xc5d2"))));
        List<Match> enriched = analyzer.analyze(transaction(explorer), DIVIDEND_CONTRACT, fund);

        Match executed = enriched.get(enriched.size() - 1);
        assertThat(executed.type()).isEqualTo(MatchType.EVENT_PARAMETER);
        assertThat(executed.source()).isEqualTo("blockscout");
        assertThat(executed.details()).containsEntry("matchedParameters", List.of("destination"));
    }

    @Test
    void unrelatedAddressHasNoMatches() throws IOException {
        assertThat(analyzer.analyze(transaction(Map.of()), "0x1111111111111111111111111111111111111111", fund))
                .isEmpty();
    }

    /** Logs 277–280 and 285 of the real transaction, with the real calldata. */
    private static TxContext transaction(Map<Integer, ExternalEventDecoding> explorer) throws IOException {
        List<LogEntry> logs = List.of(
                new LogEntry(277, TOKEN, List.of(TRANSFER, ZERO_TOPIC,
                        "0x000000000000000000000000b051bdf1a4efdda77dabeafcc7baa242acbf2121"),
                        "0x00000000000000000000000000000000000000000000000c085535b85f3e98be"),
                new LogEntry(278, DIVIDEND_CONTRACT, List.of(DIVIDEND,
                        "0x000000000000000000000000b051bdf1a4efdda77dabeafcc7baa242acbf2121", DATE_TOPIC),
                        "0x00000000000000000000000000000000000000000000000000005ca8c935b000"
                                + "0000000000000000000000000000000000000000000000000de0b6b3a7640000"
                                + "00000000000000000000000000000000000000000000000c085535b85f3e98be"
                                + "00000000000000000000000000000000000000000000000c085535b85f3e98be"
                                + "00000000000000000000000000000000000000000001cd59202932845cb7f6c0"
                                + "0000000000000000000000000000000000000000000000000000000000000000"),
                new LogEntry(279, TOKEN, List.of(TRANSFER, ZERO_TOPIC,
                        "0x0000000000000000000000006b017cb1a9a13d05b6ac24e978e0e6e683b418fa"),
                        "0x00000000000000000000000000000000000000000000000002c50cef195bd2a6"),
                new LogEntry(280, DIVIDEND_CONTRACT, List.of(DIVIDEND,
                        "0x0000000000000000000000006b017cb1a9a13d05b6ac24e978e0e6e683b418fa", DATE_TOPIC),
                        "0x00000000000000000000000000000000000000000000000000005ca8c935b000"
                                + "0000000000000000000000000000000000000000000000000de0b6b3a7640000"
                                + "00000000000000000000000000000000000000000000000002c50cef195bd2a6"
                                + "00000000000000000000000000000000000000000000000002c50cef195bd2a6"
                                + "00000000000000000000000000000000000000000000006a322f8f0812aefa8d"
                                + "0000000000000000000000000000000000000000000000000000000000000000"),
                new LogEntry(285, MULTISIG, List.of(EXECUTED,
                        "0x000000000000000000000000645ba1c8ab1cb65cf541f97a4f0cf30f58a471e4",
                        "0xc5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470"), "0x"));

        return new TxContext("0xb74ce488dfce325579e0791b9444b3bfbe07b68a0bd038690a445a8974e43f10",
                TxContext.TxStatus.SUCCESS, RELAYER, MULTISIG, realInput(), BigInteger.ZERO, 11852115L,
                Instant.ofEpochSecond(1791244032L), null, logs, List.of(), "blockscout", explorer);
    }

    private static String realInput() throws IOException {
        try (InputStream in = InvolvementAnalyzerTest.class.getResourceAsStream(
                "/fixtures/sepolia-0xb74ce488/rpc-transaction.json")) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"input\"\\s*:\\s*\"(0x[0-9a-f]+)\"").matcher(json);
            if (!m.find()) {
                throw new IllegalStateException("input not found in fixture");
            }
            return m.group(1);
        }
    }
}
