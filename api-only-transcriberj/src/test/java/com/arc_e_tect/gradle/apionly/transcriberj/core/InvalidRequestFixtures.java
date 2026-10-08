package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every contract the invalid-request tests run over, generated with the settings each is
 * written for: the valid-value corpus, the invalid-request corpus, and the reference
 * contracts.
 */
final class InvalidRequestFixtures {

    static final Path CORPUS_DIRECTORY = GeneratedSources.FIXTURES.resolve("invalid-requests/corpus");
    static final Path GOLDEN = GeneratedSources.FIXTURES.resolve("invalid-requests/golden");

    /**
     * One generation of a corpus contract: its name, the file it is generated from, and the
     * settings it is generated with.
     */
    record Variant(String name, String contract, Settings settings) {
    }

    /** The invalid-request corpus, each contract with the settings its comment names. */
    static final List<Variant> CORPUS = List.of(
            new Variant("keywords", "keywords", settings("keywords", "400", true, List.of("date", "uuid"))),
            new Variant("isolation", "isolation", settings("isolation")),
            new Variant("routes", "routes", settings("routes")),
            new Variant("strictness", "strictness", settings("strictness")),
            new Variant("strictness-off", "strictness", settings("strictness", "400", false, List.of())),
            new Variant("formats", "formats", settings("formats")),
            new Variant("formats-validated", "formats", settings("formats", "400", true,
                    List.of("email", "duration"))),
            new Variant("responses", "responses", settings("responses")),
            new Variant("responses-422", "responses", settings("responses", "422", true, List.of())),
            new Variant("degraded", "degraded", settings("degraded")),
            new Variant("collisions", "collisions", settings("collisions")),
            new Variant("media", "media", settings("media")),
            new Variant("structure", "structure", settings("structure")));

    private InvalidRequestFixtures() {
    }

    static Settings settings(String contract) {
        return GeneratedSources.settings(contract);
    }

    static Settings settings(String contract, String status, boolean strict, List<String> formats) {
        return new Settings(contract, GeneratedSources.PACKAGE, false, "PLACEHOLDER", 2, null, status, strict,
                formats, Map.of());
    }

