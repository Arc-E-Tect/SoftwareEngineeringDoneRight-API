package com.arc_e_tect.gradle.apionly.transcriberj;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The reference API's user-account contract, as the plugin tests use it: the document, the
 * version it states, and its text stating another version, for a test that publishes it as one.
 */
final class ReferenceContract {

    /** The document, as the reference API builds it. */
    static final Path USER_ACCOUNT = Path.of(System.getProperty("transcriberj.referenceApi"),
            "user-account/openapi.yaml");

    private static final Pattern VERSION_LINE = Pattern.compile("(?m)^  version: (.*)$");

    /** The version the document states in {@code info.version}. */
    static final String VERSION = version();

    private ReferenceContract() {
    }

    /** The document's text, stating the given version instead of its own. */
    static String at(String version) {
        return VERSION_LINE.matcher(text()).replaceFirst("  version: " + Matcher.quoteReplacement(version));
    }

    private static String version() {
        Matcher m = VERSION_LINE.matcher(text());
        if (!m.find()) throw new IllegalStateException(USER_ACCOUNT + " states no info.version");
        return m.group(1).strip();
    }

    private static String text() {
        try {
            return Files.readString(USER_ACCOUNT);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
