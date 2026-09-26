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
 * <p>Each value is fixed and uses documentation-reserved names and addresses, so that
 * nothing a test sends ever reaches a real host: {@code example.com},
 * {@code 192.0.2.0/24} (TEST-NET-1) and {@code 2001:db8::/32}. Where a format
 * allows it, the value is made longer or shorter to meet {@code minLength} and
 * {@code maxLength}; successive variants differ, for {@code uniqueItems}.
 */
final class Formats {

    /** The formats a value is generated for. */
    static final List<String> SUPPORTED = List.of("email", "uuid", "date", "date-time", "time", "uri",
            "uri-reference", "hostname", "ipv4", "ipv6");

    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
            + "-[0-9a-fA-F]{12}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?");
    private static final Pattern IPV4 = Pattern.compile("((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}"
            + "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:]+");
    private static final Map<String, Predicate<String>> CHECKS = Map.of(
            "email", v -> EMAIL.matcher(v).matches(),
            "uuid", v -> UUID.matcher(v).matches(),
            "date", Formats::isDate,
            "date-time", Formats::isDateTime,
            "time", Formats::isTime,
            "uri", v -> uri(v, true),
            "uri-reference", v -> uri(v, false),
            "hostname", Formats::isHostname,
            "ipv4", v -> IPV4.matcher(v).matches(),
            "ipv6", v -> IPV6.matcher(v).matches() && (v.contains("::") || v.chars().filter(c -> c == ':').count() == 7));

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
            case "hostname" -> hostname(letter, min, max);
            case "ipv4" -> ipv4(variant, min, max);
            case "ipv6" -> ipv6(variant, min, max);
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

    /**
     * {@code example.com}, or a subdomain of it as long as it takes: letters, with a dot
     * every 62 of them, so that no label is longer than DNS allows.
     */
    private static String hostname(char letter, int min, int max) {
        String domain = "example.com";
        if (letter == 'a' && min <= domain.length()) return domain;
        int length = Math.max(min - domain.length() - 1, 1);
        if (length + 1 + domain.length() > max) return null;
        char[] prefix = "a".repeat(length).toCharArray();
        prefix[0] = letter;
        for (int i = 62; i < length - 1; i += 63) prefix[i] = '.';
        return new String(prefix) + "." + domain;
    }

    /** An address in 192.0.2.0/24, TEST-NET-1: the variant-th whose length fits. */
    private static String ipv4(int variant, int min, int max) {
        for (int host = 1; host < 255; host++) {
            String value = "192.0.2." + host;
            if (value.length() >= min && value.length() <= max && variant-- == 0) return value;
        }
        return null;
    }

    /** {@code 2001:db8::} and a last group of one to four hex digits: the variant-th that fits the length. */
    private static String ipv6(int variant, int min, int max) {
        String prefix = "2001:db8::";
        for (int digits = 1; digits <= 4; digits++) {
            int length = prefix.length() + digits;
            if (length < min || length > max) continue;
            int first = digits == 1 ? 1 : 1 << (4 * (digits - 1));
            int count = (1 << (4 * digits)) - first;
            if (variant < count) return prefix + Integer.toHexString(first + variant);
            variant -= count;
        }
        return null;
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

    private static boolean isHostname(String v) {
        if (v.length() > 253) return false;
        for (String label : v.split("\\.", -1)) {
            if (!LABEL.matcher(label).matches()) return false;
        }
        return true;
    }
}