    static Variant variant(String name) {
        return CORPUS.stream().filter(v -> v.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no corpus variant " + name));
    }

    /** One corpus variant, generated. */
    static ValidValueFixtures.Fixture corpus(String name, Path into) {
        Variant v = variant(name);
        Path document = CORPUS_DIRECTORY.resolve(v.contract() + ".yaml");
        GeneratedSources sources = GeneratedSources.generate(document, "1.0.0", into, v.settings(), List.of());
        return fixture(v.name(), document, sources, v.settings());
    }

    /** Every fixture contract: the valid-value corpus, the invalid-request corpus, the reference contracts. */
    static List<ValidValueFixtures.Fixture> all(Path into) {
        List<ValidValueFixtures.Fixture> out = new ArrayList<>();
        for (String name : ValidValueFixtures.CORPUS) {
            out.add(ValidValueFixtures.corpus(name, into.resolve("valid-values-" + name)));
        }
        for (Variant v : CORPUS) out.add(corpus(v.name(), into.resolve("invalid-requests-" + v.name())));
        for (String name : ValidValueFixtures.REFERENCE) {
            out.add(ValidValueFixtures.reference(name, into.resolve("reference-" + name)));
        }
        return out;
    }

    /**
     * Every fixture contract, as {@link #all} generates it, but the reference contracts without their
     * {@code example} and {@code examples}: as they were generated before values were taken from
     * examples, for the tests that compare with what was recorded then.
     */
    static List<ValidValueFixtures.Fixture> allWithoutExamples(Path into) {
        List<ValidValueFixtures.Fixture> out = new ArrayList<>();
        for (String name : ValidValueFixtures.CORPUS) {
            out.add(ValidValueFixtures.corpus(name, into.resolve("valid-values-" + name)));
        }
        for (Variant v : CORPUS) out.add(corpus(v.name(), into.resolve("invalid-requests-" + v.name())));
        for (String name : ValidValueFixtures.REFERENCE) {
            Path documents = withoutExamples(GeneratedSources.CONTRACTS.resolve(name),
                    into.resolve("reference-" + name + "-without-examples"));
            out.add(ValidValueFixtures.reference(name, documents, into.resolve("reference-" + name)));
        }
        return out;
    }

    /**
     * A copy of a contract's documents with every {@code example}, {@code examples} and
     * {@code x-transcriberj-examples} left out.
     */
    private static Path withoutExamples(Path directory, Path into) {
        try {
            Files.createDirectories(into);
            for (String file : List.of("openapi.yaml", "asyncapi.yaml")) {
                Path document = directory.resolve(file);
                if (!Files.exists(document)) continue;
                Object parsed = new org.snakeyaml.engine.v2.api.Load(
                        org.snakeyaml.engine.v2.api.LoadSettings.builder().build())
                        .loadFromString(Files.readString(document, java.nio.charset.StandardCharsets.UTF_8));
                Files.writeString(into.resolve(file), Oracle.JSON.writeValueAsString(withoutExamples(parsed, null)));
            }
            return into;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** A parsed document without its example data, except where a key names a property. */
    private static Object withoutExamples(Object node, String parentKey) {
        if (node instanceof Map<?, ?> map) {
            Map<Object, Object> out = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                boolean named = "properties".equals(parentKey) || "patternProperties".equals(parentKey);
                if (!named && (key.equals("example") || key.equals("examples")
                        || key.equals(ValidRequests.NAMED_EXAMPLES))) {
                    continue;
                }
                out.put(e.getKey(), withoutExamples(e.getValue(), key));
            }
            return out;
        }
        if (node instanceof List<?> list) return list.stream().map(item -> withoutExamples(item, null)).toList();
        return node;
    }

    /** Whether a fixture was generated with strictness on. */
    static boolean strict(ValidValueFixtures.Fixture f) {
        return CORPUS.stream().filter(v -> v.name().equals(f.name())).findFirst()
                .map(v -> v.settings().strictRequests()).orElse(true);
    }

    private static ValidValueFixtures.Fixture fixture(String name, Path document, GeneratedSources sources,
                                                      Settings settings) {
        JsonNode report = Oracle.JSON.readTree(sources.report.renderValidValues(settings.contract(), "1.0.0"));
        return new ValidValueFixtures.Fixture(name, "1.0.0", document, sources, new Oracle(document), null, report);
    }

    /**
     * A report's invalid-request cases in the shape they had before contract cases of every kind
     * were derived: each operation's entry with only its invalid-request cases, without the fields
     * that came with the other kinds, and without the {@code Accept} every case now sends.
     */
    static JsonNode invalidRequests(JsonNode report) {
        tools.jackson.databind.node.ArrayNode out = Oracle.JSON.createArrayNode();
        for (JsonNode operation : report.get("contractCases")) {
            tools.jackson.databind.node.ObjectNode entry = (tools.jackson.databind.node.ObjectNode) operation.deepCopy();
            tools.jackson.databind.node.ArrayNode cases = Oracle.JSON.createArrayNode();
            for (JsonNode c : operation.get("cases")) {
                if (!c.get("kind").stringValue().equals("INVALID_REQUEST")) continue;
                tools.jackson.databind.node.ObjectNode old = (tools.jackson.databind.node.ObjectNode) c.deepCopy();
                old.remove(List.of("kind", "requiresState", "variant", "source"));
                tools.jackson.databind.node.ArrayNode headers = (tools.jackson.databind.node.ArrayNode)
                        old.get("request").get("headers");
                for (int i = headers.size() - 1; i >= 0; i--) {
                    if (headers.get(i).get("name").stringValue().equals("Accept")) headers.remove(i);
                }
                cases.add(old);
            }
            entry.set("cases", cases);
            out.add(entry);
        }
        return out;
    }

    /** The invalid-request part of a machine-readable report, as its golden file holds it. */
    static String golden(ValidValueFixtures.Fixture f) {
        var out = Oracle.JSON.createObjectNode();
        for (String key : List.of("constraintCoverage", "contractCases", "formatRecommendations", "gaps",
                "responseCoverage")) {
            out.set(key, f.report().get(key));
        }
        return Baselines.pretty(out, "") + "\n";
    }

    static boolean exists(Path path) {
        return Files.exists(path);
    }
}
