package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.model.CanonicalJson;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Component;
import com.arc_e_tect.gradle.apionly.transcriberj.model.ContractModel;
import com.arc_e_tect.gradle.apionly.transcriberj.model.MediaType;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Operation;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Parameter;
import com.arc_e_tect.gradle.apionly.transcriberj.model.PathItem;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Provenance;
import com.arc_e_tect.gradle.apionly.transcriberj.model.RequestBody;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Response;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Reusable;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Schema;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.ClassNames;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.GeneratedClass;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Origin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The core classes of one contract, and their names.
 *
 * <p>A component's class is named after its fragment's file, never after the key
 * the bundler gave it. Fragments that share a file name are told apart by the
 * nearest directory in which their paths differ. A schema written inline in an
 * operation is named after the operation.
 */
final class CoreClassNames implements ClassNames {

    /** The classes every generated tree has, whose names nothing else may take. */
    static final List<String> SUPPORT_CLASSES = List.of("ContractJson", "ContractField", "ContractManifest");

    private static final String INLINE_ADVICE = "an inline schema; define it as a schema component and $ref it, "
            + "so that its class is named after its fragment rather than after the operation";

    private final Shapes shapes;
    private final List<Candidate> candidates = new ArrayList<>();
    private final List<GeneratedClass> classes = new ArrayList<>();
    private final Map<String, GeneratedClass> byComponent = new LinkedHashMap<>();
    private final Map<String, GeneratedClass> byLocation = new LinkedHashMap<>();

    /** A class before its name is final. */
    private static final class Candidate {
        final Origin origin;
        final String key;
        final Provenance provenance;
        final Schema schema;
        final String location;
        String name;
        boolean exposed;
        /** The path of the operation the class belongs to, or null for a component. */
        String path;

        Candidate(Origin origin, String key, Provenance provenance, Schema schema, String location, String name) {
            this.origin = origin;
            this.key = key;
            this.provenance = provenance;
            this.schema = schema;
            this.location = location;
            this.name = name;
        }
    }

    private final Map<String, GeneratedClass> byOperation = new LinkedHashMap<>();
    private final Map<GeneratedClass, String> paths = new LinkedHashMap<>();

    CoreClassNames(ContractModel model, Shapes shapes, GenerationReport report) {
        this.shapes = shapes;
        List<String> unstamped = new ArrayList<>();

        for (Component c : model.components()) {
            String location = "/components/schemas/" + Shapes.escape(c.name());
            component(Origin.SCHEMA, c.name(), c.provenance(), c.schema(), location, location, unstamped);
        }
        for (Reusable<Response> r : model.responses()) {
            String location = "/components/responses/" + Shapes.escape(r.name());
            MediaType single = singleSchema(r.value().content());
            component(Origin.RESPONSE, r.name(), r.provenance(), single == null ? null : single.schema(),
                    location, contentLocation(location, single), unstamped);
        }
        for (Reusable<RequestBody> b : model.requestBodies()) {
            String location = "/components/requestBodies/" + Shapes.escape(b.name());
            MediaType single = singleSchema(b.value().content());
            component(Origin.REQUEST_BODY, b.name(), b.provenance(), single == null ? null : single.schema(),
                    location, contentLocation(location, single), unstamped);
        }
        for (Reusable<Parameter> p : model.parameters()) {
            String location = "/components/parameters/" + Shapes.escape(p.name());
            component(Origin.PARAMETER, p.name(), p.provenance(), p.value().schema(),
                    location, location + "/schema", unstamped);
        }
        if (!unstamped.isEmpty()) {
            throw new GenerationException("These components have no x-fragment-path, so no class can be named "
                    + "after their fragment: " + String.join(", ", unstamped) + ". Build the contract with the "
                    + "API-Only Publisher and defaults.openapi.fragmentPaths left on.");
        }

        for (Operation operation : model.operations()) {
            String location = operationLocation(operation);
            Candidate candidate = new Candidate(Origin.OPERATION, location,
                    new Provenance(null, CanonicalJson.sha256(operationSummary(operation))), null, location,
                    operationName(operation) + "Operation");
            candidate.path = operation.path();
            candidates.add(candidate);
        }
        for (Operation operation : model.operations()) {
            inline(operation, report);
        }

        resolveCollisions();
        expose(model);

        for (Candidate c : candidates) {
            GeneratedClass generated = new GeneratedClass(c.name, c.origin, c.key, c.exposed, c.provenance,
                    c.schema, c.schema != null && shapes.isObject(c.schema));
            classes.add(generated);
            if (c.path != null) paths.put(generated, c.path);
            if (c.origin == Origin.OPERATION) {
                byOperation.put(c.key, generated);
            } else if (c.origin == Origin.INLINE_REQUEST || c.origin == Origin.INLINE_RESPONSE) {
                byLocation.put(c.key, generated);
            } else {
                byComponent.put(c.origin + "/" + c.key, generated);
            }
        }
    }

