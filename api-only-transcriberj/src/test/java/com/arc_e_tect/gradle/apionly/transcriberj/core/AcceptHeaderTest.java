package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T19.7a: every case sends one {@code Accept}, listing the media types of every response its
 * operation declares, success and error alike, each once, in declaration order; none when no
 * response declares content. The not-acceptable case is the exception: its {@code Accept} is its
 * fault, so it sends only its deliberately unacceptable value.
 */
@DisplayName("T19.7a Accept on every case")
class AcceptHeaderTest {

    @TempDir
    static Path directory;

    static List<ValidValueFixtures.Fixture> fixtures;

    @BeforeAll
    static void generate() {
        fixtures = ContractCaseFixtures.all(directory);
    }

    /** The Accept every case of an operation sends, as the rule states it; null for none. */
    static String accept(ValidValueFixtures.Fixture f, String location) {
        Set<String> types = new LinkedHashSet<>();
        for (JsonNode response : f.oracle().document.at(location).path("responses")) {
            JsonNode resolved = response.has("$ref") ? f.oracle().resolve(response.get("$ref").stringValue().substring(1))
                    : response;
            types.addAll(resolved.path("content").propertyNames());
        }
        return types.isEmpty() ? null : String.join(", ", types);
    }

    @TestFactory
    Stream<DynamicTest> everyCaseSendsTheOperationsAccept() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ValidValueFixtures.Fixture f : fixtures) {
            for (JsonNode c : ContractCaseFixtures.cases(f.report())) {
                tests.add(DynamicTest.dynamicTest(f.name() + " " + c.get("class").stringValue() + " "
                        + c.get("id").stringValue(), () -> {
                    List<String> sent = ContractCaseFixtures.accept(c.get("request"));
                    if (c.get("kind").stringValue().equals("NOT_ACCEPTABLE")) {
                        assertThat(sent).containsExactly(MediaTypeChoiceTest.NOT_ACCEPTABLE.stream()
                                .filter(sent::contains).findFirst().orElse("none of the candidates"));
                        return;
                    }
                    String expected = accept(f, c.get("location").stringValue());
                    if (expected == null) {
                        assertThat(sent).isEmpty();
                    } else {
                        assertThat(sent).containsExactly(expected);
                    }
                }));
            }
        }
        return tests.stream();
    }
}
