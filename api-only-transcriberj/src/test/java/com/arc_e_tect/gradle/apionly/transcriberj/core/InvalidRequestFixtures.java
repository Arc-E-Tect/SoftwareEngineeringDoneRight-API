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

    /** Whether a fixture was generated with strictness on. */
    static boolean strict(ValidValueFixtures.Fixture f) {
        return CORPUS.stream().filter(v -> v.name().equals(f.name())).findFirst()
                .map(v -> v.settings().strictRequests()).orElse(true);
    }

    private static ValidValueFixtures.Fixture fixture(String name, Path document, GeneratedSources sources,
                                                      Settings settings) {
        JsonNode report = Oracle.JSON.readTree(sources.report.renderValidValues(settings.contract(), "1.0.0"));
        return new ValidValueFixtures.Fixture(name, document, sources, new Oracle(document), null, report);
    }

    /** The invalid-request part of a machine-readable report, as its golden file holds it. */
    static String golden(ValidValueFixtures.Fixture f) {
        var out = Oracle.JSON.createObjectNode();
        for (String key : List.of("constraintCoverage", "formatRecommendations", "gaps", "invalidRequests")) {
            out.set(key, f.report().get(key));
        }
        return Baselines.pretty(out, "") + "\n";
    }

    static boolean exists(Path path) {
        return Files.exists(path);
    }
}
