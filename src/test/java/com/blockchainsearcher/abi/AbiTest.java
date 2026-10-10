package com.blockchainsearcher.abi;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AbiTest {

    @Test
    void keccakMatchesKnownVectors() {
        assertThat(Keccak256.hashHex(""))
                .isEqualTo("0xc5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470");
        assertThat(Keccak256.hashHex("Transfer(address,address,uint256)"))
                .isEqualTo("0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef");
    }

    @Test
    void dividendEventTopicMatchesOnChainTopic() {
        EventDefinition dividend = EventRegistry.withBuiltIns().definitions().stream()
                .filter(d -> EventRegistry.DIVIDEND_DISTRIBUTED.equals(d.id()))
                .findFirst().orElseThrow();

        assertThat(dividend.canonicalSignature())
                .isEqualTo("DividendDistributed(address,uint256,int256,uint256,uint256,uint256,uint256,bool)");
        assertThat(dividend.topic0())
                .isEqualTo("0xe0b019f23e4f4948c15bdd9dfa8808b046568a2fda0f2978492dcc284fb79c9a");
    }

    @Test
    void decodesTheBenjiDividendLogOfTheSepoliaTransaction() {
        // log #280 of 0xb74ce488…3f10, copied from the receipt
        List<String> topics = List.of(
                "0xe0b019f23e4f4948c15bdd9dfa8808b046568a2fda0f2978492dcc284fb79c9a",
                "0x0000000000000000000000006b017cb1a9a13d05b6ac24e978e0e6e683b418fa",
                "0x000000000000000000000000000000000000000000000000000000006ac40fd0");
        String data = "0x"
                + "00000000000000000000000000000000000000000000000000005ca8c935b000"
                + "0000000000000000000000000000000000000000000000000de0b6b3a7640000"
                + "00000000000000000000000000000000000000000000000002c50cef195bd2a6"
                + "00000000000000000000000000000000000000000000000002c50cef195bd2a6"
                + "00000000000000000000000000000000000000000000006a322f8f0812aefa8d"
                + "0000000000000000000000000000000000000000000000000000000000000000";

        DecodedEvent event = EventRegistry.withBuiltIns().decode(topics, data).orElseThrow();

        assertThat(event.definition().id()).isEqualTo(EventRegistry.DIVIDEND_DISTRIBUTED);
        assertThat(event.value("account")).isEqualTo("0x6B017Cb1a9A13d05b6ac24e978E0e6E683B418fa");
        assertThat(event.value("date")).isEqualTo(BigInteger.valueOf(1791234000L));
        assertThat(event.value("rate")).isEqualTo(new BigInteger("101880000000000"));
        assertThat(event.value("price")).isEqualTo(new BigInteger("1000000000000000000"));
        assertThat(event.value("shares")).isEqualTo(new BigInteger("199579979550020262"));
        assertThat(event.value("dividendCashAmount")).isEqualTo(new BigInteger("199579979550020262"));
        assertThat(event.value("dividendBasis")).isEqualTo(new BigInteger("1958971138103850236557"));
        assertThat(event.value("isNegativeYield")).isEqualTo(false);
    }

    @Test
    void erc20AndErc721TransfersAreToldApartByTopicCount() {
        EventRegistry registry = EventRegistry.withBuiltIns();
        String topic0 = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
        String from = "0x" + "0".repeat(64);
        String to = "0x0000000000000000000000006b017cb1a9a13d05b6ac24e978e0e6e683b418fa";
        String word = "0x" + "0".repeat(63) + "7";

        assertThat(registry.decode(List.of(topic0, from, to), word).orElseThrow().definition().id())
                .isEqualTo(EventRegistry.ERC20_TRANSFER);
        assertThat(registry.decode(List.of(topic0, from, to, word), "0x").orElseThrow().definition().id())
                .isEqualTo(EventRegistry.ERC721_TRANSFER);
    }

    @Test
    void checksumsAddresses() {
        assertThat(Hex.checksum("0x6b017cb1a9a13d05b6ac24e978e0e6e683b418fa"))
                .isEqualTo("0x6B017Cb1a9A13d05b6ac24e978E0e6E683B418fa");
    }

    @Test
    void decodesStringAndBytes32SymbolReturns() {
        String abiString = "0x"
                + "0000000000000000000000000000000000000000000000000000000000000020"
                + "0000000000000000000000000000000000000000000000000000000000000004"
                + "46554e4400000000000000000000000000000000000000000000000000000000";
        String bytes32 = "0x4d4b520000000000000000000000000000000000000000000000000000000000";

        assertThat(AbiDecoder.decodeStringReturn(abiString)).isEqualTo("FUND");
        assertThat(AbiDecoder.decodeStringReturn(bytes32)).isEqualTo("MKR");
    }
}
