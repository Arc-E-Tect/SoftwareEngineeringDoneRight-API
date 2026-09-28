package com.example.books.adapter.web;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * How the service binds JSON: as strictly as the contract.
 *
 * <p>Spring Boot's JSON mapper is lenient by default: it ignores a member it does not know,
 * and turns {@code 5} into {@code "5"} and {@code 1.5} into {@code 1}. The contract is not:
 * {@code BookRequestV1} is closed, and every member has one type. A lenient mapper would accept
 * requests the contract calls invalid -- an unknown member is how mass assignment starts -- and
 * Bean Validation cannot catch it afterwards: by then, the unknown member is gone, and the
 * coerced value looks valid. So the mapper is configured here, explicitly.
 *
 * <p>No unit or component test sees this configuration: they bypass Spring Boot's mapper. Only
 * a test sending real HTTP to the running service does -- here, the contract tests.
 */
@Configuration(proxyBeanMethods = false)
public class JsonConfiguration {

    /**
     * The mapper, as strict as the contract.
     *
     * @return the customisation
     */
    // tag::strict-mapper[]
    @Bean
    JsonMapperBuilderCustomizer strictAsTheContract() {
        return builder -> builder
                // A member the contract does not declare is an error, not something to ignore.
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // No member may be null: the contract declares none nullable. A member that is
                // absent is not null; BookRequest says why it has setters for that.
                .changeDefaultNullHandling(nulls -> JsonSetter.Value.forValueNulls(Nulls.FAIL))
                // A number or a boolean is not a string, and 1.5 is not an integer: no coercion.
                .withCoercionConfig(LogicalType.Textual, text -> text
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                .withCoercionConfig(LogicalType.Integer, integer -> integer
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail));
    }
    // end::strict-mapper[]
}
