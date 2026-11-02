package com.xzxg.shop.common;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Serializes times the way Go's {@code encoding/json} does for {@code time.Time}:
 * RFC3339Nano with the local offset, and {@code 0001-01-01T00:00:00Z} for the zero
 * value (Go's {@code omitempty} does not omit structs such as time.Time).
 */
public final class GoTime {

    private static final LocalDateTime ZERO = LocalDateTime.of(1, 1, 1, 0, 0);
    private static final DateTimeFormatter BASE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter OFFSET = DateTimeFormatter.ofPattern("XXX");

    private GoTime() {
    }

    public static String format(LocalDateTime value) {
        if (value == null || ZERO.equals(value)) {
            return "0001-01-01T00:00:00Z";
        }
        ZoneId zone = ZoneId.systemDefault();
        ZoneOffset offset = zone.getRules().getOffset(value);
        StringBuilder out = new StringBuilder(BASE.format(value));
        int nanos = value.getNano();
        if (nanos > 0) {
            String fraction = String.format("%09d", nanos).replaceAll("0+$", "");
            out.append('.').append(fraction);
        }
        return out.append(OFFSET.format(offset)).toString();
    }

    public static LocalDateTime zero() {
        return ZERO;
    }
}
