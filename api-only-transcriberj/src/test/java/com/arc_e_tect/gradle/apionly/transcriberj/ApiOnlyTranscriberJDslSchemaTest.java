package com.arc_e_tect.gradle.apionly.transcriberj;

import com.arc_e_tect.gradle.dslupdater.DslPropertyKind;
import com.arc_e_tect.gradle.dslupdater.DslPropertySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ApiOnlyTranscriberJDslSchema")
class ApiOnlyTranscriberJDslSchemaTest {

    @Test
    @DisplayName("schemaShouldTargetTheApiOnlyTranscriberJBlock")
    void schemaShouldTargetTheApiOnlyTranscriberJBlock() {
        assertThat(ApiOnlyTranscriberJDslSchema.SCHEMA.blockName())
                .isEqualTo(ApiOnlyTranscriberJExtension.NAME);
    }

    @Test
    @DisplayName("schemaShouldListDefaultsAndTheChannelStub")
    void schemaShouldListDefaultsAndTheChannelStub() {
        List<String> names = ApiOnlyTranscriberJDslSchema.SCHEMA.properties().stream()
                .map(DslPropertySpec::name)
                .collect(Collectors.toList());

        assertThat(names).containsExactly("strictDependencies", "subscriptions");
        assertThat(ApiOnlyTranscriberJDslSchema.SCHEMA.properties())
                .filteredOn(property -> property.name().equals("strictDependencies"))
                .extracting(DslPropertySpec::defaultLiteral)
                .containsExactly("false");
        assertThat(ApiOnlyTranscriberJDslSchema.SCHEMA.properties())
                .filteredOn(property -> property.kind() == DslPropertyKind.CONTAINER)
                .extracting(DslPropertySpec::name)
                .containsExactly("subscriptions");
        // Every line of the example is a comment: generating a block never creates a subscription.
        assertThat(ApiOnlyTranscriberJDslSchema.SCHEMA.properties().get(1).containerStub().lines())
                .allMatch(line -> line.isBlank() || line.stripLeading().startsWith("//"));
    }
}
