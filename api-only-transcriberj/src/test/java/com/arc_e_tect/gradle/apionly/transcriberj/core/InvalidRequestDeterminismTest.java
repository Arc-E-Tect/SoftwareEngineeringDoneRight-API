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
 * T13.15: the invalid-request corpus and the user-account contract give byte-identical
 * sources and reports on every run: twice in one JVM, and under another default locale and
 * time zone.
 */
@DisplayName("T13.15 Determinism")
class InvalidRequestDeterminismTest {

    @TempDir
    Path directory;

    private Map<String, String> generate(Path into) {
        Map<String, String> out = new TreeMap<>();
        java.util.List<ValidValueFixtures.Fixture> fixtures = new java.util.ArrayList<>();
        for (InvalidRequestFixtures.Variant v : InvalidRequestFixtures.CORPUS) {
            fixtures.add(InvalidRequestFixtures.corpus(v.name(), into.resolve(v.name())));
        }
        fixtures.add(ValidValueFixtures.reference("user-account", into.resolve("user-account")));
        for (ValidValueFixtures.Fixture f : fixtures) {
            out.put(f.name() + ".txt", f.text());
            out.put(f.name() + ".json", f.sources().report.renderValidValues(f.name(), "1.0.0"));
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
        assertThat(first.keySet()).anyMatch(k -> k.endsWith("InvalidRequests.java"));
        assertThat(generate(directory.resolve("second"))).isEqualTo(first);
    }

    @Test
    void anotherLocaleAndTimeZoneGiveTheSameBytes() {
        Map<String, String> reference = generate(directory.resolve("reference"));
        Locale locale = Locale.getDefault();
        TimeZone zone = TimeZone.getDefault();
        try {
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
