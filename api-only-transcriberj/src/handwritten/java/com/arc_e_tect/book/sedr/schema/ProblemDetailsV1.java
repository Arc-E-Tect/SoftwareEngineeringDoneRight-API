package com.arc_e_tect.book.sedr.schema;

import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.JsonFieldType;

import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;

/**
 * Base schema: components/schemas/ProblemDetailsV1 (RFC 9457). Never a top-level
 * body on its own -- every top-level problem payload (UserNotFoundProblemV1,
 * InvalidRequestProblemV1, ...) composes this fragment via {@code allOf}, exactly
 * as the spec does, fixing {@code type}/{@code title}/{@code status} to constants.
 */
final class ProblemDetailsV1 {

    private ProblemDetailsV1() {
    }

    static FieldDescriptor[] fields() {
        return new FieldDescriptor[]{
                fieldWithPath("type")
                        .description(ContractErrorDescriptions.get("problem.type"))
                        .type(JsonFieldType.STRING),
                fieldWithPath("title")
                        .description(ContractErrorDescriptions.get("problem.title"))
                        .type(JsonFieldType.STRING),
                fieldWithPath("status")
                        .description(ContractErrorDescriptions.get("problem.status"))
                        .type(JsonFieldType.NUMBER),
                fieldWithPath("detail")
                        .description(ContractErrorDescriptions.get("problem.detail"))
                        .type(JsonFieldType.STRING),
                fieldWithPath("instance")
                        .description(ContractErrorDescriptions.get("problem.instance"))
                        .type(JsonFieldType.STRING)
                        .optional()
        };
    }

    static String body(String type, String title, int status, String detail) {
        return """
                {"type":"%s","title":"%s","status":%d,"detail":"%s"}
                """.formatted(JsonText.escape(type), JsonText.escape(title), status, JsonText.escape(detail));
    }
}
