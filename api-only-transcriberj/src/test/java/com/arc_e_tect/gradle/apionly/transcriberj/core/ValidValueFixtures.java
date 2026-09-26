package com.arc_e_tect.gradle.apionly.transcriberj.core;

import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

/** The contracts the valid-value tests run over, generated, with their oracle and machine-readable report. */
final class ValidValueFixtures {

    /** The contracts of the repository's reference API, built into {@code reference-api/dist}. */
    static final List<String> REFERENCE = List.of("system-admin", "user-account");

    /** The contracts written for these tests, under {@code fixtures/valid-values/corpus}. */
    static final List<String> CORPUS = List.of("keywords", "exclusive-3.0", "unsatisfiable", "parameters");

    static final Path CORPUS_DIRECTORY = GeneratedSources.FIXTURES.resolve("valid-values/corpus");

    private ValidValueFixtures() {
    }

    /**
     * One contract, generated.
     *
     * @param name     its name
     * @param document its OpenAPI document
     * @param sources  what was generated
     * @param oracle   the independent validator, over the document
     * @param async    the independent validator over the contract's AsyncAPI document, or null
     * @param report   the machine-readable report of every valid value
     */
    record Fixture(String name, Path document, GeneratedSources sources, Oracle oracle, Oracle async,
                   JsonNode report) {

        /** The oracle over whichever document a pointer points into: a message payload's is the AsyncAPI one. */
        Oracle oracleFor(String pointer) {
            return oracle.document.at(pointer).isMissingNode() && async != null ? async : oracle;
        }

        /** The report's entry for a body class. */
        JsonNode body(String className) {
            return entry("bodies", className);
        }

        /** The report's entry for an operation's class. */
        JsonNode request(String className) {
            return entry("requests", className);
        }

        List<JsonNode> bodies() {
            return list(report.get("bodies"));
        }

        List<JsonNode> requests() {
            return list(report.get("requests"));
        }

        /** The text report. */
        String text() {
            return sources.report.render(name, "1.0.0");
        }

        private JsonNode entry(String list, String className) {
            return list(report.get(list)).stream().filter(e -> e.get("class").stringValue().equals(className))
                    .findFirst().orElseThrow(() -> new AssertionError("no " + list + " entry for " + className));
        }
    }

    static List<JsonNode> list(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).toList();
    }

    static Fixture corpus(String name, Path into) {
        Path document = CORPUS_DIRECTORY.resolve(name + ".yaml");
        GeneratedSources sources = GeneratedSources.generate(document, "1.0.0", into,
                GeneratedSources.settings(name), List.of());
        return fixture(name, document, null, sources);
    }

    static Fixture reference(String name, Path into) {
        Path directory = GeneratedSources.CONTRACTS.resolve(name);
        Path document = directory.resolve("openapi.yaml");
        Path async = directory.resolve("asyncapi.yaml");
        GeneratedSources sources = GeneratedSources.generate(document, Files.exists(async) ? async : null, "1.0.0",
                into, GeneratedSources.settings(name), List.of());
        return fixture(name, document, Files.exists(async) ? async : null, sources);
    }

    /** Every fixture contract: the corpus, then the reference contracts. */
    static List<Fixture> all(Path into) {
        List<Fixture> out = new ArrayList<>();
        for (String name : CORPUS) out.add(corpus(name, into.resolve("corpus-" + name)));
        for (String name : REFERENCE) out.add(reference(name, into.resolve("reference-" + name)));
        return out;
    }

    private static Fixture fixture(String name, Path document, Path async, GeneratedSources sources) {
        JsonNode report = Oracle.JSON.readTree(sources.report.renderValidValues(name, "1.0.0"));
        return new Fixture(name, document, sources, new Oracle(document), async == null ? null : new Oracle(async),
                report);
    }
}
