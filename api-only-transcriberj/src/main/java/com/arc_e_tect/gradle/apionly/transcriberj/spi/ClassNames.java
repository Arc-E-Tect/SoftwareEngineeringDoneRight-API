package com.arc_e_tect.gradle.apionly.transcriberj.spi;

import java.util.List;
import java.util.Optional;

/** The core classes of one contract, as the core emitter names them. */
public interface ClassNames {

    /**
     * Every core class, in the order the contract declares what they come from.
     *
     * @return the classes
     */
    List<GeneratedClass> all();

    /**
     * The class for a component.
     *
     * @param origin        {@link Origin#SCHEMA}, {@link Origin#RESPONSE},
     *                      {@link Origin#PARAMETER} or {@link Origin#REQUEST_BODY}
     * @param componentName the component's name
     * @return the class, or empty when there is none
     */
    Optional<GeneratedClass> component(Origin origin, String componentName);

    /**
     * The class for an operation.
     *
     * @param location the JSON pointer of the operation, such as {@code /paths/~1v1~1users/get}
     * @return the class, or empty when there is none
     */
    Optional<GeneratedClass> operation(String location);

    /**
     * The class for a schema written inline in an operation.
     *
     * @param location the JSON pointer of the schema
     * @return the class, or empty when the schema has none
     */
    Optional<GeneratedClass> inline(String location);

    /**
     * The class that lists an operation's invalid-request cases, such as
     * {@code InitiateUserRegistrationInvalidRequests}: generated for every operation, with no
     * cases where the operation declares no invalid-request status or no constraint on its
     * input. It is not one of {@link #all()}, which lists the classes the contract's
     * components and operations are generated into.
     *
     * @param location the JSON pointer of the operation, such as {@code /paths/~1v1~1users/get}
     * @return the class, or empty when there is none
     */
    default Optional<GeneratedClass> invalidRequests(String location) {
        return Optional.empty();
    }
}
