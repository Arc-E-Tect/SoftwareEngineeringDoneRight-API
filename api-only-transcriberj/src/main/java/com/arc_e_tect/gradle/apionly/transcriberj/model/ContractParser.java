package com.arc_e_tect.gradle.apionly.transcriberj.model;

import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.schema.CoreSchema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a {@link ContractModel} from a bundled OpenAPI 3 contract.
 *
 * <p>Bundled means self-contained: every {@code $ref} in a schema points at a schema
 * component in the same document, which is what the API-Only Publisher produces.
 */
public final class ContractParser {

    private static final String SCHEMA_REF_PREFIX = "#/components/schemas/";
    private static final String FRAGMENT_PATH = "x-fragment-path";

    private static final Set<String> ANNOTATIONS = Set.of(
            "title", "default", "examples", "example", "readOnly", "writeOnly", "deprecated",
            "externalDocs", "xml", "$comment", "contentMediaType", "contentEncoding");

    private static final String RESPONSES = "responses";
    private static final String PARAMETERS = "parameters";
    private static final String REQUEST_BODIES = "requestBodies";

    private final String source;
    private final Set<String> componentNames = new HashSet<>();
    private final Map<String, Map<String, Object>> reusableRaw = new HashMap<>();
    private final Map<String, Object> reusableTyped = new HashMap<>();
    private final Set<String> resolving = new LinkedHashSet<>();
    private final List<Finding> findings = new ArrayList<>();
    private final List<RefSite> refSites = new ArrayList<>();
    private String currentComponent;

    private record RefSite(String from, String to, String location) {
    }

    /** Types an object that is not a reference. */
    @FunctionalInterface
    private interface Typer<T> {
        T type(Map<String, Object> object, String location);
    }

    /** The same object, as written through the given reference. */
    @FunctionalInterface
    private interface Rereference<T> {
        T through(T resolved, Reference reference);
    }

    private ContractParser(String source) {
        this.source = source;
    }

    /**
     * Parses a contract file, read as UTF-8.
     *
     * @param file the contract
     * @return the model
     * @throws IOException            when the file cannot be read
     * @throws ContractModelException when it is not a bundled OpenAPI 3 contract;
     *                                the message starts with the file's path
     */
    public static ContractModel parse(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
    }

    /**
     * Parses a contract's two documents into one model.
     *
     * <p>A specification library shares fragments between them: an event payload
     * references the schema of a username the HTTP responses use, so that a username
     * means the same thing either way. Both documents are therefore read into one
     * model, keyed by fragment, and a fragment they share is one component of it --
     * which is what makes it one generated class rather than two of the same name.
     *
     * @param openapi  the OpenAPI document
     * @param asyncapi the AsyncAPI document, or {@code null} when the contract has none
     * @return the model
     * @throws IOException            when a file cannot be read
     * @throws ContractModelException when a document is not what it claims to be
     */
    public static ContractModel parse(Path openapi, Path asyncapi) throws IOException {
        ContractModel http = parse(openapi);
        if (asyncapi == null) return http;
        String text = Files.readString(asyncapi, StandardCharsets.UTF_8);
        Object root;
        try {
            root = new Load(LoadSettings.builder().setSchema(new CoreSchema()).build()).loadFromString(text);
        } catch (YamlEngineException e) {
            throw new ContractModelException(asyncapi + ": is not valid YAML: " + e.getMessage(), e);
        }
        return new ContractParser(asyncapi.toString()).asyncInto(http, root);
    }

    /**
     * Parses a contract's text.
     *
     * @param text   the contract, as YAML or JSON
     * @param source what to call the contract in error messages
     * @return the model
     * @throws ContractModelException when it is not a bundled OpenAPI 3 contract;
     *                                the message starts with {@code source}
     */
    public static ContractModel parse(String text, String source) {
        Object root;
        try {
            root = new Load(LoadSettings.builder().setSchema(new CoreSchema()).build()).loadFromString(text);
        } catch (YamlEngineException e) {
            throw new ContractModelException(source + ": is not valid YAML: " + e.getMessage(), e);
        }
        return new ContractParser(source).document(root);
    }

