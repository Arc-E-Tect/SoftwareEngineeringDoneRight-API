package com.arc_e_tect.book.sedr.schema;

import org.springframework.restdocs.payload.PayloadDocumentation;
import org.springframework.restdocs.payload.ResponseFieldsSnippet;

/** Top-level payload schema: components/schemas/UsernameAlreadyRegisteredProblemV1 (InitiateUserRegistration 409). */
public final class UsernameAlreadyRegisteredProblemV1 {

    private static final String TYPE = "https://api.iff.arc-e-tect.com/problems/username-already-registered";
    private static final String TITLE = "Username Already Registered";
    private static final int STATUS = 409;

    private UsernameAlreadyRegisteredProblemV1() {
    }

    public static ResponseFieldsSnippet responseFields() {
        return PayloadDocumentation.responseFields(ProblemDetailsV1.fields());
    }

    public static String body(String detail) {
        return ProblemDetailsV1.body(TYPE, TITLE, STATUS, detail);
    }
}
