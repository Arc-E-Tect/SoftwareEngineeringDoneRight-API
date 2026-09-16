package com.arc_e_tect.book.sedr.schema;

import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.restdocs.payload.PayloadDocumentation;
import org.springframework.restdocs.payload.ResponseFieldsSnippet;

import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;

/** Top-level payload schema: components/schemas/UserAccountV1 (CompleteUserRegistration 200). */
public final class UserAccountV1 {

    private UserAccountV1() {
    }

    public static ResponseFieldsSnippet responseFields() {
        return PayloadDocumentation.responseFields(
                fieldWithPath("username")
                        .description(UsernameV1.description())
                        .type(JsonFieldType.STRING),
                fieldWithPath("emailAddress")
                        .description("The email address associated with the account.")
                        .type(JsonFieldType.STRING)
        );
    }

    public static String body(String username, String emailAddress) {
        return """
            {"username":"%s","emailAddress":"%s"}
            """.formatted(JsonText.escape(username), JsonText.escape(emailAddress));
    }
}