    private ContractModel document(Object root) {
        Map<String, Object> document = map(root, "");
        Object openapi = document.get("openapi");
        if (!(openapi instanceof String version) || !version.startsWith("3.")) {
            throw error("", "not an OpenAPI 3 document (openapi: " + openapi + ")");
        }

        Map<String, Object> info = optionalMap(document.get("info"), "/info");
        String title = info == null ? null : string(info.get("title"), "/info/title");
        Object contractVersion = info == null ? null : info.get("version");

        Map<String, Object> components = optionalMap(document.get("components"), "/components");
        List<Component> schemas = new ArrayList<>();
        List<Reusable<Response>> responses = new ArrayList<>();
        List<Reusable<Parameter>> parameters = new ArrayList<>();
        List<Reusable<RequestBody>> requestBodies = new ArrayList<>();
        Map<String, Object> otherComponents = new LinkedHashMap<>();
        if (components != null) {
            Map<String, Object> schemaMap = optionalMap(components.get("schemas"), "/components/schemas");
            if (schemaMap != null) {
                componentNames.addAll(schemaMap.keySet());
            }
            for (String type : List.of(RESPONSES, PARAMETERS, REQUEST_BODIES)) {
                Map<String, Object> typed = optionalMap(components.get(type), "/components/" + type);
                if (typed != null) {
                    Map<String, Object> raws = new LinkedHashMap<>();
                    typed.forEach((name, raw) -> raws.put(name, raw));
                    reusableRaw.put(type, raws);
                }
            }
            if (schemaMap != null) {
                schemaMap.forEach((name, raw) -> schemas.add(component(name, raw)));
            }
            reusableRaw.getOrDefault(RESPONSES, Map.of()).forEach((name, raw) ->
                    responses.add(reusable(RESPONSES, name, raw, responseTyper(null), responseRereference(null))));
            reusableRaw.getOrDefault(PARAMETERS, Map.of()).forEach((name, raw) ->
                    parameters.add(reusable(PARAMETERS, name, raw, this::parameter, ContractParser::rereference)));
            reusableRaw.getOrDefault(REQUEST_BODIES, Map.of()).forEach((name, raw) ->
                    requestBodies.add(reusable(REQUEST_BODIES, name, raw, this::requestBody,
                            ContractParser::rereference)));
            components.forEach((type, entries) -> {
                if (List.of("schemas", RESPONSES, PARAMETERS, REQUEST_BODIES).contains(type)) return;
                otherComponents.put(type, entries);
                Map<String, Object> typed = optionalMap(entries, "/components/" + escape(type));
                if (typed != null) {
                    typed.keySet().forEach(name -> findings.add(new Finding(
                            "/components/" + escape(type) + "/" + escape(name),
                            Construct.UNMODELLED_COMPONENT_TYPE, Treatment.UNDECIDED, type)));
                }
            });
        }
        findRecursion(schemas);

        List<PathItem> paths = new ArrayList<>();
        Map<String, Object> pathMap = optionalMap(document.get("paths"), "/paths");
        if (pathMap != null) {
            pathMap.forEach((path, raw) -> paths.add(pathItem(path, raw)));
        }

        return new ContractModel(
                version,
                title,
                contractVersion == null ? null : String.valueOf(contractVersion),
                List.copyOf(schemas),
                List.copyOf(responses),
                List.copyOf(parameters),
                List.copyOf(requestBodies),
                Collections.unmodifiableMap(otherComponents),
                List.copyOf(paths),
                List.of(),
                List.of(),
                List.copyOf(findings));
    }

    // -------------------------------------------------------------- asyncapi

