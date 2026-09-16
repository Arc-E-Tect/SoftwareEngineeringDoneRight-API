package com.arc_e_tect.book.sedr.schema;

import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.restdocs.payload.PayloadDocumentation;
import org.springframework.restdocs.payload.RequestFieldsSnippet;

import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;

/**
 * Top-level payload schema: components/schemas/UserRegistrationResendEmailRequestV1
 * (ResendVerificationEmail request body).
 */
public final class UserRegistrationResendEmailRequestV1 {

    private UserRegistrationResendEmailRequestV1() {
    }

    public static RequestFieldsSnippet requestFields() {
        return PayloadDocumentation.requestFields(
                fieldWithPath("username")
                        .description(UsernameV1.description())
                        .type(JsonFieldType.STRING)
        );
    }

    public static String body(String username) {
        return """
                {"username":"%s"}
                """.formatted(JsonText.escape(username));
    }
}
