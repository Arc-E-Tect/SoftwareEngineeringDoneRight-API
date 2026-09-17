package com.arc_e_tect.book.sedr.schema;

import org.springframework.restdocs.payload.PayloadDocumentation;
import org.springframework.restdocs.payload.ResponseFieldsSnippet;

/** Top-level payload schema: components/schemas/RegistrationNotFoundProblemV1 (ResendVerificationEmail 404). */
public final class RegistrationNotFoundProblemV1 {

    private static final String TYPE = "https://api.iff.arc-e-tect.com/problems/registration-not-found";
    private static final String TITLE = "Registration Not Found";
    private static final int STATUS = 404;

    private RegistrationNotFoundProblemV1() {
    }

    public static ResponseFieldsSnippet responseFields() {
        return PayloadDocumentation.responseFields(ProblemDetailsV1.fields());
    }

    public static String body(String detail) {
        return ProblemDetailsV1.body(TYPE, TITLE, STATUS, detail);
    }
}