    /** The OpenAPI model, with everything the AsyncAPI document adds to it. */
    private ContractModel asyncInto(ContractModel http, Object root) {
        Map<String, Object> document = map(root, "");
        Object asyncapi = document.get("asyncapi");
        if (!(asyncapi instanceof String version) || !version.startsWith("3.")) {
            throw error("", "not an AsyncAPI 3 document (asyncapi: " + asyncapi + ")");
        }
        Map<String, Object> info = optionalMap(document.get("info"), "/info");
        Object contractVersion = info == null ? null : info.get("version");
        if (contractVersion != null && http.version() != null
                && !String.valueOf(contractVersion).equals(http.version())) {
            throw error("/info/version", "says the contract is version " + contractVersion
                    + ", but its OpenAPI document says " + http.version()
                    + ". One contract is one version; publish both documents from the same build.");
        }

        List<Component> components = new ArrayList<>(http.components());
        List<AsyncChannel> channels = new ArrayList<>();
        Map<String, Object> channelMap = optionalMap(document.get("channels"), "/channels");
        if (channelMap != null) {
            channelMap.forEach((key, raw) -> channels.add(channel(key, raw, components)));
        }

        List<AsyncOperation> operations = new ArrayList<>();
        Map<String, Object> operationMap = optionalMap(document.get("operations"), "/operations");
        if (operationMap != null) {
            operationMap.forEach((id, raw) -> operations.add(asyncOperation(id, raw)));
        }

        List<Finding> all = new ArrayList<>(http.findings());
        all.addAll(findings);
        return new ContractModel(http.openapi(), http.title(), http.version(), List.copyOf(components),
                http.responses(), http.parameters(), http.requestBodies(), http.otherComponents(), http.paths(),
                List.copyOf(channels), List.copyOf(operations), List.copyOf(all));
    }

    private AsyncChannel channel(String key, Object raw, List<Component> components) {
        String location = "/channels/" + escape(key);
        Map<String, Object> channel = map(raw, location);
        List<AsyncMessage> messages = new ArrayList<>();
        Map<String, Object> messageMap = optionalMap(channel.get("messages"), location + "/messages");
        if (messageMap != null) {
            messageMap.forEach((messageKey, messageRaw) ->
                    messages.add(message(messageKey, messageRaw, location, components)));
        }
        return new AsyncChannel(key, provenance(raw, location),
                optionalString(channel.get("address"), location + "/address"),
                optionalString(channel.get("title"), location + "/title"),
                optionalString(channel.get("description"), location + "/description"),
                List.copyOf(messages), location);
    }

    private AsyncMessage message(String key, Object raw, String channelLocation, List<Component> components) {
        String location = channelLocation + "/messages/" + escape(key);
        Map<String, Object> message = map(raw, location);
        Component payload = null;
        Object payloadRaw = message.get("payload");
        if (payloadRaw != null) {
            String payloadLocation = location + "/payload";
            Component parsed = new Component(key, provenance(payloadRaw, payloadLocation),
                    schema(withoutFragmentPath(payloadRaw, payloadLocation), payloadLocation), payloadLocation);
            payload = share(parsed, components);
        }
        return new AsyncMessage(key, provenance(raw, location),
                optionalString(message.get("name"), location + "/name"),
                optionalString(message.get("title"), location + "/title"),
                optionalString(message.get("summary"), location + "/summary"),
                optionalString(message.get("contentType"), location + "/contentType"),
                payload, location);
    }

    /**
     * The component this payload is, added to the model unless its fragment is
     * already in it: the same fragment is the same component, and one class.
     */
    private Component share(Component parsed, List<Component> components) {
        String fragment = parsed.provenance().fragmentPath();
        if (fragment == null) {
            components.add(parsed);
            return parsed;
        }
        for (Component existing : components) {
            if (!fragment.equals(existing.provenance().fragmentPath())) continue;
            if (!existing.provenance().sha256().equals(parsed.provenance().sha256())) {
                findings.add(new Finding(parsed.location(), Construct.FRAGMENT_BUNDLED_DIFFERENTLY,
                        Treatment.UNDECIDED, fragment));
            }
            return existing;
        }
        components.add(parsed);
        return parsed;
    }

