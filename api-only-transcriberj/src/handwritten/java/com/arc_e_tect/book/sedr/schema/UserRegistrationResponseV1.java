package com.arc_e_tect.book.sedr.schema;

import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.restdocs.payload.PayloadDocumentation;
import org.springframework.restdocs.payload.ResponseFieldsSnippet;

import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;

/**
 * Top-level payload schema: components/schemas/UserRegistrationResponseV1
 * (InitiateUserRegistration 202, ResendVerificationEmail 202).
 */
public final class UserRegistrationResponseV1 {

    private UserRegistrationResponseV1() {
    }

    public static ResponseFieldsSnippet responseFields() {
        return PayloadDocumentation.responseFields(
                fieldWithPath("message")
                        .description("Human-readable confirmation that the verification email was sent.")
                        .type(JsonFieldType.STRING)
        );
    }

    public static String body(String message) {
        return """
                {"message":"%s"}
                """.formatted(JsonText.escape(message));
    }
}
