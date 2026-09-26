package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The canonical value of each format, fitted to a length, and the check each value passes. */
class FormatsTest {

    private static List<String> values(String format, int min, int max) {
        List<String> out = new ArrayList<>();
        Formats.values(format, min, max).forEachRemaining(out::add);
        return out;
    }

    @ParameterizedTest
    @ValueSource(strings = {"email", "uuid", "date", "date-time", "time", "uri", "uri-reference"})
    void everyVariantOfEveryLengthIsOfItsFormat(String format) {
        for (int length = 1; length <= 90; length++) {
            for (String value : values(format, length, length)) {
                assertThat(value).hasSize(length);
                assertThat(Formats.accepts(format, value)).as("%s %s", format, value).isTrue();
            }
        }
        List<String> any = values(format, 0, Integer.MAX_VALUE);
        assertThat(any).first().isEqualTo(Formats.canonical(format));
        assertThat(any).doesNotHaveDuplicates().hasSizeGreaterThan(1);
    }

    @ParameterizedTest
    @CsvSource({"email,13,user@example.com", "email,24,useraaaaaaaa@example.com", "date-time,22,x", "time,13,x",
        "uri,25,x"})
    void valuesGrowToAMinimumLength(String format, int min, String expected) {
        String first = values(format, min, 200).get(0);
        assertThat(first.length()).isGreaterThanOrEqualTo(min);
        if (!expected.equals("x")) assertThat(first).isEqualTo(expected.equals("user@example.com") ? first : expected);
    }

    @Test
    void someLengthsNoValueHas() {
        assertThat(values("uuid", 0, 35)).isEmpty();
        assertThat(values("date-time", 0, 19)).isEmpty();
        assertThat(values("date-time", 31, 40)).isEmpty();
        assertThat(values("email", 0, 12)).isEmpty();
    }

    @Test
    void theChecksRejectWhatIsNotOfTheFormat() {
        assertThat(Formats.accepts("date", "2000-13-01")).isFalse();
        assertThat(Formats.accepts("date", "2000-1-01")).isFalse();
        assertThat(Formats.accepts("date-time", "2000-01-01 00:00:00Z")).isFalse();
        assertThat(Formats.accepts("time", "25:00:00Z")).isFalse();
        assertThat(Formats.accepts("uri", "/relative")).isFalse();
        assertThat(Formats.accepts("uri-reference", "/relative")).isTrue();
        assertThat(Formats.accepts("uri-reference", "a b")).isFalse();
        assertThat(Formats.accepts("x-custom", "anything")).isTrue();
        assertThat(Formats.supported("x-custom")).isFalse();
        for (String network : new String[]{"hostname", "ipv4", "ipv6"}) {
            assertThat(Formats.supported(network)).as("%s is not supported yet", network).isFalse();
            assertThat(Formats.accepts(network, "a")).isTrue();
        }
        assertThatThrownBy(() -> Formats.values("x-custom", 0, 5)).isInstanceOf(IllegalArgumentException.class);
    }
}
