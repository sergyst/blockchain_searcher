package com.blockchainsearcher.abi;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A Solidity event, parsed from a human-readable signature such as
 * {@code Transfer(address indexed from, address indexed to, uint256 value)}.
 *
 * @param id                 stable key used by the analyzer to recognise well-known events
 * @param name               event name
 * @param params             parameters in declaration order
 * @param canonicalSignature {@code Name(type1,type2,...)}, the string that is hashed into topic0
 * @param topic0             keccak256 of the canonical signature, 0x-prefixed
 */
public record EventDefinition(String id, String name, List<Param> params, String canonicalSignature, String topic0) {

    public record Param(String name, String type, boolean indexed) {
    }

    public static EventDefinition parse(String id, String humanReadable) {
        String signature = humanReadable.trim();
        if (signature.startsWith("event ")) {
            signature = signature.substring("event ".length()).trim();
        }
        int open = signature.indexOf('(');
        int close = signature.lastIndexOf(')');
        if (open <= 0 || close < open) {
            throw new IllegalArgumentException("Not an event signature: " + humanReadable);
        }
        String name = signature.substring(0, open).trim();
        String inner = signature.substring(open + 1, close).trim();

        List<Param> params = new ArrayList<>();
        if (!inner.isEmpty()) {
            if (inner.contains("(")) {
                throw new IllegalArgumentException("Tuple parameters are not supported: " + humanReadable);
            }
            int position = 0;
            for (String raw : inner.split(",")) {
                String[] tokens = raw.trim().split("\\s+");
                String type = normalizeType(tokens[0]);
                boolean indexed = false;
                String paramName = null;
                for (int i = 1; i < tokens.length; i++) {
                    if ("indexed".equals(tokens[i])) {
                        indexed = true;
                    } else {
                        paramName = tokens[i];
                    }
                }
                params.add(new Param(paramName != null ? paramName : "arg" + position, type, indexed));
                position++;
            }
        }
        String canonical = name + "(" + params.stream().map(Param::type).collect(Collectors.joining(",")) + ")";
        return new EventDefinition(id, name, List.copyOf(params), canonical, Keccak256.hashHex(canonical));
    }

    public int indexedCount() {
        return (int) params.stream().filter(Param::indexed).count();
    }

    private static String normalizeType(String type) {
        if ("uint".equals(type)) {
            return "uint256";
        }
        if ("int".equals(type)) {
            return "int256";
        }
        if (type.startsWith("uint[") || type.startsWith("int[")) {
            return type.replaceFirst("^(u?int)\\[", "$1256[");
        }
        return type;
    }
}
