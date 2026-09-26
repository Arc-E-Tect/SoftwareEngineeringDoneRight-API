package com.arc_e_tect.gradle.apionly.transcriberj.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The canonical value of each supported {@code format}, for a string with no {@code pattern}.
 *
 * <p>Each value is fixed and uses a documentation-reserved name, {@code example.com}.
 * {@code hostname}, {@code ipv4} and {@code ipv6} are not supported yet: a string of one
 * of them is a plain string, as for any other format not listed here. Where a format
 * allows it, the value is made longer or shorter to meet {@code minLength} and
 * {@code maxLength}; successive variants differ, for {@code uniqueItems}.
 */
final class Formats {

    /** The formats a value is generated for. */
    static final List<String> SUPPORTED = List.of("email", "uuid", "date", "date-time", "time", "uri",
            "uri-reference");

    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
            + "-[0-9a-fA-F]{12}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final Map<String, Predicate<String>> CHECKS = Map.of(
            "email", v -> EMAIL.matcher(v).matches(),
            "uuid", v -> UUID.matcher(v).matches(),
            "date", Formats::isDate,
            "date-time", Formats::isDateTime,
            "time", Formats::isTime,
            "uri", v -> uri(v, true),
            "uri-reference", v -> uri(v, false));

    private Formats() {
    }

    static boolean supported(String format) {
        return SUPPORTED.contains(format);
    }

    /** Whether a value is of a supported format; a format this class does not support accepts anything. */
    static boolean accepts(String format, String value) {
        Predicate<String> check = CHECKS.get(format);
        return check == null || check.test(value);
    }

    /** Values of the format with {@code min} to {@code max} code points, canonical first. */
    static Iterator<String> values(String format, int min, int max) {
        List<String> out = new ArrayList<>();
        for (int variant = 0; variant < 26 && out.size() < 26; variant++) {
            String value = value(format, variant, min, max);
            if (value != null && !out.contains(value)) out.add(value);
        }
        return out.iterator();
    }

    /** The canonical value's length when nothing constrains it: what a length keyword is compared with. */
    static String canonical(String format) {
        return value(format, 0, 0, Integer.MAX_VALUE);
    }

    private static String value(String format, int variant, int min, int max) {
        char letter = (char) ('a' + variant);
        String value = switch (format) {
            case "email" -> fit("user@example.com", "@example.com", 1, 64, min, max, letter);
            case "uuid" -> "00000000-0000-4000-8000-" + String.format(Locale.ROOT, "%012x", variant);
            case "date" -> String.format(Locale.ROOT, "2000-01-%02d", variant + 1);
            case "date-time" -> fraction(String.format(Locale.ROOT, "2000-01-01T00:00:%02d", variant), "Z", min, max);
            case "time" -> fraction(String.format(Locale.ROOT, "00:00:%02d", variant), "Z", min, max);
            case "uri", "uri-reference" -> path(variant, min, max);
            default -> throw new IllegalArgumentException("unsupported format " + format);
        };
        if (value == null) return null;
        int length = value.length();
        return length >= min && length <= max ? value : null;
    }

    /**
     * An address whose local part grows or shrinks to fit: {@code user@example.com}, then
     * {@code u@example.com} up to a local part of 64 letters.
     */
    private static String fit(String canonical, String domain, int shortest, int longest, int min, int max,
                              char letter) {
        int local = canonical.length() - domain.length();
        int want = Math.max(Math.min(local, max - domain.length()), min - domain.length());
        want = Math.max(Math.min(want, longest), shortest);
        String base = want == local ? canonical.substring(0, local)
                : want < local ? canonical.substring(0, want) : canonical.substring(0, local) + "a".repeat(want - local);
        if (letter != 'a') base = base.substring(0, base.length() - 1) + letter;
        return base + domain;
    }

    /** A time with as many fractional digits as it takes to reach the minimum length, up to nine. */
    private static String fraction(String base, String zone, int min, int max) {
        int plain = base.length() + zone.length();
        if (min <= plain) return base + zone;
        int digits = Math.max(min - plain - 1, 1);
        if (digits > 9) return null;
        return base + "." + "0".repeat(digits) + zone;
    }

    private static String path(int variant, int min, int max) {
        String root = "https://example.com";
        String value = root + "/" + (variant == 0 ? "" : String.valueOf((char) ('a' + variant)));
        if (value.length() > max && variant == 0) value = root;
        if (value.length() < min) value = value + "a".repeat(min - value.length());
        return value;
    }

    private static boolean isDate(String v) {
        try {
            LocalDate.parse(v, DateTimeFormatter.ISO_LOCAL_DATE);
            return v.length() == 10;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean isDateTime(String v) {
        try {
            OffsetDateTime.parse(v, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            return v.indexOf('T') == 10;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean isTime(String v) {
        try {
            OffsetTime.parse(v, DateTimeFormatter.ISO_OFFSET_TIME);
            return v.length() >= 9 && v.charAt(8) != ':';
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean uri(String v, boolean absolute) {
        try {
            URI uri = new URI(v);
            return !absolute || uri.isAbsolute();
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
