package com.arc_e_tect.gradle.apionly.transcriberj.spi;

import java.util.List;

/**
 * A request, as the generated {@code ContractRequest} of the same name holds it: the path
 * template and the values that fill it kept apart, and no value encoded.
 *
 * @param method         the HTTP method
 * @param pathTemplate   the path as the contract writes it: the operation's {@code PATH}
 * @param pathParameters the value of each placeholder, in the order the path names them
 * @param query          the query parameters, in declaration order; a name may repeat
 * @param headers        the header parameters, in declaration order
 * @param contentType    the content type of the body, or null when the request has none
 * @param body           the body as compact JSON followed by a newline, or null when the request
 *                       has none
 */
public record ContractRequest(String method, String pathTemplate, List<String> pathParameters, List<Pair> query,
                              List<Pair> headers, String contentType, String body) {

    /**
     * A request as given, with the lists copied so that they cannot change.
     *
     * @param method         the HTTP method
     * @param pathTemplate   the path as the contract writes it
     * @param pathParameters the value of each placeholder
     * @param query          the query parameters
     * @param headers        the header parameters
     * @param contentType    the content type of the body, or null
     * @param body           the body, or null
     */
    public ContractRequest {
        pathParameters = List.copyOf(pathParameters);
        query = List.copyOf(query);
        headers = List.copyOf(headers);
    }

    /**
     * One query parameter or header.
     *
     * @param name  its name
     * @param value its value, as it travels
     */
    public record Pair(String name, String value) {
    }
}
