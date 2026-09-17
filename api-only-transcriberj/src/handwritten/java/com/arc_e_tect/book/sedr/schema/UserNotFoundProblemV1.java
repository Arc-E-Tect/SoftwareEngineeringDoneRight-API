package com.arc_e_tect.book.sedr.schema;

import org.springframework.restdocs.payload.PayloadDocumentation;
import org.springframework.restdocs.payload.ResponseFieldsSnippet;

/** Top-level payload schema: components/schemas/UserNotFoundProblemV1 (GetUser 404). */
public final class UserNotFoundProblemV1 {

    private static final String TYPE = "https://api.iff.arc-e-tect.com/problems/user-not-found";
    private static final String TITLE = "User Not Found";
    private static final int STATUS = 404;

    private UserNotFoundProblemV1() {
    }

    public static ResponseFieldsSnippet responseFields() {
        return PayloadDocumentation.responseFields(ProblemDetailsV1.fields());
    }

    public static String body(String detail) {
        return ProblemDetailsV1.body(TYPE, TITLE, STATUS, detail);
    }
}