    private void component(Origin origin, String name, Provenance provenance, Schema schema, String location,
                           String schemaLocation, List<String> unstamped) {
        if (provenance.fragmentPath() == null) {
            unstamped.add(location);
            return;
        }
        String file = provenance.fragmentPath().substring(provenance.fragmentPath().lastIndexOf('/') + 1)
                .replaceFirst("\\.ya?ml$", "");
        candidates.add(new Candidate(origin, name, provenance, schema, schemaLocation, JavaText.typeName(file)));
    }

    /** The one media type with a schema, or null when there is not exactly one. */
    private static MediaType singleSchema(List<MediaType> content) {
        if (content == null) return null;
        List<MediaType> withSchema = content.stream().filter(m -> m.schema() != null).toList();
        return withSchema.size() == 1 ? withSchema.get(0) : null;
    }

    private static String contentLocation(String location, MediaType single) {
        return single == null ? null : location + "/content/" + Shapes.escape(single.contentType()) + "/schema";
    }

    static String operationLocation(Operation operation) {
        return "/paths/" + Shapes.escape(operation.path()) + "/" + operation.method().key();
    }

    /** An operation's name: its operationId, or else its method and path. */
    private static String operationName(Operation operation) {
        if (operation.operationId() != null) {
            return JavaText.typeName(operation.operationId());
        }
        return JavaText.typeName(operation.method().key() + " "
                + operation.path().replace("{", " by ").replace("}", " "));
    }

