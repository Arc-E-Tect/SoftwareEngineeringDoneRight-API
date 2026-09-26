package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T13.2: every constraint on request input is listed exactly once, with at least one case or
 * exactly one reason from the closed list. The set of constraints is found again here, by a
 * walk of the contract document as written that shares no code with the generator, and must
 * equal the set the report lists: the test that catches a constraint silently dropped.
 */
@DisplayName("T13.2 Exhaustiveness")
class CoverageExhaustivenessTest {

    /** The closed list of reasons a constraint has no case. */
    static final List<String> REASONS = List.of("NOT_ISOLATABLE", "CHANGES_ROUTE", "KEYWORD_NOT_MODELLED",
            "PARAMETER_STYLE_NOT_SUPPORTED", "DEGRADED_CONSTRUCT", "FORMAT_VALIDATION_OFF", "PATTERN_OVERRIDES_FORMAT",
            "NO_INVALID_REQUEST_STATUS", "STRICTNESS_OFF", "BEYOND_RECURSION_DEPTH", "NOT_EXPRESSIBLE_IN_PARAMETER",
            "FORMAT_NOT_SUPPORTED", "NO_VALID_BASELINE", "ASSERTS_NOTHING", "NO_VIOLATION_RULE", "MEDIA_TYPE_NOT_USED");

    private static final List<String> KEYWORDS = List.of("type", "const", "enum", "minLength", "maxLength", "pattern",
            "format", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf", "minItems", "maxItems",
            "uniqueItems", "minProperties", "maxProperties", "oneOf", "anyOf", "not", "if", "then", "else",
            "dependentSchemas", "dependentRequired", "dependencies", "prefixItems", "additionalItems", "contains",
            "minContains", "maxContains", "propertyNames", "unevaluatedItems", "unevaluatedProperties", "$dynamicRef",
            "$recursiveRef");
    private static final Set<String> IGNORED_HEADERS = Set.of("accept", "content-type", "authorization");
    private static final int RECURSION_DEPTH = 2;

    @TempDir
    static Path directory;

    static List<ValidValueFixtures.Fixture> fixtures;

    @BeforeAll
    static void generate() {
        fixtures = InvalidRequestFixtures.all(directory);
    }

    /** One constraint: where it is, what it is, and where the contract writes it; a pointer may hold * for a member name. */
    record Entry(String operation, String in, String name, String mediaType, String pointer, String keyword,
                 String schemaLocation) {

        String key() {
            return String.join(" ", operation, in, String.valueOf(name), String.valueOf(mediaType), keyword,
                    schemaLocation);
        }

        boolean matches(Entry reported) {
            return key().equals(reported.key()) && Pattern.matches(Arrays.stream(pointer.split("/", -1))
                    .map(s -> s.equals("*") ? "[^/]+" : Pattern.quote(s))
                    .reduce((a, b) -> a + "/" + b).orElse(""), reported.pointer());
        }
    }

    @Test
    void theWalkFindsConstraintsInTheCorpusAndTheReferenceContract() {
        for (String name : List.of("keywords", "structure", "user-account")) {
            ValidValueFixtures.Fixture f = fixtures.stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow();
            assertThat(new Walk(Oracle.raw(f.document())).entries()).as(name).hasSizeGreaterThan(10);
        }
    }

    @Test
    void theClosedListIsTheReasonsTheGeneratorKnows() {
        assertThat(Arrays.stream(InvalidRequests.Reason.values()).map(Enum::name).toList())
                .containsExactlyElementsOf(REASONS);
    }

