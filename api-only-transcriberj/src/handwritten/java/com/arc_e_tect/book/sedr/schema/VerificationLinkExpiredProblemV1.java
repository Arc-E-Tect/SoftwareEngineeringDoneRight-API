package com.arc_e_tect.book.sedr.schema;

import org.springframework.restdocs.payload.PayloadDocumentation;
import org.springframework.restdocs.payload.ResponseFieldsSnippet;

/** Top-level payload schema: components/schemas/VerificationLinkExpiredProblemV1 (CompleteUserRegistration 422). */
public final class VerificationLinkExpiredProblemV1 {

    private static final String TYPE = "https://api.iff.arc-e-tect.com/problems/verification-link-expired";
    private static final String TITLE = "Verification Link Expired";
    private static final int STATUS = 422;

    private VerificationLinkExpiredProblemV1() {
    }

    public static ResponseFieldsSnippet responseFields() {
        return PayloadDocumentation.responseFields(ProblemDetailsV1.fields());
    }

    public static String body(String detail) {
        return ProblemDetailsV1.body(TYPE, TITLE, STATUS, detail);
    }
}