    /** What an operation class is generated from, for its hash. */
    private static Map<String, Object> operationSummary(Operation operation) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("method", operation.method().name());
        summary.put("path", operation.path());
        summary.put("operationId", operation.operationId());
        if (operation.requestBody() != null && operation.requestBody().content() != null) {
            summary.put("requestContentTypes",
                    operation.requestBody().content().stream().map(MediaType::contentType).toList());
        }
        Map<String, Object> responses = new LinkedHashMap<>();
        if (operation.responses() != null) {
            for (Response response : operation.responses()) {
                responses.put(response.status(), response.content() == null ? List.of()
                        : response.content().stream().map(MediaType::contentType).toList());
            }
        }
        summary.put("responses", responses);
        return summary;
    }

    private void inline(Operation operation, GenerationReport report) {
        String location = operationLocation(operation);
        String operationName = operationName(operation);
        boolean named = false;

        RequestBody body = operation.requestBody();
        if (body != null && body.reference() == null && body.content() != null) {
            named |= inlineContent(body.content(), operation.path(), location + "/requestBody",
                    operationName + "Request", Origin.INLINE_REQUEST, report);
        }
        if (operation.responses() != null) {
            for (Response response : operation.responses()) {
                if (response.reference() != null || response.content() == null) continue;
                named |= inlineContent(response.content(), operation.path(),
                        location + "/responses/" + Shapes.escape(response.status()),
                        operationName + JavaText.typeName("Response " + response.status()),
                        Origin.INLINE_RESPONSE, report);
            }
        }
        if (named && operation.operationId() == null) {
            report.recommend(location, "an operation without an operationId; give it one, since its classes "
                    + "are named after its method and path until it has one");
        }
    }

    private boolean inlineContent(List<MediaType> content, String path, String location, String baseName,
                                  Origin origin, GenerationReport report) {
        List<MediaType> needing = content.stream().filter(m -> needsClass(m.schema())).toList();
        for (MediaType mediaType : needing) {
            String schemaLocation = location + "/content/" + Shapes.escape(mediaType.contentType()) + "/schema";
            String subtype = mediaType.contentType().substring(mediaType.contentType().indexOf('/') + 1);
            String name = needing.size() == 1 ? baseName : baseName + JavaText.typeName(subtype);
            Provenance provenance = new Provenance(null, CanonicalJson.sha256(Shapes.render(mediaType.schema())));
            Candidate candidate = new Candidate(origin, schemaLocation, provenance, mediaType.schema(),
                    schemaLocation, name);
            candidate.path = path;
            candidates.add(candidate);
            report.recommend(schemaLocation, INLINE_ADVICE);
        }
        return !needing.isEmpty();
    }

    /** Whether an inline schema is more than a reference, or a choice between references. */
    private static boolean needsClass(Schema schema) {
        if (schema == null || schema.literal() != null || schema.ref() != null) return false;
        List<Schema> choice = schema.oneOf() != null ? schema.oneOf() : schema.anyOf();
        boolean onlyChoice = choice != null && schema.properties() == null && schema.allOf() == null
                && schema.types() == null && schema.items() == null;
        return !(onlyChoice && choice.stream().allMatch(b -> b.ref() != null));
    }

    private void resolveCollisions() {
        Map<String, List<Candidate>> byName = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            byName.computeIfAbsent(c.name, k -> new ArrayList<>()).add(c);
        }
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, List<Candidate>> entry : byName.entrySet()) {
            List<Candidate> group = entry.getValue();
            if (SUPPORT_CLASSES.contains(entry.getKey())) {
                problems.add(describe(group) + " would be named " + entry.getKey()
                        + ", which the generated support classes use");
                continue;
            }
            if (group.size() == 1) continue;
            if (group.stream().anyMatch(c -> c.provenance.fragmentPath() == null)) {
                problems.add(describe(group) + " would all be named " + entry.getKey());
                continue;
            }
            List<String> prefixes = group.stream().map(c -> distinguishingDirectory(c, group)).toList();
            for (int i = 0; i < group.size(); i++) {
                if (prefixes.get(i) != null) group.get(i).name = JavaText.typeName(prefixes.get(i)) + group.get(i).name;
            }
        }
        Set<String> seen = new HashSet<>();
        for (Candidate c : candidates) {
            if (!seen.add(c.name) && problems.isEmpty()) {
                problems.add(describe(candidates.stream().filter(o -> o.name.equals(c.name)).toList())
                        + " would still all be named " + c.name + " after telling them apart by directory");
            }
        }
        if (!problems.isEmpty()) {
            throw new GenerationException("Class names collide: " + String.join("; ", problems)
                    + ". Rename one of the fragments or operations.");
        }
    }

    private static String describe(List<Candidate> group) {
        return String.join(" and ", group.stream()
                .map(c -> c.provenance.fragmentPath() != null ? c.provenance.fragmentPath() : c.key).toList());
    }

    /**
     * The nearest directory at which this candidate's path, read from its file
     * upwards, stops matching every other candidate's; null when another candidate
     * has exactly the same directories.
     */
    private static String distinguishingDirectory(Candidate candidate, List<Candidate> group) {
        List<String> own = directories(candidate);
        List<List<String>> others = group.stream().filter(o -> o != candidate).map(CoreClassNames::directories).toList();
        for (int i = 0; i < own.size(); i++) {
            List<String> nearest = own.subList(0, i + 1);
            int depth = i + 1;
            if (others.stream().noneMatch(o -> o.size() >= depth && o.subList(0, depth).equals(nearest))) {
                return own.get(i);
            }
        }
        return others.contains(own) ? null : "Root";
    }

    /** A fragment's directories, nearest first. */
    private static List<String> directories(Candidate candidate) {
        String[] parts = candidate.provenance.fragmentPath().split("/");
        List<String> directories = new ArrayList<>();
        for (int i = parts.length - 2; i >= 0; i--) {
            directories.add(parts[i]);
        }
        return directories;
    }

    /**
     * Marks the classes code outside the package needs: bodies of requests and
     * responses, parameters, every non-schema class, and whatever a public body
     * needs an argument rendered by.
     */
    private void expose(ContractModel model) {
        Set<String> exposedSchemas = new HashSet<>();
        for (Candidate c : candidates) {
            if (c.origin != Origin.SCHEMA) {
                c.exposed = true;
                if (c.schema != null) exposedSchemas.addAll(topLevelReferences(c.schema));
            }
        }
        for (PathItem item : model.paths()) {
            parameterReferences(item.parameters(), exposedSchemas);
            for (Operation operation : item.operations()) {
                parameterReferences(operation.parameters(), exposedSchemas);
                if (operation.requestBody() != null && operation.requestBody().content() != null) {
                    operation.requestBody().content().forEach(m -> exposedSchemas.addAll(topLevelReferences(m.schema())));
                }
                if (operation.responses() != null) {
                    for (Response response : operation.responses()) {
                        if (response.content() != null) {
                            response.content().forEach(m -> exposedSchemas.addAll(topLevelReferences(m.schema())));
                        }
                    }
                }
            }
        }

        // Whatever an exposed body takes rendered JSON of must be exposed too.
        List<Schema> pending = new ArrayList<>();
        candidates.stream().filter(c -> c.exposed && c.schema != null).forEach(c -> pending.add(c.schema));
        exposedSchemas.forEach(name -> shapes.component(name).ifPresent(pending::add));
        Set<String> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            Schema schema = pending.remove(pending.size() - 1);
            for (String name : shapes.renderedReferences(schema)) {
                exposedSchemas.add(name);
                if (visited.add(name)) shapes.component(name).ifPresent(pending::add);
            }
        }
        for (Candidate c : candidates) {
            if (c.origin == Origin.SCHEMA && exposedSchemas.contains(c.key)) c.exposed = true;
        }
    }

    private static void parameterReferences(List<Parameter> parameters, Set<String> into) {
        if (parameters == null) return;
        for (Parameter p : parameters) {
            if (p.schema() != null && p.schema().ref() != null) into.add(p.schema().ref());
        }
    }

    private static List<String> topLevelReferences(Schema schema) {
        List<String> names = new ArrayList<>();
        if (schema == null) return names;
        if (schema.ref() != null) names.add(schema.ref());
        for (List<Schema> choice : java.util.Arrays.asList(schema.oneOf(), schema.anyOf())) {
            if (choice != null) choice.stream().filter(b -> b.ref() != null).forEach(b -> names.add(b.ref()));
        }
        return names;
    }

    @Override
    public List<GeneratedClass> all() {
        return List.copyOf(classes);
    }

    @Override
    public Optional<GeneratedClass> component(Origin origin, String componentName) {
        return Optional.ofNullable(byComponent.get(origin + "/" + componentName));
    }

    @Override
    public Optional<GeneratedClass> operation(String location) {
        return Optional.ofNullable(byOperation.get(location));
    }

    /**
     * The path of the operation a class belongs to.
     *
     * @param generated an operation class or an inline schema's class
     * @return the path, or {@code null} for a component's class
     */
    String path(GeneratedClass generated) {
        return paths.get(generated);
    }

    @Override
    public Optional<GeneratedClass> inline(String location) {
        return Optional.ofNullable(byLocation.get(location));
    }

    /**
     * The JSON pointer of the schema a class's body is generated from.
     *
     * @param generated the class
     * @return the pointer, or {@code null} when it has no schema
     */
    String schemaLocation(GeneratedClass generated) {
        for (Candidate c : candidates) {
            if (c.origin == generated.origin() && c.key.equals(generated.key())) return c.location;
        }
        return null;
    }
}
