package com.arc_e_tect.gradle.apionly.transcriberj.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RFC 8785, checked against the RFC's own examples. Every number below was also
 * confirmed against Node's Number.prototype.toString, which the RFC defers to.
 */
class CanonicalJsonTest {

    private static final char BACKSLASH = 92;
    private static final char QUOTE = 34;

    private static String chars(int... codes) {
        StringBuilder out = new StringBuilder();
        for (int code : codes) out.append((char) code);
        return out.toString();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "0000000000000000, 0",
            "8000000000000000, 0",
            "0000000000000001, 5e-324",
            "7fefffffffffffff, 1.7976931348623157e+308",
            "4340000000000000, 9007199254740992",
            "4430000000000000, 295147905179352830000",
            "44b52d02c7e14af5, 9.999999999999997e+22",
            "44b52d02c7e14af6, 1e+23",
            "3eb0c6f7a0b5ed8d, 0.000001",
            "3eb0c6f7a0b5ed8c, 9.999999999999997e-7",
            "41b3de4355555555, 333333333.3333333",
            "444b1ae4d6e2ef4e, 999999999999999700000",
            "444b1ae4d6e2ef4f, 999999999999999900000",
            "444b1ae4d6e2ef50, 1e+21",
            "3ee4f8b588e368f1, 0.00001",
            "3ed6849b86a12b9b, 0.00000536870912",
    })
    void numbersAreWrittenAsEcmaScriptWritesThem(String bits, String expected) {
        double value = Double.longBitsToDouble(Long.parseUnsignedLong(bits, 16));
        assertThat(CanonicalJson.write(value)).isEqualTo(expected);
    }

    @Test
    void integersOfEveryWidthAreNumbersToo() {
        assertThat(CanonicalJson.write(404)).isEqualTo("404");
        assertThat(CanonicalJson.write(-7L)).isEqualTo("-7");
        assertThat(CanonicalJson.write(new BigInteger("100000000000000000000000"))).isEqualTo("1e+23");
    }

    @Test
    void theRfcExampleIsCanonicalisedExactly() {
        // The RFC's input string: euro sign, dollar, U+000F, newline, A, apostrophe,
        // B, quote, backslash, backslash, quote, slash.
        String string = chars(0x20ac, '$', 0x0f, 0x0a, 'A', '\'', 'B', QUOTE, BACKSLASH, BACKSLASH, QUOTE, '/');
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("numbers", List.of(333333333.33333329, 1E30, 4.50, 2e-3, 0.000000000000000000000000001));
        input.put("string", string);
        input.put("literals", Arrays.asList(null, true, false));

        String expectedString = chars(0x20ac, '$') + BACKSLASH + "u000f" + BACKSLASH + "nA'B"
                + BACKSLASH + QUOTE + BACKSLASH + BACKSLASH + BACKSLASH + BACKSLASH + BACKSLASH + QUOTE + "/";
        assertThat(CanonicalJson.write(input)).isEqualTo(
                "{\"literals\":[null,true,false],\"numbers\":[333333333.3333333,1e+30,4.5,0.002,1e-27],"
                        + "\"string\":\"" + expectedString + "\"}");
    }

    @Test
    void keysAreSortedByUtf16CodeUnitsAndEveryShortEscapeIsUsed() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put(chars(0xe9), 1);
        input.put("b", chars(0x08, 0x09, 0x0a, 0x0c, 0x0d, 0x01));
        input.put("a", Map.of());

        String escaped = BACKSLASH + "b" + BACKSLASH + "t" + BACKSLASH + "n" + BACKSLASH + "f"
                + BACKSLASH + "r" + BACKSLASH + "u0001";
        assertThat(CanonicalJson.write(input))
                .isEqualTo("{\"a\":{},\"b\":\"" + escaped + "\",\"" + chars(0xe9) + "\":1}");
    }

    @Test
    void theHashIsTheLowerCaseSha256OfTheCanonicalForm() {
        assertThat(CanonicalJson.sha256(Map.of()))
                .isEqualTo("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a");
    }

    @Test
    void whatJsonCannotHoldIsRefused() {
        assertThatThrownBy(() -> CanonicalJson.write(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.write(Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.write(new Object())).isInstanceOf(IllegalArgumentException.class);
    }
}
