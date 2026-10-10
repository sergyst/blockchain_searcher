package com.blockchainsearcher.abi;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Decodes event logs and simple return values using the Solidity ABI encoding rules. */
public final class AbiDecoder {

    private static final int WORD = 32;

    private AbiDecoder() {
    }

    /**
     * Decodes a log against a definition.
     *
     * @throws AbiDecodingException if topic0, the topic count or the data layout does not match
     */
    public static DecodedEvent decode(EventDefinition definition, List<String> topics, String data) {
        if (topics == null || topics.isEmpty() || !definition.topic0().equalsIgnoreCase(topics.get(0))) {
            throw new AbiDecodingException("topic0 does not match " + definition.canonicalSignature());
        }
        if (topics.size() - 1 != definition.indexedCount()) {
            throw new AbiDecodingException("expected " + definition.indexedCount() + " indexed topics, got "
                    + (topics.size() - 1));
        }
        byte[] body = Hex.toBytes(data == null ? "0x" : data);

        List<DecodedEvent.Value> values = new ArrayList<>();
        int topicIndex = 1;
        int headOffset = 0;
        for (EventDefinition.Param param : definition.params()) {
            Object value;
            if (param.indexed()) {
                byte[] topic = Hex.toBytes(topics.get(topicIndex++));
                value = isDynamic(param.type()) ? "0x" + Hex.toHex(topic) : decodeStatic(param.type(), topic, 0);
            } else {
                if (isDynamic(param.type())) {
                    int offset = toInt(word(body, headOffset));
                    value = decodeDynamic(param.type(), body, offset);
                } else {
                    value = decodeStatic(param.type(), body, headOffset);
                }
                headOffset += WORD;
            }
            values.add(new DecodedEvent.Value(param.name(), param.type(), param.indexed(), value));
        }
        return new DecodedEvent(definition, List.copyOf(values));
    }

    /** Decodes the return value of a {@code uint8 decimals()}-style call. */
    public static BigInteger decodeUintReturn(String returnData) {
        byte[] body = Hex.toBytes(returnData);
        if (body.length < WORD) {
            return null;
        }
        return new BigInteger(1, word(body, 0));
    }

    /**
     * Decodes the return value of {@code string symbol()}; also accepts the legacy
     * {@code bytes32 symbol()} encoding used by some older tokens.
     */
    public static String decodeStringReturn(String returnData) {
        byte[] body = Hex.toBytes(returnData);
        if (body.length == 0) {
            return null;
        }
        if (body.length == WORD) {
            int end = 0;
            while (end < WORD && body[end] != 0) {
                end++;
            }
            return new String(body, 0, end, StandardCharsets.UTF_8);
        }
        int offset = toInt(word(body, 0));
        return (String) decodeDynamic("string", body, offset);
    }

    static boolean isDynamic(String type) {
        return "string".equals(type) || "bytes".equals(type) || type.endsWith("[]");
    }

    private static Object decodeDynamic(String type, byte[] body, int offset) {
        int length = toInt(word(body, offset));
        int start = offset + WORD;
        if ("string".equals(type) || "bytes".equals(type)) {
            requireRange(body, start, length);
            byte[] raw = new byte[length];
            System.arraycopy(body, start, raw, 0, length);
            return "string".equals(type) ? new String(raw, StandardCharsets.UTF_8) : "0x" + Hex.toHex(raw);
        }
        String elementType = type.substring(0, type.length() - 2);
        if (isDynamic(elementType) || elementType.endsWith("]")) {
            throw new AbiDecodingException("unsupported array element type " + elementType);
        }
        List<Object> items = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            items.add(decodeStatic(elementType, body, start + i * WORD));
        }
        return items;
    }

    private static Object decodeStatic(String type, byte[] body, int offset) {
        byte[] w = word(body, offset);
        if ("address".equals(type)) {
            return Hex.checksum(Hex.addressFromWord(Hex.toHex(w)));
        }
        if ("bool".equals(type)) {
            return new BigInteger(1, w).signum() != 0;
        }
        if (type.startsWith("uint")) {
            return new BigInteger(1, w);
        }
        if (type.startsWith("int")) {
            return new BigInteger(w);
        }
        if (type.startsWith("bytes")) {
            int size = Integer.parseInt(type.substring("bytes".length()));
            byte[] raw = new byte[size];
            System.arraycopy(w, 0, raw, 0, size);
            return "0x" + Hex.toHex(raw);
        }
        throw new AbiDecodingException("unsupported type " + type);
    }

    private static byte[] word(byte[] body, int offset) {
        requireRange(body, offset, WORD);
        byte[] w = new byte[WORD];
        System.arraycopy(body, offset, w, 0, WORD);
        return w;
    }

    private static int toInt(byte[] word) {
        BigInteger value = new BigInteger(1, word);
        if (value.bitLength() > 31) {
            throw new AbiDecodingException("offset/length out of range: " + value);
        }
        return value.intValue();
    }

    private static void requireRange(byte[] body, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > body.length) {
            throw new AbiDecodingException("data too short: need " + (offset + length) + " bytes, have "
                    + body.length);
        }
    }
}