    private AsyncOperation asyncOperation(String operationId, Object raw) {
        String location = "/operations/" + escape(operationId);
        Map<String, Object> operation = map(raw, location);
        String action = optionalString(operation.get("action"), location + "/action");
        String channelKey = keyOf(operation.get("channel"), "#/channels/", location + "/channel");
        List<String> messageKeys = new ArrayList<>();
        Object messages = operation.get("messages");
        if (messages != null) {
            List<Object> named = list(messages, location + "/messages");
            for (int i = 0; i < named.size(); i++) {
                String key = keyOf(named.get(i), "#/channels/" + escape(channelKey == null ? "" : channelKey)
                        + "/messages/", location + "/messages/" + i);
                if (key != null) messageKeys.add(key);
            }
        }
        return new AsyncOperation(operationId, action, channelKey, List.copyOf(messageKeys),
                optionalString(operation.get("summary"), location + "/summary"),
                optionalString(operation.get("description"), location + "/description"), location);
    }

    /** The key a {@code $ref} into this document names, or null when it names something else. */
    private String keyOf(Object raw, String prefix, String location) {
        Map<String, Object> reference = optionalMap(raw, location);
        Object ref = reference == null ? null : reference.get("$ref");
        if (!(ref instanceof String pointer) || !pointer.startsWith(prefix)) {
            if (reference != null) {
                findings.add(new Finding(location, Construct.UNMODELLED_KEYWORD, Treatment.UNDECIDED, "$ref"));
            }
            return null;
        }
        String rest = pointer.substring(prefix.length());
        return rest.contains("/") ? null : unescape(rest);
    }

    // ------------------------------------------------------------ components

    private Component component(String name, Object raw) {
        String location = "/components/schemas/" + escape(name);
        currentComponent = name;
        Schema schema = schema(withoutFragmentPath(raw, location), location);
        currentComponent = null;
        return new Component(name, provenance(raw, location), schema, location);
    }

    private Provenance provenance(Object raw, String location) {
        String fragmentPath = raw instanceof Map<?, ?> m && m.containsKey(FRAGMENT_PATH)
                ? string(m.get(FRAGMENT_PATH), location + "/" + FRAGMENT_PATH)
                : null;
        return new Provenance(fragmentPath, CanonicalJson.sha256(raw));
    }

    private Object withoutFragmentPath(Object raw, String location) {
        if (!(raw instanceof Map<?, ?> m) || !m.containsKey(FRAGMENT_PATH)) return raw;
        Map<String, Object> copy = new LinkedHashMap<>(map(raw, location));
        copy.remove(FRAGMENT_PATH);
        return copy;
    }

    private <T> Reusable<T> reusable(String type, String name, Object raw, Typer<T> typer,
                                     Rereference<T> rereference) {
        String location = "/components/" + type + "/" + escape(name);
        return new Reusable<>(name, provenance(raw, location), resolveComponent(type, name, location, typer,
                rereference));
    }

    /** A reusable component, typed once however often it is referenced. */
    @SuppressWarnings("unchecked")
    private <T> T resolveComponent(String type, String name, String referencedFrom, Typer<T> typer,
                                   Rereference<T> rereference) {
        String key = type + "/" + name;
        if (reusableTyped.containsKey(key)) return (T) reusableTyped.get(key);
        Map<String, Object> raws = reusableRaw.getOrDefault(type, Map.of());
        if (!raws.containsKey(name)) {
            throw error(referencedFrom, "$ref '#/components/" + type + "/" + escape(name) + "' names no component");
        }
        if (!resolving.add(key)) {
            throw error(referencedFrom, "$ref '#/components/" + type + "/" + escape(name)
                    + "' is part of a cycle of references: " + String.join(" -> ", resolving) + " -> " + key);
        }
        String location = "/components/" + type + "/" + escape(name);
        T typed = object(type, withoutFragmentPath(raws.get(name), location), location, typer, rereference);
        resolving.remove(key);
        reusableTyped.put(key, typed);
        return typed;
    }

    /**
     * An object in a position that may hold a reference to a reusable component of
     * the given type: typed directly, or resolved through the reference.
     */
    private <T> T object(String type, Object raw, String location, Typer<T> typer, Rereference<T> rereference) {
        Map<String, Object> object = new LinkedHashMap<>(map(raw, location));
        if (!object.containsKey("$ref")) {
            return typer.type(object, location);
        }
        String ref = string(object.remove("$ref"), location + "/$ref");
        String prefix = "#/components/" + type + "/";
        if (!ref.startsWith(prefix) || ref.substring(prefix.length()).contains("/")) {
            throw error(location, "$ref '" + ref + "' is not a reference to a component under components/" + type);
        }
        String name = unescape(ref.substring(prefix.length()));
        Reference reference = new Reference(
                name,
                optionalString(object.remove("summary"), location + "/summary"),
                optionalString(object.remove("description"), location + "/description"),
                Collections.unmodifiableMap(object));
        return rereference.through(resolveComponent(type, name, location, typer, rereference), reference);
    }

