package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T12.4: the same contract gives byte-identical sources and reports on every run: twice
 * in one JVM, and under another default locale and time zone.
 */
@DisplayName("T12.4 Determinism")
class DeterminismTest {

    @TempDir
    Path directory;

    /** Every file generation writes, by path, and both reports of every fixture contract. */
    private Map<String, String> generate(Path into) {
        Map<String, String> out = new TreeMap<>();
        for (ValidValueFixtures.Fixture f : ValidValueFixtures.all(into)) {
            out.put(f.name() + ".txt", f.text());
            out.put(f.name() + ".valid-values.json", f.sources().report.renderValidValues(f.name(), "1.0.0"));
            try (Stream<Path> files = Files.walk(f.sources().sources)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    out.put(f.name() + "/" + f.sources().sources.relativize(file), Files.readString(file));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }

    @Test
    void twiceInOneJvmGivesTheSameBytes() {
        Map<String, String> first = generate(directory.resolve("first"));
        Map<String, String> second = generate(directory.resolve("second"));
        assertThat(first).hasSizeGreaterThan(200);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void anotherLocaleAndTimeZoneGiveTheSameBytes() {
        Map<String, String> reference = generate(directory.resolve("reference"));
        Locale locale = Locale.getDefault();
        TimeZone zone = TimeZone.getDefault();
        try {
            // Turkish, for its dotless i in case conversions; Arabic, for its digits; a
            // time zone far from UTC, on a half hour.
            for (Locale other : new Locale[]{Locale.forLanguageTag("tr-TR"), Locale.forLanguageTag("ar-EG-u-nu-arab")}) {
                Locale.setDefault(other);
                TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Chatham"));
                assertThat(generate(directory.resolve(other.toLanguageTag()))).as(other.toLanguageTag())
                        .isEqualTo(reference);
            }
        } finally {
            Locale.setDefault(locale);
            TimeZone.setDefault(zone);
        }
    }
}
