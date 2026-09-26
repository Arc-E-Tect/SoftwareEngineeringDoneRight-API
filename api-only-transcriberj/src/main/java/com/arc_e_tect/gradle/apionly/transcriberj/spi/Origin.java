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
    INLINE_RESPONSE,
    /** An operation: a method on a path, with its responses. */
    OPERATION,
    /** A channel of the contract's AsyncAPI document: an address messages travel over. */
    CHANNEL,
    /** An operation of that document: an application sending or receiving on a channel. */
    ASYNC_OPERATION,
    /**
     * The invalid requests of an operation: one case per constraint on its request input.
     * Such a class is named through {@link ClassNames#invalidRequests(String)}, never listed
     * by {@link ClassNames#all()}.
     */
    INVALID_REQUESTS
}