    /** Marks each $ref that closes a cycle of components, where it closes it. */
    private void findRecursion(List<Component> components) {
        Map<String, List<RefSite>> edges = new LinkedHashMap<>();
        for (RefSite site : refSites) {
            edges.computeIfAbsent(site.from(), k -> new ArrayList<>()).add(site);
        }
        Set<String> done = new HashSet<>();
        for (Component component : components) {
            visit(component.name(), edges, new LinkedHashSet<>(), done);
        }
    }

    private void visit(String name, Map<String, List<RefSite>> edges, LinkedHashSet<String> stack,
                       Set<String> done) {
        if (done.contains(name)) return;
        stack.add(name);
        for (RefSite site : edges.getOrDefault(name, List.of())) {
            if (stack.contains(site.to())) {
                List<String> cycle = new ArrayList<>();
                boolean inCycle = false;
                for (String member : stack) {
                    inCycle |= member.equals(site.to());
                    if (inCycle) cycle.add(member);
                }
                cycle.add(site.to());
                findings.add(new Finding(site.location(), Construct.RECURSIVE_REF, Treatment.REPRESENTED,
                        String.join(" -> ", cycle)));
            } else {
                visit(site.to(), edges, stack, done);
            }
        }
        stack.remove(name);
        done.add(name);
    }

    // --------------------------------------------------------------- schemas

