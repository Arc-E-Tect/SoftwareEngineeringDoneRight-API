package com.arc_e_tect.book.sedr.schema;

import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.restdocs.payload.PayloadDocumentation;
import org.springframework.restdocs.payload.ResponseFieldsSnippet;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;

/**
 * Top-level payload schema: components/schemas/InvalidRequestProblemV1
 * (InitiateUserRegistration 400, ResendVerificationEmail 400).
 */
public final class InvalidRequestProblemV1 {

    private static final String TYPE = "https://api.iff.arc-e-tect.com/problems/invalid-request";
    private static final String TITLE = "Invalid Request";
    private static final int STATUS = 400;
    private static final String DESCRIPTION_KEY_PREFIX = "invalid_request";

    private InvalidRequestProblemV1() {
    }

    public static ResponseFieldsSnippet responseFields() {
        List<FieldDescriptor> fields = new ArrayList<>(List.of(ProblemDetailsV1.fields()));
        fields.add(fieldWithPath("errors")
                .description(ContractErrorDescriptions.get(DESCRIPTION_KEY_PREFIX + ".errors"))
                .type(JsonFieldType.ARRAY)
                .optional());
        fields.addAll(List.of(ErrorDetailV1.fields("errors[]", DESCRIPTION_KEY_PREFIX)));
        return PayloadDocumentation.responseFields(fields.toArray(FieldDescriptor[]::new));
    }

    public static String body(String detail) {
        return ProblemDetailsV1.body(TYPE, TITLE, STATUS, detail);
    }

    public static String body(String detail, List<String> errorMessages, String errorContext) {
        StringBuilder errors = new StringBuilder();
        for (int i = 0; i < errorMessages.size(); i++) {
            if (i > 0) {
                errors.append(',');
            }
            errors.append("""
                    {"message":"%s","context":"%s"}""".formatted(
                    JsonText.escape(errorMessages.get(i)), JsonText.escape(errorContext)));
        }
        return """
                {"type":"%s","title":"%s","status":%d,"detail":"%s","errors":[%s]}
                """.formatted(JsonText.escape(TYPE), JsonText.escape(TITLE), STATUS, JsonText.escape(detail), errors);
    }
}
