package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** JSON values as generation holds, compares and writes them. */
class ValueJsonTest {

    @Test
    void parsedValuesBecomeExactOnes() {
        Map<Object, Object> map = new LinkedHashMap<>();
        map.put("n", null);
        map.put("d", 0.1);
        map.put("i", new BigInteger("123456789012345678901234567890"));
        map.put("x", new BigDecimal("1.50"));
        map.put("l", Arrays.asList(1L, "s", true));
        Object value = ValueJson.of(map);
        assertThat(ValueJson.write(value))
                .isEqualTo("{\"n\":null,\"d\":0.1,\"i\":123456789012345678901234567890,\"x\":1.5,\"l\":[1,\"s\",true]}");
        assertThat(ValueJson.of(null)).isSameAs(ValueJson.NULL);
        assertThat(ValueJson.write(null)).isEqualTo("null");
    }

    @Test
    void equalityIsJsonEquality() {
        assertThat(ValueJson.equal(ValueJson.of(1), ValueJson.of(1.0))).isTrue();
        assertThat(ValueJson.equal(ValueJson.of(List.of(1, 2)), ValueJson.of(List.of(1, 2)))).isTrue();
        assertThat(ValueJson.equal(ValueJson.of(List.of(1, 2)), ValueJson.of(List.of(2, 1)))).isFalse();
        assertThat(ValueJson.equal(ValueJson.of(List.of(1)), ValueJson.of(List.of(1, 2)))).isFalse();
        assertThat(ValueJson.equal(ValueJson.of(Map.of("a", 1, "b", 2)), ValueJson.of(Map.of("b", 2, "a", 1)))).isTrue();
        assertThat(ValueJson.equal(ValueJson.of(Map.of("a", 1)), ValueJson.of(Map.of("a", 2)))).isFalse();
        assertThat(ValueJson.equal(ValueJson.of(Map.of("a", 1)), ValueJson.of(Map.of("b", 1)))).isFalse();
        assertThat(ValueJson.equal("1", ValueJson.of(1))).isFalse();
    }

    @Test
    void typesAreJsonSchemaTypes() {
        assertThat(ValueJson.type(ValueJson.of(2.0))).isEqualTo("integer");
        assertThat(ValueJson.type(ValueJson.of(0))).isEqualTo("integer");
        assertThat(ValueJson.type(ValueJson.of(2.5))).isEqualTo("number");
        assertThat(ValueJson.type(ValueJson.NULL)).isEqualTo("null");
        assertThat(ValueJson.type(List.of())).isEqualTo("array");
        assertThat(ValueJson.type(Map.of())).isEqualTo("object");
        assertThat(ValueJson.type(true)).isEqualTo("boolean");
        assertThat(ValueJson.type("s")).isEqualTo("string");
    }

    @Test
    void numbersAreWrittenPlainAndStringsEscapedAsContractJsonEscapesThem() {
        assertThat(ValueJson.number(new BigDecimal("1E+3"))).isEqualTo("1000");
        assertThat(ValueJson.number(new BigDecimal("0.000"))).isEqualTo("0");
        assertThat(ValueJson.write("q\"b\\\b\t\n\f\r\u0001😀é"))
                .isEqualTo("\"q\\\"b\\\\\\b\\t\\n\\f\\r\\u0001😀é\"");
    }
}