    private Schema schema(Object raw, String location) {
        if (raw instanceof Boolean literal) {
            findings.add(new Finding(location, Construct.BOOLEAN_SCHEMA, Treatment.UNDECIDED, literal.toString()));
            return new Schema(literal, null, null, false, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, emptyConstraints(), Map.of(), Map.of());
        }
        if (!(raw instanceof Map)) {
            throw error(location, "must be a mapping or a boolean");
        }
        Map<String, Object> s = map(raw, location);
        SchemaBuilder b = new SchemaBuilder();
        Map<String, Object> constraints = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : s.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String at = location + "/" + escape(key);
            switch (key) {
                case "$ref" -> b.ref = reference(string(value, at), location);
                case "type" -> {
                    if (value instanceof List<?>) {
                        b.types = stringList(value, at);
                        b.typeWrittenAsList = true;
                        if (b.types.size() > 1) {
                            findings.add(new Finding(location, Construct.MULTIPLE_TYPES, Treatment.UNDECIDED,
                                    "type: " + String.join(", ", b.types)));
                        }
                    } else {
                        b.types = List.of(string(value, at));
                    }
                }
                case "format" -> b.format = string(value, at);
                case "description" -> b.description = string(value, at);
                case "const" -> b.constValue = new Const(value);
                case "enum" -> b.enumValues = list(value, at);
                case "required" -> b.required = stringList(value, at);
                case "properties" -> b.properties = schemaMap(value, at);
                case "patternProperties" -> {
                    b.patternProperties = schemaMap(value, at);
                    findings.add(new Finding(location, Construct.PATTERN_PROPERTIES, Treatment.REPRESENTED,
                            "patternProperties: " + String.join(", ", b.patternProperties.keySet())));
                }
                case "items" -> b.items = schema(value, at);
                case "additionalProperties" -> {
                    if (value instanceof Boolean allowed) {
                        b.additionalProperties = new AdditionalProperties(allowed, null);
                    } else {
                        b.additionalProperties = new AdditionalProperties(null, schema(value, at));
                    }
                    findings.add(new Finding(location, Construct.ADDITIONAL_PROPERTIES, Treatment.REPRESENTED,
                            "additionalProperties: " + (value instanceof Boolean ? value : "a schema")));
                }
                case "allOf" -> b.allOf = schemaList(value, at);
                case "oneOf" -> {
                    b.oneOf = schemaList(value, at);
                    composition("oneOf", b.oneOf, location,
                            Construct.ONE_OF_REF_BRANCHES, Construct.ONE_OF_INLINE_BRANCHES);
                }
                case "anyOf" -> {
                    b.anyOf = schemaList(value, at);
                    composition("anyOf", b.anyOf, location,
                            Construct.ANY_OF_REF_BRANCHES, Construct.ANY_OF_INLINE_BRANCHES);
                }
                case "discriminator" -> {
                    Map<String, Object> d = map(value, at);
                    Map<String, Object> other = new LinkedHashMap<>(d);
                    other.remove("propertyName");
                    other.remove("mapping");
                    Map<String, String> mapping = null;
                    if (d.containsKey("mapping")) {
                        mapping = new LinkedHashMap<>();
                        for (Map.Entry<String, Object> m : map(d.get("mapping"), at + "/mapping").entrySet()) {
                            mapping.put(m.getKey(), string(m.getValue(), at + "/mapping/" + escape(m.getKey())));
                        }
                        mapping = Collections.unmodifiableMap(mapping);
                    }
                    b.discriminator = new Discriminator(
                            d.containsKey("propertyName") ? string(d.get("propertyName"), at + "/propertyName") : null,
                            mapping,
                            Collections.unmodifiableMap(other));
                    findings.add(new Finding(location, Construct.DISCRIMINATOR, Treatment.REPRESENTED,
                            "discriminator: " + b.discriminator.propertyName()));
                }
                case "minLength", "maxLength", "minimum", "maximum", "multipleOf", "minItems", "maxItems",
                     "minProperties", "maxProperties", "pattern", "uniqueItems",
                     "exclusiveMinimum", "exclusiveMaximum" -> constraints.put(key, value);
                default -> {
                    if (ANNOTATIONS.contains(key) || key.startsWith("x-")) {
                        b.annotations.put(key, value);
                    } else {
                        b.unmodelled.put(key, value);
                        findings.add(new Finding(location, Construct.UNMODELLED_KEYWORD, Treatment.UNDECIDED, key));
                    }
                }
            }
        }
        return b.build(constraints(constraints, location));
    }

    private void composition(String keyword, List<Schema> branches, String location,
                             Construct allReferences, Construct someInline) {
        List<String> names = new ArrayList<>();
        boolean allRefs = true;
        for (int i = 0; i < branches.size(); i++) {
            String ref = branches.get(i).ref();
            allRefs &= ref != null;
            names.add(ref != null ? ref : "<inline #" + (i + 1) + ">");
        }
        Construct construct = allRefs ? allReferences : someInline;
        findings.add(new Finding(location, construct, construct.treatment(),
                keyword + ": " + String.join(", ", names)));
    }

    private String reference(String ref, String location) {
        if (!ref.startsWith(SCHEMA_REF_PREFIX) || ref.substring(SCHEMA_REF_PREFIX.length()).contains("/")) {
            throw error(location, "$ref '" + ref + "' is not a reference to a schema component; "
                    + "a bundled contract has no other kind");
        }
        String name = unescape(ref.substring(SCHEMA_REF_PREFIX.length()));
        if (!componentNames.contains(name)) {
            throw error(location, "$ref '" + ref + "' names no component");
        }
        if (currentComponent != null) {
            refSites.add(new RefSite(currentComponent, name, location));
        }
        return name;
    }

    private Constraints constraints(Map<String, Object> c, String location) {
        return new Constraints(
                number(c.get("minLength"), location + "/minLength"),
                number(c.get("maxLength"), location + "/maxLength"),
                c.containsKey("pattern") ? string(c.get("pattern"), location + "/pattern") : null,
                number(c.get("minimum"), location + "/minimum"),
                number(c.get("maximum"), location + "/maximum"),
                c.get("exclusiveMinimum"),
                c.get("exclusiveMaximum"),
                number(c.get("multipleOf"), location + "/multipleOf"),
                number(c.get("minItems"), location + "/minItems"),
                number(c.get("maxItems"), location + "/maxItems"),
                bool(c.get("uniqueItems"), location + "/uniqueItems"),
                number(c.get("minProperties"), location + "/minProperties"),
                number(c.get("maxProperties"), location + "/maxProperties"));
    }

    private static Constraints emptyConstraints() {
        return new Constraints(null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private Map<String, Schema> schemaMap(Object value, String location) {
        Map<String, Schema> out = new LinkedHashMap<>();
        map(value, location).forEach((name, raw) -> out.put(name, schema(raw, location + "/" + escape(name))));
        return Collections.unmodifiableMap(out);
    }

    private List<Schema> schemaList(Object value, String location) {
        List<Schema> out = new ArrayList<>();
        List<Object> raw = list(value, location);
        for (int i = 0; i < raw.size(); i++) {
            out.add(schema(raw.get(i), location + "/" + i));
        }
        return List.copyOf(out);
    }

    private static final class SchemaBuilder {
        String ref;
        List<String> types;
        boolean typeWrittenAsList;
        String format;
        String description;
        Const constValue;
        List<Object> enumValues;
        List<String> required;
        Map<String, Schema> properties;
        Map<String, Schema> patternProperties;
        Schema items;
        AdditionalProperties additionalProperties;
        List<Schema> allOf;
        List<Schema> oneOf;
        List<Schema> anyOf;
        Discriminator discriminator;
        final Map<String, Object> annotations = new LinkedHashMap<>();
        final Map<String, Object> unmodelled = new LinkedHashMap<>();

        Schema build(Constraints constraints) {
            return new Schema(null, ref, types, typeWrittenAsList, format, description, constValue, enumValues,
                    required, properties, patternProperties, items, additionalProperties, allOf, oneOf, anyOf,
                    discriminator, constraints, Collections.unmodifiableMap(annotations),
                    Collections.unmodifiableMap(unmodelled));
        }
    }

    // ----------------------------------------------------------------- paths

    private PathItem pathItem(String path, Object raw) {
        String location = "/paths/" + escape(path);
        List<Parameter> parameters = null;
        List<Operation> operations = new ArrayList<>();
        Map<String, Object> other = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map(raw, location).entrySet()) {
            String key = entry.getKey();
            if (key.equals("parameters")) {
                parameters = parameters(entry.getValue(), location + "/parameters");
                continue;
            }
            HttpMethod.fromKey(key).ifPresentOrElse(
                    method -> operations.add(operation(path, method, entry.getValue(), location + "/" + key)),
                    () -> other.put(key, entry.getValue()));
        }
        return new PathItem(path, parameters, List.copyOf(operations), Collections.unmodifiableMap(other));
    }

    private Operation operation(String path, HttpMethod method, Object raw, String location) {
        Map<String, Object> other = new LinkedHashMap<>(map(raw, location));
        String operationId = optionalString(other.remove("operationId"), location + "/operationId");
        List<String> tags = other.containsKey("tags") ? stringList(other.remove("tags"), location + "/tags") : null;
        String summary = optionalString(other.remove("summary"), location + "/summary");
        String description = optionalString(other.remove("description"), location + "/description");
        List<Parameter> parameters = other.containsKey("parameters")
                ? parameters(other.remove("parameters"), location + "/parameters") : null;
        RequestBody requestBody = other.containsKey("requestBody")
                ? object(REQUEST_BODIES, other.remove("requestBody"), location + "/requestBody", this::requestBody,
                        ContractParser::rereference)
                : null;
        List<Response> responses = null;
        if (other.containsKey("responses")) {
            responses = new ArrayList<>();
            for (Map.Entry<String, Object> r : map(other.remove("responses"), location + "/responses").entrySet()) {
                responses.add(object(RESPONSES, r.getValue(), location + "/responses/" + escape(r.getKey()),
                        responseTyper(r.getKey()), responseRereference(r.getKey())));
            }
            responses = List.copyOf(responses);
        }
        return new Operation(path, method, operationId, tags, summary, description, parameters, requestBody,
                responses, Collections.unmodifiableMap(other));
    }

    private List<Parameter> parameters(Object value, String location) {
        List<Parameter> out = new ArrayList<>();
        List<Object> raw = list(value, location);
        for (int i = 0; i < raw.size(); i++) {
            out.add(object(PARAMETERS, raw.get(i), location + "/" + i, this::parameter,
                    ContractParser::rereference));
        }
        return List.copyOf(out);
    }

    private Parameter parameter(Map<String, Object> other, String at) {
        return new Parameter(
                null,
                optionalString(other.remove("name"), at + "/name"),
                optionalString(other.remove("in"), at + "/in"),
                bool(other.remove("required"), at + "/required"),
                optionalString(other.remove("description"), at + "/description"),
                other.containsKey("schema") ? schema(other.remove("schema"), at + "/schema") : null,
                Collections.unmodifiableMap(other));
    }

    private static Parameter rereference(Parameter p, Reference reference) {
        return new Parameter(reference, p.name(), p.in(), p.required(), p.description(), p.schema(), p.other());
    }

    private RequestBody requestBody(Map<String, Object> other, String location) {
        return new RequestBody(
                null,
                optionalString(other.remove("description"), location + "/description"),
                bool(other.remove("required"), location + "/required"),
                other.containsKey("content") ? content(other.remove("content"), location + "/content") : null,
                Collections.unmodifiableMap(other));
    }

    private static RequestBody rereference(RequestBody b, Reference reference) {
        return new RequestBody(reference, b.description(), b.required(), b.content(), b.other());
    }

    private Typer<Response> responseTyper(String status) {
        return (other, location) -> new Response(
                status,
                null,
                optionalString(other.remove("description"), location + "/description"),
                other.containsKey("content") ? content(other.remove("content"), location + "/content") : null,
                Collections.unmodifiableMap(other));
    }

    private static Rereference<Response> responseRereference(String status) {
        return (r, reference) -> new Response(status, reference, r.description(), r.content(), r.other());
    }

    private List<MediaType> content(Object value, String location) {
        List<MediaType> out = new ArrayList<>();
        for (Map.Entry<String, Object> entry : map(value, location).entrySet()) {
            String at = location + "/" + escape(entry.getKey());
            Map<String, Object> other = new LinkedHashMap<>(map(entry.getValue(), at));
            Schema schema = other.containsKey("schema") ? schema(other.remove("schema"), at + "/schema") : null;
            out.add(new MediaType(entry.getKey(), schema, Collections.unmodifiableMap(other)));
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------- plumbing

    private ContractModelException error(String location, String problem) {
        return new ContractModelException(
                source + ": " + (location.isEmpty() ? "/" : location) + (problem.startsWith("$") ? ": " : " ")
                        + problem, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value, String location) {
        if (!(value instanceof Map<?, ?> m)) throw error(location, "must be a mapping");
        for (Object key : m.keySet()) {
            if (!(key instanceof String)) {
                throw error(location, "must have only string keys, not " + key);
            }
        }
        return (Map<String, Object>) m;
    }

    private Map<String, Object> optionalMap(Object value, String location) {
        return value == null ? null : map(value, location);
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Object value, String location) {
        if (!(value instanceof List<?>)) throw error(location, "must be a list");
        return Collections.unmodifiableList(new ArrayList<>((List<Object>) value));
    }

    private List<String> stringList(Object value, String location) {
        List<Object> raw = list(value, location);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            out.add(string(raw.get(i), location + "/" + i));
        }
        return List.copyOf(out);
    }

    private String string(Object value, String location) {
        if (!(value instanceof String s)) throw error(location, "must be a string");
        return s;
    }

    private String optionalString(Object value, String location) {
        return value == null ? null : string(value, location);
    }

    private Number number(Object value, String location) {
        if (value == null) return null;
        if (!(value instanceof Number n)) throw error(location, "must be a number");
        return n;
    }

    private Boolean bool(Object value, String location) {
        if (value == null) return null;
        if (!(value instanceof Boolean b)) throw error(location, "must be a boolean");
        return b;
    }

    private static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    private static String unescape(String token) {
        return token.replace("~1", "/").replace("~0", "~");
    }
}
