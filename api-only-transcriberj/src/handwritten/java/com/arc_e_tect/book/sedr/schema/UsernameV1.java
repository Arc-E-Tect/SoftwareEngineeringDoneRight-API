package com.arc_e_tect.book.sedr.schema;

/**
 * Reusable scalar schema: components/schemas/UsernameV1. Referenced by
 * UserV1.username, UserAccountV1.username, UserRegistrationRequestV1.username,
 * and UserRegistrationResendEmailRequestV1.username.
 */
public final class UsernameV1 {

    private UsernameV1() {
    }

    public static String description() {
        return "The unique username of the account. Must be 5-12 characters, start with a "
                + "lowercase letter, and contain only lowercase alphanumerics, dashes, or underscores.";
    }
}
