package com.arc_e_tect.book.sedr.schema;

import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.JsonFieldType;

import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;

/** Fragment schema: components/schemas/ErrorDetailV1. Always nested under an errors[] array. */
final class ErrorDetailV1 {

    private ErrorDetailV1() {
    }

    /**
     * Both fields are optional even though the schema requires them: most error
     * scenarios for a given status don't populate {@code details} at all, so these
     * paths never resolve to anything in those responses. When {@code details} is
     * populated, REST Docs still validates each entry's fields and type against the
     * actual payload, so the required-when-present shape is enforced per response.
     */
    static FieldDescriptor[] fields(String pathPrefix, String descriptionKeyPrefix) {
        return new FieldDescriptor[]{
                fieldWithPath(pathPrefix + ".message")
                        .description(ContractErrorDescriptions.get(descriptionKeyPrefix + ".details.message"))
                        .type(JsonFieldType.STRING)
                        .optional(),
                fieldWithPath(pathPrefix + ".context")
                        .description(ContractErrorDescriptions.get(descriptionKeyPrefix + ".details.context"))
                        .type(JsonFieldType.STRING)
                        .optional()
        };
    }
}
