package com.arc_e_tect.gradle.apionly.transcriberj.spi;

import java.util.List;

/**
 * One invalid-request case, as the generated {@code InvalidRequestCase} of the same name holds
 * it, and where it is: an emitter that renders cases shapes its code by them, and takes their
 * values from the generated {@code CASES} at run time.
 *
 * @param id                   readable, unique within its operation and the same on every run
 * @param description          one sentence saying what is wrong with the request
 * @param in                   where the fault is: {@code path}, {@code query}, {@code header}
 *                             or {@code body}
 * @param name                 the parameter's name, or null when the fault is in the body
 * @param pointer              the JSON pointer of the fault in the body, the empty string for
 *                             the body as a whole, or null when the fault is in a parameter
 * @param keyword              the constraint violated, as the contract spells it
 * @param request              the complete violating request
 * @param expectedStatus       the status the contract declares for an invalid request
 * @param expectedContentTypes every content type that response is declared with; empty when
 *                             it has no content
 * @param responseBodyClass    the simple name of the core class for that response's body, or
 *                             null when it has none
 * @param representative       whether this is the case whose documentation is published
 * @param operationId          the {@code operationId} of the case's operation, or null when the
 *                             contract gives it none
 * @param declaredQuery        the name of every query parameter the operation declares, whether
 *                             or not the request carries it, in declaration order
 * @param declaredHeaders      the name of every header parameter the operation declares, whether
 *                             or not the request carries it, in declaration order; without
 *                             {@code Accept}, {@code Content-Type} and {@code Authorization},
 *                             which OpenAPI ignores as parameters
 * @param index                its position in the operation's generated {@code CASES}
 */
public record InvalidRequestCase(String id, String description, String in, String name, String pointer,
                                 String keyword, ContractRequest request, int expectedStatus,
                                 List<String> expectedContentTypes, String responseBodyClass,
                                 boolean representative, String operationId, List<String> declaredQuery,
                                 List<String> declaredHeaders, int index) {

    /**
     * A case as given, with the lists copied so that they cannot change.
     *
     * @param id                   its id
     * @param description          what is wrong with the request
     * @param in                   where the fault is
     * @param name                 the parameter's name, or null
     * @param pointer              the JSON pointer of the fault, or null
     * @param keyword              the constraint violated
     * @param request              the violating request
     * @param expectedStatus       the declared status
     * @param expectedContentTypes the declared content types
     * @param responseBodyClass    the class of the declared body, or null
     * @param representative       whether its documentation is published
     * @param operationId          its operation's id, or null
     * @param declaredQuery        the query parameters its operation declares
     * @param declaredHeaders      the headers its operation declares
     * @param index                its position in {@code CASES}
     */
    public InvalidRequestCase {
        expectedContentTypes = List.copyOf(expectedContentTypes);
        declaredQuery = List.copyOf(declaredQuery);
        declaredHeaders = List.copyOf(declaredHeaders);
    }
}
