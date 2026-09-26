package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.spi.InvalidRequestCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A case says which operation it belongs to, and which query parameters and headers that
 * operation declares, so that a renderer matching a request exactly -- a stub, say -- can
 * require the ones the case leaves out to be absent, without looking the operation up.
 */
@DisplayName("A case's operation context")
class CaseOperationContextTest {

    private static final String CONTRACT = """
            openapi: 3.1.0
            info:
              title: Context
              version: 1.0.0
            paths:
              /items/{id}:
                parameters:
                  - name: id
                    in: path
                    required: true
                    schema:
                      type: string
                      maxLength: 5
                  - name: X-Trace
                    in: header
                    schema:
                      type: string
                  - name: page
                    in: query
                    schema:
                      type: integer
                get:
                  operationId: getItem
                  parameters:
                    - name: page
                      in: query
                      required: true
                      schema:
                        type: integer
                        minimum: 1
                    - $ref: '#/components/parameters/Filter'
                    - name: Accept
                      in: header
                      schema:
                        type: string
                    - name: Authorization
                      in: header
                      schema:
                        type: string
                    - name: session
                      in: cookie
                      schema:
                        type: string
                    - name: X-Mode
                      in: header
                      required: true
                      schema:
                        type: string
                        enum: [a, b]
                  responses:
                    '200':
                      description: The item.
                    '400':
                      description: Invalid.
                delete:
                  responses:
                    '204':
                      description: Deleted.
                    '400':
                      description: Invalid.
            components:
              parameters:
                Filter:
                  x-fragment-path: openapi/components/parameters/FilterV1.yaml
                  name: filter
                  in: query
                  schema:
                    type: string
                    maxLength: 3
            """;

    @TempDir
    Path directory;

    @Test
    void everyCaseCarriesItsOperationsIdAndDeclaredParameters() throws Exception {
        EmitterInvalidRequestCasesTest.Capturing capturing = new EmitterInvalidRequestCasesTest.Capturing();
        GeneratedSources sources = GeneratedSources.generate(CONTRACT, directory, List.of(capturing));

        Map<String, Expected> expected = Map.of(
                "GetItemInvalidRequests", new Expected("getItem", List.of("page", "filter"), List.of("X-Trace", "X-Mode")),
                "DeleteItemsByIdInvalidRequests", new Expected(null, List.of("page"), List.of("X-Trace")));
        assertThat(capturing.seen).containsOnlyKeys(expected.keySet());

        for (Map.Entry<String, List<InvalidRequestCase>> operation : capturing.seen.entrySet()) {
            Expected e = expected.get(operation.getKey());
            assertThat(operation.getValue()).as(operation.getKey()).isNotEmpty().allSatisfy(c -> {
                assertThat(c.operationId()).isEqualTo(e.operationId());
                assertThat(c.declaredQuery()).isEqualTo(e.query());
                assertThat(c.declaredHeaders()).isEqualTo(e.headers());
            });
            List<?> generated = (List<?>) sources.type(operation.getKey()).getField("CASES").get(null);
            assertThat(generated).as(operation.getKey()).isNotEmpty();
            for (Object c : generated) {
                assertThat(accessor(c, "operationId")).isEqualTo(e.operationId());
                assertThat(accessor(c, "declaredQuery")).isEqualTo(e.query());
                assertThat(accessor(c, "declaredHeaders")).isEqualTo(e.headers());
            }
        }
    }

    private static Object accessor(Object record, String name) throws Exception {
        return record.getClass().getMethod(name).invoke(record);
    }

    private record Expected(String operationId, List<String> query, List<String> headers) {
    }
}
