package com.arc_e_tect.gradle.apionly.transcriberj.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * RFC 8785, the JSON Canonicalization Scheme, over the maps, lists and scalars a
 * YAML or JSON parser produces.
 *
 * <p>This is what makes a component's hash a property of its content rather than
 * of how a bundler happened to format it.
 */
public final class CanonicalJson {

    private static final char BACKSLASH = 92;
    private static final char QUOTE = 34;

    private CanonicalJson() {
    }

    /**
     * The lower-case hex SHA-256 of a value's canonical form, encoded as UTF-8.
     *
     * @param value a map, list, string, number, boolean or {@code null}
     * @return the hash
     */
    public static String sha256(Object value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(write(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform provides SHA-256", e);
        }
    }

    /**
     * A value's canonical form.
     *
     * @param value a map with string keys, list, string, number, boolean or {@code null}
     * @return the canonical JSON text
     * @throws IllegalArgumentException for anything JSON cannot hold
     */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean b) {
            out.append(b);
        } else if (value instanceof String s) {
            string(s, out);
        } else if (value instanceof Number n) {
            out.append(number(n));
        } else if (value instanceof Map<?, ?> map) {
            List<String> keys = new ArrayList<>();
            for (Object key : map.keySet()) {
                if (!(key instanceof String s)) {
                    throw new IllegalArgumentException("a JSON object key must be a string, not " + key);
                }
                keys.add(s);
            }
            keys.sort(null);
            out.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) out.append(',');
                string(keys.get(i), out);
                out.append(':');
                write(map.get(keys.get(i)), out);
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) out.append(',');
                write(list.get(i), out);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("JSON cannot hold a " + value.getClass().getName());
        }
    }

    private static void string(String s, StringBuilder out) {
        out.append(QUOTE);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case QUOTE, BACKSLASH -> out.append(BACKSLASH).append(c);
                case 8 -> out.append(BACKSLASH).append('b');
                case 9 -> out.append(BACKSLASH).append('t');
                case 10 -> out.append(BACKSLASH).append('n');
                case 12 -> out.append(BACKSLASH).append('f');
                case 13 -> out.append(BACKSLASH).append('r');
                default -> {
                    if (c < 0x20) {
                        out.append(BACKSLASH).append('u').append(String.format("%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append(QUOTE);
    }

    /** A number as ECMAScript's Number.prototype.toString writes the IEEE double it is. */
    private static String number(Number n) {
        double d = n instanceof BigInteger big ? big.doubleValue()
                : n instanceof BigDecimal dec ? dec.doubleValue()
                : n.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("JSON cannot hold " + d);
        }
        if (d == 0) return "0";

        String sign = d < 0 ? "-" : "";
        double magnitude = Math.abs(d);
        // Java writes the shortest decimal that round-trips, but never fewer than two
        // digits; ECMAScript uses one when one round-trips.
        BigDecimal decimal = new BigDecimal(Double.toString(magnitude)).stripTrailingZeros();
        BigDecimal oneDigit = decimal.round(new MathContext(1, RoundingMode.HALF_EVEN));
        if (oneDigit.doubleValue() == magnitude) decimal = oneDigit.stripTrailingZeros();

        String digits = decimal.unscaledValue().toString();
        int k = digits.length();
        int e = k - decimal.scale();
        String text;
        if (k <= e && e <= 21) {
            text = digits + "0".repeat(e - k);
        } else if (0 < e && e <= 21) {
            text = digits.substring(0, e) + "." + digits.substring(e);
        } else if (-6 < e && e <= 0) {
            text = "0." + "0".repeat(-e) + digits;
        } else {
            int exponent = e - 1;
            String mantissa = k == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
            text = mantissa + "e" + (exponent < 0 ? "-" : "+") + Math.abs(exponent);
        }
        return sign + text;
    }
}
