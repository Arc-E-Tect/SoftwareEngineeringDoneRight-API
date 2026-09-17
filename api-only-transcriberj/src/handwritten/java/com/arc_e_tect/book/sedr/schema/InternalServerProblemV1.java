package com.arc_e_tect.book.sedr.schema;

import org.springframework.restdocs.payload.PayloadDocumentation;
import org.springframework.restdocs.payload.ResponseFieldsSnippet;

/**
 * Top-level payload schema: components/schemas/InternalServerProblemV1 (500 across
 * GetRoot, GetHealth, GetUser, InitiateUserRegistration, ResendVerificationEmail,
 * CompleteUserRegistration).
 */
public final class InternalServerProblemV1 {

    private static final String TYPE = "about:blank";
    private static final String TITLE = "Internal Server Error";
    private static final int STATUS = 500;

    private InternalServerProblemV1() {
    }

    public static ResponseFieldsSnippet responseFields() {
        return PayloadDocumentation.responseFields(ProblemDetailsV1.fields());
    }

    public static String body(String detail) {
        return ProblemDetailsV1.body(TYPE, TITLE, STATUS, detail);
    }
}
