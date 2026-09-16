package com.arc_e_tect.book.sedr.schema;

import org.springframework.restdocs.payload.PayloadDocumentation;
import org.springframework.restdocs.payload.ResponseFieldsSnippet;

/** Top-level payload schema: components/schemas/RegisteredRequestCannotBeProcessedProblemV1 (InitiateUserRegistration 422). */
public final class RegisteredRequestCannotBeProcessedProblemV1 {

    private static final String TYPE = "https://api.iff.arc-e-tect.com/problems/registration-request-cannot-be-processed";
    private static final String TITLE = "Registration Request Cannot Be Processed";
    private static final int STATUS = 422;

    private RegisteredRequestCannotBeProcessedProblemV1() {
    }

    public static ResponseFieldsSnippet responseFields() {
        return PayloadDocumentation.responseFields(ProblemDetailsV1.fields());
    }

    public static String body(String detail) {
        return ProblemDetailsV1.body(TYPE, TITLE, STATUS, detail);
    }
}
