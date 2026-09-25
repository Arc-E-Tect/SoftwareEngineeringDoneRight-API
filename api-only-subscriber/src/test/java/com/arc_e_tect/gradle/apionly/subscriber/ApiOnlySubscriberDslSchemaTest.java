package com.arc_e_tect.gradle.apionly.subscriber;

import com.arc_e_tect.gradle.dslupdater.DslPropertyKind;
import com.arc_e_tect.gradle.dslupdater.DslPropertySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ApiOnlySubscriberDslSchema")
class ApiOnlySubscriberDslSchemaTest {

    @Test
    @DisplayName("schemaShouldTargetTheApiOnlySubscriberBlock")
    void schemaShouldTargetTheApiOnlySubscriberBlock() {
        assertThat(ApiOnlySubscriberDslSchema.SCHEMA.blockName())
                .isEqualTo(ApiOnlySubscriberExtension.NAME);
    }

    @Test
    @DisplayName("schemaShouldListDefaultsAndTheChannelStub")
    void schemaShouldListDefaultsAndTheChannelStub() {
        List<String> names = ApiOnlySubscriberDslSchema.SCHEMA.properties().stream()
                .map(DslPropertySpec::name)
                .collect(Collectors.toList());

        assertThat(names).containsExactly("lockfile", "apiContractVersion", "sourceSet", "clientResources", "channel");
        assertThat(ApiOnlySubscriberDslSchema.SCHEMA.properties())
                .filteredOn(property -> property.name().equals("apiContractVersion"))
                .extracting(DslPropertySpec::defaultLiteral)
                .containsExactly("findProperty('apiContractVersion')");
        assertThat(ApiOnlySubscriberDslSchema.SCHEMA.properties())
                .filteredOn(property -> property.kind() == DslPropertyKind.CONTAINER)
                .extracting(DslPropertySpec::name)
                .containsExactly("channel");
    }
}
