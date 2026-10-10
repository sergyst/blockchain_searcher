package com.blockchainsearcher.abi;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/** Hex, quantity and address helpers for EVM data. */
public final class Hex {

    public static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

    private static final Pattern ADDRESS = Pattern.compile("^0x[0-9a-fA-F]{40}$");
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() {
    }

    public static String strip0x(String hex) {
        if (hex == null) {
            return "";
        }
        return hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
    }

    public static byte[] toBytes(String hex) {
        String clean = strip0x(hex);
        if (clean.length() % 2 != 0) {
            clean = "0" + clean;
        }
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = DIGITS[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = DIGITS[bytes[i] & 0xF];
        }
        return new String(out);
    }

    /** Parses a JSON-RPC quantity ("0x1a") or a plain decimal string. */
    public static BigInteger quantity(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.startsWith("0x") || value.startsWith("0X")) {
            String clean = strip0x(value);
            return clean.isEmpty() ? BigInteger.ZERO : new BigInteger(clean, 16);
        }
        return new BigInteger(value);
    }

    public static boolean isAddress(String value) {
        return value != null && ADDRESS.matcher(value).matches();
    }

    /** Lower-case, 0x-prefixed address, or null. */
    public static String normalizeAddress(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        return "0x" + strip0x(address).toLowerCase(Locale.ROOT);
    }

    /** EIP-55 mixed-case checksum encoding. */
    public static String checksum(String address) {
        if (address == null) {
            return null;
        }
        String lower = strip0x(address).toLowerCase(Locale.ROOT);
        String hash = toHex(Keccak256.hash(lower.getBytes(StandardCharsets.US_ASCII)));
        StringBuilder out = new StringBuilder("0x");
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            out.append(Character.digit(hash.charAt(i), 16) >= 8 ? Character.toUpperCase(ch) : ch);
        }
        return out.toString();
    }

    /** The 64-hex-char topic/word form of an address (left-padded with zeros, no 0x). */
    public static String addressAsWord(String address) {
        return "000000000000000000000000" + strip0x(normalizeAddress(address));
    }

    /** Address held in the last 20 bytes of a 32-byte word (hex, with or without 0x). */
    public static String addressFromWord(String word) {
        String clean = strip0x(word);
        return "0x" + clean.substring(clean.length() - 40).toLowerCase(Locale.ROOT);
    }
}
