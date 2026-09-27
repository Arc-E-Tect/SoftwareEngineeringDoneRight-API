package com.arc_e_tect.gradle.apionly.transcriberj.spi;

import java.util.List;

/**
 * One contract case, as an emitter is given it: the values the generated {@code ContractCase}
 * of the base package holds, and the case's {@code index} in its operation's {@code CASES}.
 *
 * @param id                   readable, unique within its operation and the same on every run
 * @param kind                 what the case tests
 * @param requiresState        whether a target must be put in a particular state before the
 *                             request is sent: {@code true} for {@link CaseKind#SUCCESS} and
 *                             {@link CaseKind#NOT_FOUND}
 * @param variant              for a success case, which valid request it sends: {@code required},
 *                             {@code full} or {@code noBody}; {@code null} for every other kind
 * @param description          one sentence saying what the case sends
 * @param in                   for an invalid-request case, where the fault is: {@code path},
 *                             {@code query}, {@code header} or {@code body}; {@code null} otherwise
 * @param name                 for an invalid-request case, the parameter's name, or {@code null}
 * @param pointer              for an invalid-request case, the JSON pointer into the body, or {@code null}
 * @param keyword              for an invalid-request case, the violated keyword; {@code null} otherwise
 * @param request              the complete request
 * @param expectedStatus       the status the contract declares for it
 * @param expectedContentTypes every content type that response is declared with; empty when it
 *                             has no content
 * @param responseBodyClass    the simple name of the generated class of that response's body, or
 *                             {@code null}
 * @param operationId          the operation's {@code operationId}, or {@code null}
 * @param declaredQuery        every query parameter the operation declares, in declaration order
 * @param declaredHeaders      every header parameter the operation declares, in declaration order,
 *                             without {@code Accept}, {@code Content-Type} and {@code Authorization}
 * @param index                the case's position in its operation's {@code CASES}
 */
public record ContractCase(String id, CaseKind kind, boolean requiresState, String variant, String description,
                           String in, String name, String pointer, String keyword, ContractRequest request,
                           int expectedStatus, List<String> expectedContentTypes, String responseBodyClass,
                           String operationId, List<String> declaredQuery, List<String> declaredHeaders, int index) {

    /**
     * A case, with its lists copied so that they cannot change.
     *
     * @param id                   the id
     * @param kind                 the kind
     * @param requiresState        whether it requires state
     * @param variant              the success variant, or {@code null}
     * @param description          the description
     * @param in                   where an invalid request's fault is, or {@code null}
     * @param name                 the parameter's name, or {@code null}
     * @param pointer              the pointer into the body, or {@code null}
     * @param keyword              the violated keyword, or {@code null}
     * @param request              the request
     * @param expectedStatus       the expected status
     * @param expectedContentTypes the expected content types
     * @param responseBodyClass    the response body's class, or {@code null}
     * @param operationId          the operation's id, or {@code null}
     * @param declaredQuery        the declared query parameters
     * @param declaredHeaders      the declared headers
     * @param index                the position in {@code CASES}
     */
    public ContractCase {
        expectedContentTypes = List.copyOf(expectedContentTypes);
        declaredQuery = List.copyOf(declaredQuery);
        declaredHeaders = List.copyOf(declaredHeaders);
    }
}
