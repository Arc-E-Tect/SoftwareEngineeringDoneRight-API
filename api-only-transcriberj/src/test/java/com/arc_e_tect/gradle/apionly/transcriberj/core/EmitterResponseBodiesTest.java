package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.model.Construct;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Operation;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Response;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.ContractCase;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Emitter;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.EmitterContext;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.ManagedDependency;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.ResponseBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An emitter is given the body of each response an operation declares through
 * {@link EmitterContext#responseBody(String, String)}: the class its cases name, and exactly the
 * text that class's generated {@code requiredBody()} and {@code fullBody()} return -- or, where
 * one of them throws, the reason it gives. What a stub written as a file, rather than as Java
 * that calls those methods, answers with.
 */
@DisplayName("Response bodies, as an emitter sees them")
class EmitterResponseBodiesTest {

    @TempDir
    static Path directory;

    /** What the context gives for one declared response. */
    record Seen(String location, String status, Optional<ResponseBody> body) {
    }

    /** Records every declared response's body, and every case's body class. */
    static class Capturing implements Emitter {

        final List<Seen> seen = new ArrayList<>();
        final Map<String, String> caseClasses = new LinkedHashMap<>();
        final List<Optional<ResponseBody>> unknown = new ArrayList<>();

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
                if (operation.responses() != null) {
                    for (Response response : operation.responses()) {
                        seen.add(new Seen(location, response.status(),
                                context.responseBody(location, response.status())));
                    }
                }
                for (ContractCase c : context.contractCases(location)) {
                    Optional<ResponseBody> body = context.responseBody(location, String.valueOf(c.expectedStatus()));
                    caseClasses.put(location + " " + c.id(), c.responseBodyClass() + " -> "
                            + body.map(ResponseBody::bodyClass).orElse(null));
                }
                unknown.add(context.responseBody(location, "599"));
            }
            unknown.add(context.responseBody("/paths/~1no~1such~1operation/get", "200"));
        }
    }

    @TestFactory
    Stream<DynamicTest> everyEmitterSeesWhatTheGeneratedBodiesReturn() {
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
    void aBodyTheCoreCannotBuildIsGivenWithItsReason() throws Exception {
        Path document = Path.of("src/test/resources/fixtures/response-bodies/unbuildable.yaml");
        Capturing capturing = new Capturing();
        assertSeen(document, InvalidRequestFixtures.settings("unbuildable"), "unbuildable-seen");
        GeneratedSources.generate(document, "1.0.0", directory.resolve("unbuildable"),
                InvalidRequestFixtures.settings("unbuildable"), List.of(capturing));
        List<ResponseBody.Body> unavailable = new ArrayList<>();
        for (Seen s : capturing.seen) {
            s.body().ifPresent(b -> {
                if (!b.required().available()) unavailable.add(b.required());
                if (!b.full().available()) unavailable.add(b.full());
            });
        }
        assertThat(capturing.seen).filteredOn(seen -> seen.status().equals("204"))
                .allSatisfy(seen -> assertThat(seen.body()).as("a response without content").isEmpty());
        assertThat(unavailable).hasSize(2).allSatisfy(b -> {
            assertThat(b.text()).isNull();
            assertThat(b.location()).startsWith("/");
            assertThat(b.reason()).isNotBlank();
        });
    }

    private static void assertSeen(Path document, com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings settings,
                                   String name) throws Exception {
        Capturing capturing = new Capturing();
        GeneratedSources sources = GeneratedSources.generate(document, "1.0.0", directory.resolve(name), settings,
                List.of(capturing));
        assertThat(capturing.unknown).allSatisfy(u -> assertThat(u).isEmpty());
        for (String classes : capturing.caseClasses.values()) {
            String[] pair = classes.split(" -> ");
            assertThat(pair[1]).as("the body class a case names").isEqualTo(pair[0]);
        }
        for (Seen s : capturing.seen) {
            if (s.body().isEmpty()) continue;
            ResponseBody body = s.body().get();
            Class<?> type = sources.type(body.bodyClass());
            assertBody(body.required(), type, "requiredBody", s);
            assertBody(body.full(), type, "fullBody", s);
        }
    }

    /** The body the context gives is what the generated method returns, or the reason it throws. */
    private static void assertBody(ResponseBody.Body body, Class<?> type, String method, Seen s) throws Exception {
        String as = s.location() + " " + s.status() + " " + type.getSimpleName() + "." + method + "()";
        try {
            Object text = type.getMethod(method).invoke(null);
            assertThat(body.available()).as(as).isTrue();
            assertThat(body.text()).as(as).isEqualTo(text);
        } catch (InvocationTargetException e) {
            assertThat(e.getCause()).as(as).isInstanceOf(UnsupportedOperationException.class);
            assertThat(body.available()).as(as).isFalse();
            assertThat(e.getCause().getMessage()).as(as).contains(body.reason()).contains(body.location());
        }
    }
}
