package com.arc_e_tect.book.sedr.schema;

import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.restdocs.payload.PayloadDocumentation;
import org.springframework.restdocs.payload.RequestFieldsSnippet;

import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;

/** Top-level payload schema: components/schemas/UserRegistrationRequestV1 (InitiateUserRegistration request body). */
public final class UserRegistrationRequestV1 {

    private UserRegistrationRequestV1() {
    }

    public static RequestFieldsSnippet requestFields() {
        return PayloadDocumentation.requestFields(
                fieldWithPath("username")
                        .description(UsernameV1.description())
                        .type(JsonFieldType.STRING),
                fieldWithPath("emailAddress")
                        .description("The email address of the prospective user.")
                        .type(JsonFieldType.STRING)
        );
    }

    public static String body(String username, String emailAddress) {
        return """
                {"username":"%s","emailAddress":"%s"}
                """.formatted(JsonText.escape(username), JsonText.escape(emailAddress));
    }
}
