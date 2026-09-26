package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T13.4: a type case sends a value no lenient binder coerces. Each value is bound with a
 * default-configured Jackson {@code ObjectMapper} into the matching Java type, and binding
 * fails; the coercible values it avoids bind, which is why they are avoided.
 */
@DisplayName("T13.4 Non-coercible types")
class NonCoercibleTypesTest {

    private static final ObjectMapper JACKSON = new ObjectMapper();

    record Text(String value) {
    }

    record Count(Integer value) {
    }

    record Amount(BigDecimal value) {
    }

    record Flag(Boolean value) {
    }

    record Members(Map<String, Object> value) {
    }

    record Items(List<Object> value) {
    }

    @Test
    void eachPrimitiveTypeGetsItsNonCoercibleValue() {
        assertThat(ValueJson.write(InvalidRequests.nonCoercible("string"))).isEqualTo("{}");
        assertThat(InvalidRequests.nonCoercible("integer")).isEqualTo("not-a-number");
        assertThat(InvalidRequests.nonCoercible("number")).isEqualTo("not-a-number");
        assertThat(InvalidRequests.nonCoercible("boolean")).isEqualTo("not-a-boolean");
        assertThat(ValueJson.write(InvalidRequests.nonCoercible("object"))).isEqualTo("[]");
        assertThat(ValueJson.write(InvalidRequests.nonCoercible("array"))).isEqualTo("{}");
    }

    @Test
    void aDefaultObjectMapperRefusesEveryOne() {
        refuses(Text.class, "string");
        refuses(Count.class, "integer");
        refuses(Amount.class, "number");
        refuses(Flag.class, "boolean");
        refuses(Members.class, "object");
        refuses(Items.class, "array");
    }

    @Test
    void theCoercibleValuesItAvoidsBind() throws Exception {
        assertThat(JACKSON.readValue("{\"value\":5}", Text.class).value()).isEqualTo("5");
        assertThat(JACKSON.readValue("{\"value\":1.5}", Count.class).value()).isEqualTo(1);
        assertThat(JACKSON.readValue("{\"value\":\"5\"}", Amount.class).value()).isEqualByComparingTo("5");
        assertThat(JACKSON.readValue("{\"value\":\"true\"}", Flag.class).value()).isTrue();
    }

    @Test
    void theCorpusTypeCasesSendThem(@TempDir Path directory) {
        List<JsonNode> cases = InvalidRequestOracleTest.cases(InvalidRequestFixtures.corpus("keywords", directory));
        assertThat(cases.stream().filter(c -> c.get("id").stringValue().equals("body-text-type")).findFirst()
                .orElseThrow().get("request").get("body").get("text").isObject()).isTrue();
        assertThat(cases.stream().filter(c -> c.get("id").stringValue().equals("body-count-type")).findFirst()
                .orElseThrow().get("request").get("body").get("count").stringValue()).isEqualTo("not-a-number");
        assertThat(cases.stream().filter(c -> c.get("id").stringValue().equals("body-items-type")).findFirst()
                .orElseThrow().get("request").get("body").get("items").isObject()).isTrue();
        assertThat(cases.stream().filter(c -> c.get("id").stringValue().equals("body-nested-type")).findFirst()
                .orElseThrow().get("request").get("body").get("nested").isArray()).isTrue();
    }

    private static void refuses(Class<?> type, String schemaType) {
        String json = "{\"value\":" + ValueJson.write(InvalidRequests.nonCoercible(schemaType)) + "}";
        assertThatThrownBy(() -> JACKSON.readValue(json, type)).as("binding %s into %s", json, type.getSimpleName())
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThatCode(() -> JACKSON.readTree(json)).doesNotThrowAnyException();
    }
}
