package com.blockchainsearcher.abi;

import java.util.List;
import java.util.Optional;

/**
 * An event log decoded against an {@link EventDefinition}.
 * Values are typed: address → checksummed String, (u)intN → BigInteger, bool → Boolean,
 * bytes/bytesN → 0x-hex String, string → String, T[] → List. Indexed dynamic values
 * cannot be recovered from a log and are returned as their topic hash.
 */
public record DecodedEvent(EventDefinition definition, List<Value> values) {

    public record Value(String name, String type, boolean indexed, Object value) {
    }

    public Optional<Value> get(String name) {
        return values.stream().filter(v -> v.name().equals(name)).findFirst();
    }

    public Object value(String name) {
        return get(name).map(Value::value).orElse(null);
    }
}
