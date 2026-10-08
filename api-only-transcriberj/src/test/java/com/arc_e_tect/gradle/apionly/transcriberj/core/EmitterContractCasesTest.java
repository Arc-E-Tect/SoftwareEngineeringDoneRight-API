package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.model.Construct;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Operation;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.ContractRequest;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Emitter;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.EmitterContext;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.GeneratedClass;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.CaseKind;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.ContractCase;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.ManagedDependency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T19.13: an emitter is given each operation's contract cases, of every kind, through
 * {@link EmitterContext#contractCases(String)}: exactly what the operation's generated
 * {@code CASES} holds, in its order, each with its position there.
 */
@DisplayName("T19.13 Contract cases, as an emitter sees them")
class EmitterContractCasesTest {

    @TempDir
    static Path directory;

    /** Records, per operation class, the cases the context gives. */
    static class Capturing implements Emitter {

        final Map<String, List<ContractCase>> seen = new LinkedHashMap<>();
        List<ContractCase> unknown;

        @Override
        public String id() {
            return "capturing";
        }

        @Override
        public List<ManagedDependency> dependencies() {
            return List.of();
        }

        @Override
        public Set<Construct> represents() {
            return Set.of();
        }

        @Override
        public void emit(EmitterContext context) {
            for (Operation operation : context.model().operations()) {
                String location = CoreClassNames.operationLocation(operation);
                GeneratedClass cases = context.names().contractCases(location).orElseThrow();
                seen.put(cases.simpleName(), context.contractCases(location));
            }
            unknown = context.contractCases("/paths/~1no~1such~1operation/get");
        }
    }

    @TestFactory
    Stream<DynamicTest> everyEmitterSeesWhatTheGeneratedCasesHold() {
        List<DynamicTest> tests = new ArrayList<>();
        for (InvalidRequestFixtures.Variant v : InvalidRequestFixtures.CORPUS) {
            Path document = InvalidRequestFixtures.CORPUS_DIRECTORY.resolve(v.contract() + ".yaml");
            tests.add(DynamicTest.dynamicTest(v.name(), () -> assertSeen(document, v.settings(), v.name())));
        }
        for (InvalidRequestFixtures.Variant v : ContractCaseFixtures.CORPUS) {
            Path document = ContractCaseFixtures.CORPUS_DIRECTORY.resolve(v.contract() + ".yaml");
            tests.add(DynamicTest.dynamicTest(v.name(), () -> assertSeen(document, v.settings(), "contract-" + v.name())));
        }
        for (String name : ValidValueFixtures.REFERENCE) {
            Path document = GeneratedSources.CONTRACTS.resolve(name).resolve("openapi.yaml");
            tests.add(DynamicTest.dynamicTest(name, () ->
                    assertSeen(document, GeneratedSources.settings(name), "reference-" + name)));
        }
        return tests.stream();
    }

    @Test
    void anEmitterSeesEveryKind() {
        java.util.Set<String> kinds = new java.util.TreeSet<>();
        for (String name : List.of("success", "not-found", "negotiation")) {
            InvalidRequestFixtures.Variant v = ContractCaseFixtures.variant(name);
            Capturing capturing = new Capturing();
            GeneratedSources.generate(ContractCaseFixtures.CORPUS_DIRECTORY.resolve(v.contract() + ".yaml"), "1.0.0",
                    directory.resolve("kinds-" + name), v.settings(), List.of(capturing));
            capturing.seen.values().forEach(cases -> cases.forEach(c -> kinds.add(c.kind().name())));
        }
        GeneratedSources.generate(InvalidRequestFixtures.CORPUS_DIRECTORY.resolve("routes.yaml"), "1.0.0",
                directory.resolve("kinds-routes"), InvalidRequestFixtures.settings("routes"), List.of(new Capturing() {
                    @Override
                    public void emit(EmitterContext context) {
                        super.emit(context);
                        seen.values().forEach(cases -> cases.forEach(c -> kinds.add(c.kind().name())));
                    }
                }));
        assertThat(kinds).containsExactlyInAnyOrder(java.util.Arrays.stream(CaseKind.values()).map(Enum::name)
                .toArray(String[]::new));
    }

    private static void assertSeen(Path document, com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings settings,
                                   String name) throws Exception {
        Capturing capturing = new Capturing();
        GeneratedSources sources = GeneratedSources.generate(document, GeneratedSources.version(document), directory.resolve(name), settings,
                List.of(capturing));
        assertThat(capturing.seen).isNotEmpty();
        assertThat(capturing.unknown).isEmpty();
        for (Map.Entry<String, List<ContractCase>> operation : capturing.seen.entrySet()) {
            List<?> generated = (List<?>) sources.type(operation.getKey()).getField("CASES").get(null);
            List<ContractCase> seen = operation.getValue();
            assertThat(seen).as(operation.getKey()).hasSameSizeAs(generated);
            for (int i = 0; i < seen.size(); i++) {
                assertThat(seen.get(i).index()).isEqualTo(i);
                assertThat(components(seen.get(i))).as("%s case %d", operation.getKey(), i)
                        .isEqualTo(components(generated.get(i)));
            }
        }
    }

    /** A record's components by name, a nested record's as a map of its own; the SPI's index left out. */
    private static Map<String, Object> components(Object record) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        for (RecordComponent c : record.getClass().getRecordComponents()) {
            if (c.getName().equals("index")) continue;
            Object value = c.getAccessor().invoke(record);
            out.put(c.getName(), value instanceof Enum<?> e ? e.name() : value);
        }
        if (out.get("request") instanceof Record request) out.put("request", components(request));
        for (String pairs : List.of("query", "headers")) {
            if (out.get(pairs) instanceof List<?> list) {
                List<Object> converted = new ArrayList<>();
                for (Object pair : list) converted.add(components(pair));
                out.put(pairs, converted);
            }
        }
        return out;
    }

    @Test
    void theCasesCannotChange() {
        ContractRequest request = new ContractRequest("GET", "/a", new ArrayList<>(List.of("x")),
                new ArrayList<>(List.of(new ContractRequest.Pair("q", "1"))), new ArrayList<>(), null, null);
        ContractCase c = new ContractCase("query-q-type", CaseKind.INVALID_REQUEST, false, null, "d", "query", "q",
                null, "type", request, 400, new ArrayList<>(List.of("application/json")), null, "getA",
                new ArrayList<>(List.of("q")), new ArrayList<>(List.of("X-A")), 0);

        assertThatThrownBy(() -> request.pathParameters().add("y")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> request.query().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> request.headers().add(null)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> c.expectedContentTypes().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> c.declaredQuery().add("r")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> c.declaredHeaders().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aContextThatKnowsNoCasesGivesNone() {
        EmitterContext context = new EmitterContext() {
            @Override
            public com.arc_e_tect.gradle.apionly.transcriberj.model.ContractModel model() {
                return null;
            }

            @Override
            public com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings settings() {
                return null;
            }

            @Override
            public com.arc_e_tect.gradle.apionly.transcriberj.spi.ClassNames names() {
                return null;
            }

            @Override
            public void writeJava(String packageName, String simpleName, String source) {
            }

            @Override
            public void writeResource(String path, String content) {
            }

            @Override
            public String degraded(String className, String method,
                                   com.arc_e_tect.gradle.apionly.transcriberj.model.Finding finding) {
                return "";
            }
        };

        assertThat(context.contractCases("/paths/~1a/get")).isEmpty();
        assertThat(context.requestBodySchema("/paths/~1a/get", "application/json")).isEmpty();
        assertThat(context.parameterSchema("/paths/~1a/get", "path", "id")).isEmpty();
    }
}