    @TestFactory
    Stream<DynamicTest> everyConstraintIsListedOnceWithCasesOrOneReason() {
        return fixtures.stream().map(f -> DynamicTest.dynamicTest(f.name(), () -> {
            Set<String> seen = new HashSet<>();
            for (JsonNode e : f.report().get("constraintCoverage")) {
                String key = String.join(" ", List.of("operation", "in", "name", "mediaType", "pointer", "keyword",
                        "schemaLocation").stream().map(k -> String.valueOf(text(e.get(k)))).toList());
                assertThat(seen.add(key)).as("listed once: %s", e).isTrue();
                boolean covered = e.has("cases");
                assertThat(covered ^ e.has("uncovered")).as("cases or a reason: %s", e).isTrue();
                if (covered) {
                    assertThat(e.get("cases").size()).as("covered by a case: %s", e).isPositive();
                } else {
                    assertThat(REASONS).contains(e.get("uncovered").get("code").stringValue());
                    assertThat(e.get("uncovered").get("detail").stringValue()).isNotBlank();
                }
            }
            // Every id a constraint names is a case of its operation, and every case covers something.
            for (JsonNode operation : f.report().get("invalidRequests")) {
                Set<String> ids = new LinkedHashSet<>();
                operation.get("cases").forEach(c -> ids.add(c.get("id").stringValue()));
                Set<String> named = new HashSet<>();
                for (JsonNode e : f.report().get("constraintCoverage")) {
                    if (!e.get("class").equals(operation.get("class")) || !e.has("cases")) continue;
                    e.get("cases").forEach(id -> named.add(id.stringValue()));
                }
                assertThat(named).containsExactlyInAnyOrderElementsOf(ids);
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> theReportListsExactlyTheConstraintsAnIndependentWalkFinds() {
        return fixtures.stream().map(f -> DynamicTest.dynamicTest(f.name(), () -> {
            List<Entry> expected = new Walk(Oracle.raw(f.document())).entries();
            List<Entry> reported = new ArrayList<>();
            for (JsonNode e : f.report().get("constraintCoverage")) {
                reported.add(new Entry(e.get("operation").stringValue(), e.get("in").stringValue(),
                        text(e.get("name")), text(e.get("mediaType")), e.get("pointer").stringValue(),
                        e.get("keyword").stringValue(), e.get("schemaLocation").stringValue()));
            }
            List<Entry> unmatched = new ArrayList<>(reported);
            List<Entry> missing = new ArrayList<>();
            for (Entry want : expected) {
                Entry found = unmatched.stream().filter(want::matches).findFirst().orElse(null);
                if (found == null) {
                    missing.add(want);
                } else {
                    unmatched.remove(found);
                }
            }
            assertThat(missing).as("constraints the report does not list").isEmpty();
            assertThat(unmatched).as("constraints the report lists that the walk does not find").isEmpty();
        }));
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.stringValue();
    }

    /** The walk of a contract document, as written. */
    private static final class Walk {
        private final JsonNode document;
        private final List<Entry> out = new ArrayList<>();
        private String operation;

        Walk(JsonNode document) {
            this.document = document;
        }

        List<Entry> entries() {
            for (var item : document.path("paths").properties()) {
                String itemAt = "/paths/" + RequestValidation.escape(item.getKey());
                for (String method : List.of("get", "put", "post", "delete", "options", "head", "patch", "trace")) {
                    JsonNode op = item.getValue().path(method);
                    if (op.isMissingNode()) continue;
                    operation = itemAt + "/" + method;
                    parameters(itemAt, operation);
                    body(op);
                }
            }
            return out;
        }

        private void parameters(String itemAt, String opAt) {
            List<String> own = new ArrayList<>();
            for (int i = 0; i < document.at(opAt + "/parameters").size(); i++) own.add(located(opAt + "/parameters/" + i));
            List<String> all = new ArrayList<>();
            for (int i = 0; i < document.at(itemAt + "/parameters").size(); i++) {
                String at = located(itemAt + "/parameters/" + i);
                String override = own.stream().filter(o -> same(o, at)).findFirst().orElse(null);
                if (override != null) {
                    own.remove(override);
                    all.add(override);
                } else {
                    all.add(at);
                }
            }
            all.addAll(own);
            for (String at : all) {
                JsonNode p = document.at(at);
                String in = p.path("in").stringValue("unknown");
                String name = p.path("name").stringValue(null);
                if (in.equals("header") && IGNORED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) continue;
                if (p.has("required")) out.add(new Entry(operation, in, name, null, "", "required", at));
                if (p.has("schema")) {
                    schema(in, name, null, "", List.of(at + "/schema"), List.of(), false);
                } else if (p.has("content")) {
                    out.add(new Entry(operation, in, name, null, "", "content", at));
                }
            }
        }

        private void body(JsonNode op) {
            JsonNode body = op.path("requestBody");
            String at = operation + "/requestBody";
            if (body.has("$ref")) {
                at = body.get("$ref").stringValue().substring(1);
                body = document.at(at);
            }
            if (body.path("content").isEmpty()) return;
            if (body.has("required")) out.add(new Entry(operation, "body", null, null, "", "required", at));
            for (var media : body.get("content").properties()) {
                if (!media.getValue().has("schema")) continue;
                schema("body", null, media.getKey(), "", List.of(at + "/content/" + RequestValidation.escape(media.getKey())
                        + "/schema"), List.of(), false);
            }
        }

        private String located(String at) {
            JsonNode node = document.at(at);
            return node.has("$ref") ? node.get("$ref").stringValue().substring(1) : at;
        }

        private boolean same(String a, String b) {
            return document.at(a).path("name").equals(document.at(b).path("name"))
                    && document.at(a).path("in").equals(document.at(b).path("in"));
        }

        private void schema(String in, String name, String media, String pointer, List<String> roots,
                            List<Set<String>> ancestors, boolean branch) {
            List<String> parts = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String root : roots) expand(root, seen, parts);
            Set<String> refs = new LinkedHashSet<>();
            for (String part : parts) {
                if (part.matches("/components/schemas/[^/]+")) refs.add(part.substring("/components/schemas/".length()));
            }
            boolean beyond = refs.stream().anyMatch(r -> ancestors.stream().filter(s -> s.contains(r)).count()
                    > RECURSION_DEPTH);
            for (String part : parts) {
                JsonNode s = document.at(part);
                if (!s.isObject()) continue;
                for (String keyword : KEYWORDS) {
                    if (s.has(keyword)) out.add(new Entry(operation, in, name, media, pointer, keyword, part));
                }
                if (s.has("required")) {
                    if (s.get("required").isEmpty()) out.add(new Entry(operation, in, name, media, pointer, "required", part));
                    Set<String> required = new LinkedHashSet<>();
                    s.get("required").forEach(r -> required.add(r.stringValue()));
                    for (String r : required) {
                        out.add(new Entry(operation, in, name, media, pointer + "/" + RequestValidation.escape(r),
                                "required", part));
                    }
                }
                if (s.path("additionalProperties").isBoolean() && !s.get("additionalProperties").booleanValue()) {
                    out.add(new Entry(operation, in, name, media, pointer, "additionalProperties", part));
                }
            }
            boolean objectLike = parts.stream().map(document::at).anyMatch(s -> s.path("type").stringValue("").equals("object")
                    || s.has("properties"));
            boolean open = parts.stream().map(document::at).anyMatch(s -> s.has("additionalProperties")
                    || s.has("patternProperties"));
            if (!branch && objectLike && !open) {
                out.add(new Entry(operation, in, name, media, pointer, "additionalProperties", parts.get(0)));
            }
            if (beyond) return;
            List<Set<String>> children = new ArrayList<>(ancestors);
            children.add(refs);
            Set<String> declared = new LinkedHashSet<>();
            for (String part : parts) document.at(part).path("properties").propertyNames().forEach(declared::add);
            for (String member : declared) {
                List<String> memberRoots = new ArrayList<>();
                for (String part : parts) {
                    JsonNode s = document.at(part);
                    boolean matched = false;
                    if (s.path("properties").has(member)) {
                        memberRoots.add(part + "/properties/" + RequestValidation.escape(member));
                        matched = true;
                    }
                    for (String regex : s.path("patternProperties").propertyNames()) {
                        if (Pattern.compile(regex).matcher(member).find()) {
                            memberRoots.add(part + "/patternProperties/" + RequestValidation.escape(regex));
                            matched = true;
                        }
                    }
                    if (!matched && s.path("additionalProperties").isObject()) memberRoots.add(part + "/additionalProperties");
                }
                schema(in, name, media, pointer + "/" + RequestValidation.escape(member), memberRoots, children, false);
            }
            for (String part : parts) {
                for (String regex : document.at(part).path("patternProperties").propertyNames()) {
                    schema(in, name, media, pointer + "/*", List.of(part + "/patternProperties/"
                            + RequestValidation.escape(regex)), children, false);
                }
            }
            List<String> additional = parts.stream().filter(p -> document.at(p).path("additionalProperties").isObject())
                    .map(p -> p + "/additionalProperties").toList();
            if (!additional.isEmpty()) schema(in, name, media, pointer + "/*", additional, children, false);
            List<String> items = parts.stream().filter(p -> document.at(p).has("items")).map(p -> p + "/items").toList();
            if (!items.isEmpty()) schema(in, name, media, pointer + "/0", items, children, false);
            for (String part : parts) {
                for (String keyword : List.of("oneOf", "anyOf")) {
                    JsonNode branches = document.at(part).path(keyword);
                    for (int i = 0; i < branches.size(); i++) {
                        schema(in, name, media, pointer, List.of(part + "/" + keyword + "/" + i), ancestors, true);
                    }
                }
            }
        }

        /** A schema's parts: what it refers to first, then itself, then its allOf, each once. */
        private void expand(String at, Set<String> seen, List<String> out) {
            JsonNode s = document.at(at);
            if (s.has("$ref")) {
                String target = s.get("$ref").stringValue().substring(1);
                if (seen.add(target)) expand(target, seen, out);
            }
            out.add(at);
            for (int i = 0; i < s.path("allOf").size(); i++) expand(at + "/allOf/" + i, seen, out);
        }
    }
}
