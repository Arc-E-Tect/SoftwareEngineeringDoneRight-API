package com.arc_e_tect.gradle.apionly.transcriberj.spi;

/** What a generated class was generated from. */
public enum Origin {
    /** A schema component. */
    SCHEMA,
    /** A reusable response component. */
    RESPONSE,
    /** A reusable parameter component. */
    PARAMETER,
    /** A reusable request body component. */
    REQUEST_BODY,
    /** A schema written inline in an operation's request body. */
    INLINE_REQUEST,
    /** A schema written inline in an operation's response. */
    INLINE_RESPONSE
}
