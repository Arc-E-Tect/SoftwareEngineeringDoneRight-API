package com.arc_e_tect.gradle.apionly.transcriberj.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON values as generation holds them: a {@link String}, a {@link BigDecimal}, a
 * {@link Boolean}, {@link #NULL}, a {@code List} or a {@code Map} in member order.
 *
 * <p>Every number is a {@code BigDecimal}, so that {@code multipleOf: 0.1} is exact, and
 * JSON {@code null} is {@link #NULL}, since a Java {@code null} would mean "no value".
 */
final class ValueJson {

    /** JSON {@code null}. */
    enum Null {
        /** The only one. */
        NULL
    }

    static final Null NULL = Null.NULL;

    private static final char QUOTE = 34;
    private static final char BACKSLASH = 92;

    private ValueJson() {
    }

    /** A value as the contract parser read it, in the form generation holds values in. */
    static Object of(Object parsed) {
        if (parsed == null) return NULL;
        if (parsed instanceof BigDecimal d) return d;
        if (parsed instanceof BigInteger i) return new BigDecimal(i);
        if (parsed instanceof Double || parsed instanceof Float) return new BigDecimal(parsed.toString());
        if (parsed instanceof Number n) return BigDecimal.valueOf(n.longValue());
        if (parsed instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(v -> out.add(of(v)));
            return Collections.unmodifiableList(out);
        }
        if (parsed instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), of(v)));
            return Collections.unmodifiableMap(out);
        }
        return parsed;
    }

    /** Whether two values are the same JSON value: numbers by value, objects regardless of member order. */
    static boolean equal(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y) == 0;
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) {
                if (!equal(x.get(i), y.get(i))) return false;
            }
            return true;
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (!x.keySet().equals(y.keySet())) return false;
            for (Object key : x.keySet()) {
                if (!equal(x.get(key), y.get(key))) return false;
            }
            return true;
        }
        return a.equals(b);
    }

    /** The JSON type of a value, as {@code type} names it; a whole number is an {@code integer}. */
    static String type(Object value) {
        if (value instanceof String) return "string";
        if (value instanceof Boolean) return "boolean";
        if (value == NULL) return "null";
        if (value instanceof List) return "array";
        if (value instanceof Map) return "object";
        return isInteger((BigDecimal) value) ? "integer" : "number";
    }

    static boolean isInteger(BigDecimal value) {
        return value.signum() == 0 || value.stripTrailingZeros().scale() <= 0;
    }

    /** Compact JSON, members in their order, strings escaped as the generated {@code ContractJson} escapes them. */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    /** A number as JSON writes it: plain, never in exponent form, without trailing zeros. */
    static String number(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == NULL || value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            string(s, out);
        } else if (value instanceof BigDecimal n) {
            out.append(number(n));
        } else if (value instanceof Boolean b) {
            out.append(b);
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) out.append(',');
                write(list.get(i), out);
            }
            out.append(']');
        } else {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> member : ((Map<?, ?>) value).entrySet()) {
                if (!first) out.append(',');
                first = false;
                string((String) member.getKey(), out);
                out.append(':');
                write(member.getValue(), out);
            }
            out.append('}');
        }
    }

    private static void string(String value, StringBuilder out) {
        out.append(QUOTE);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case QUOTE, BACKSLASH -> out.append(BACKSLASH).append(c);
                case 8 -> out.append(BACKSLASH).append('b');
                case 9 -> out.append(BACKSLASH).append('t');
                case 10 -> out.append(BACKSLASH).append('n');
                case 12 -> out.append(BACKSLASH).append('f');
                case 13 -> out.append(BACKSLASH).append('r');
                default -> {
                    if (c < 0x20) {
                        out.append(BACKSLASH).append('u').append(String.format(java.util.Locale.ROOT, "%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append(QUOTE);
    }
}
