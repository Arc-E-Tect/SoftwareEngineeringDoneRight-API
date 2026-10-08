package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every contract the contract-case tests run over: the contract-case corpus, each contract with
 * the kinds it is derived with, and every contract the invalid-request tests run over.
 */
final class ContractCaseFixtures {

    static final Path CORPUS_DIRECTORY = GeneratedSources.FIXTURES.resolve("contract-cases/corpus");

    /** The contract-case corpus: each contract with every kind, and some with kinds switched off. */
    static final List<InvalidRequestFixtures.Variant> CORPUS = List.of(
            new InvalidRequestFixtures.Variant("success", "success", settings("success", null)),
            new InvalidRequestFixtures.Variant("not-found", "not-found", settings("not-found", null)),
            new InvalidRequestFixtures.Variant("negotiation", "negotiation", settings("negotiation", null)),
            new InvalidRequestFixtures.Variant("coverage", "coverage", settings("coverage", null)),
            new InvalidRequestFixtures.Variant("coverage-success-only", "coverage",
                    settings("coverage", List.of("success"))),
            new InvalidRequestFixtures.Variant("not-found-without-not-found", "not-found",
                    settings("not-found", List.of("success", "notAcceptable", "unsupportedMediaType",
                            "invalidRequest"))));

    private ContractCaseFixtures() {
    }

    static Settings settings(String contract, List<String> derive) {
        return new Settings(contract, GeneratedSources.PACKAGE, false, "PLACEHOLDER", 2, null, "400", true,
                List.of(), Map.of(), derive);
    }

    static InvalidRequestFixtures.Variant variant(String name) {
        return CORPUS.stream().filter(v -> v.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no corpus variant " + name));
    }

    /** One corpus variant, generated. */
    static ValidValueFixtures.Fixture corpus(String name, Path into) {
        InvalidRequestFixtures.Variant v = variant(name);
        Path document = CORPUS_DIRECTORY.resolve(v.contract() + ".yaml");
        GeneratedSources sources = GeneratedSources.generate(document, "1.0.0", into, v.settings(), List.of());
        JsonNode report = Oracle.JSON.readTree(sources.report.renderValidValues(v.settings().contract(), "1.0.0"));
        return new ValidValueFixtures.Fixture(v.name(), "1.0.0", document, sources, new Oracle(document), null, report);
    }

    /** The contract-case corpus, generated. */
    static List<ValidValueFixtures.Fixture> corpus(Path into) {
        List<ValidValueFixtures.Fixture> out = new ArrayList<>();
        for (InvalidRequestFixtures.Variant v : CORPUS) out.add(corpus(v.name(), into.resolve("contract-cases-" + v.name())));
        return out;
    }

    /** Every fixture contract: the invalid-request tests' ones, then the contract-case corpus. */
    static List<ValidValueFixtures.Fixture> all(Path into) {
        List<ValidValueFixtures.Fixture> out = new ArrayList<>(InvalidRequestFixtures.all(into));
        out.addAll(corpus(into));
        return out;
    }

    /** Every case of a report, of every kind, each with its operation's class and location. */
    static List<JsonNode> cases(JsonNode report) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode operation : report.get("contractCases")) {
            for (JsonNode c : operation.get("cases")) {
                tools.jackson.databind.node.ObjectNode copy = (tools.jackson.databind.node.ObjectNode) c.deepCopy();
                copy.put("class", operation.get("class").stringValue());
                copy.put("location", operation.get("location").stringValue());
                out.add(copy);
            }
        }
        return out;
    }

    /** The cases of one kind. */
    static List<JsonNode> cases(JsonNode report, String kind) {
        return cases(report).stream().filter(c -> c.get("kind").stringValue().equals(kind)).toList();
    }

    /** The report's valid requests of the operation a case belongs to: requiredRequest, fullRequest, noBodyRequest. */
    static JsonNode requests(JsonNode report, JsonNode c) {
        for (JsonNode e : report.get("requests")) {
            if (e.get("location").equals(c.get("location"))) return e;
        }
        throw new AssertionError("no valid requests for " + c.get("location"));
    }

    /** One of an operation's valid requests, as the report records it. */
    static JsonNode request(JsonNode report, JsonNode c, String method) {
        return requests(report, c).get(method).get("value");
    }

    /** A request without its {@code Accept} header. */
    static JsonNode withoutAccept(JsonNode request) {
        tools.jackson.databind.node.ObjectNode out = (tools.jackson.databind.node.ObjectNode) request.deepCopy();
        tools.jackson.databind.node.ArrayNode headers = (tools.jackson.databind.node.ArrayNode) out.get("headers");
        for (int i = headers.size() - 1; i >= 0; i--) {
            if (headers.get(i).get("name").stringValue().equals("Accept")) headers.remove(i);
        }
        return out;
    }

    /** The values of a request's {@code Accept} headers. */
    static List<String> accept(JsonNode request) {
        List<String> out = new ArrayList<>();
        request.get("headers").forEach(h -> {
            if (h.get("name").stringValue().equals("Accept")) out.add(h.get("value").stringValue());
        });
        return out;
    }

    /** The response-coverage entries of one operation class. */
    static List<String> coverage(JsonNode report, String className) {
        List<String> out = new ArrayList<>();
        report.get("responseCoverage").forEach(e -> {
            if (e.get("class").stringValue().equals(className)) out.add(coverageLine(e));
        });
        return out;
    }

    /** A case in one line: class, id, kind, status, the request's path values, content type and headers. */
    static String line(JsonNode c) {
        JsonNode r = c.get("request");
        List<String> path = new ArrayList<>();
        r.get("pathParameters").forEach(p -> path.add(p.stringValue()));
        List<String> headers = new ArrayList<>();
        r.get("headers").forEach(h -> headers.add(h.get("name").stringValue() + "=" + h.get("value").stringValue()));
        return c.get("class").stringValue() + " " + c.get("id").stringValue() + " " + c.get("kind").stringValue()
                + " " + c.get("expectedStatus").intValue() + " path" + path + " "
                + (r.get("contentType").isNull() ? "-" : r.get("contentType").stringValue()) + " " + headers;
    }

    /** A response-coverage entry in one line: class, status, and its cases or its reason's code. */
    static String coverageLine(JsonNode e) {
        String what;
        if (e.has("cases")) {
            List<String> ids = new ArrayList<>();
            e.get("cases").forEach(i -> ids.add(i.stringValue()));
            what = ids.toString();
        } else {
            what = e.get("uncovered").get("code").stringValue();
        }
        return e.get("class").stringValue() + " " + e.get("status").stringValue() + " " + what;
    }
}
