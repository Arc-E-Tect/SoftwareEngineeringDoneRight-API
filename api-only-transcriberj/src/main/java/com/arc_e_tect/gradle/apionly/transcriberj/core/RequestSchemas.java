package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.model.CanonicalJson;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Operation;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.schema.CoreSchema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The schemas a request's body and parameters must satisfy, each as a self-contained JSON Schema
 * 2020-12 document: what an emitter needs to recognise any valid request, not only the ones the
 * core generates -- a stub that answers every valid request, for one.
 *
 * <p>Each is exactly the schema the core's own notion of validity uses. Every component it refers
 * to is bundled into {@code $defs}. A {@code format} beside a {@code pattern} is dropped, as the
 * pattern decides; a format {@code validateFormats} does not name is kept as the annotation
 * {@code x-format}, so that a validator asserting formats does not assert it; and an OpenAPI 3.0
 * boolean {@code exclusiveMinimum} or {@code exclusiveMaximum} becomes the numeric form; and a
 * {@code discriminator}'s {@code mapping} names the bundled components. With
 * {@code strictRequests} on, every object-level schema that declares neither
 * {@code additionalProperties} nor {@code patternProperties}, itself or through its {@code allOf},
 * gets {@code unevaluatedProperties: false}; a branch of an {@code allOf}, {@code oneOf} or
 * {@code anyOf} stays open at its top level, so that the object as a whole decides.
 */
final class RequestSchemas {

    static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";
    private static final String COMPONENTS = "#/components/schemas/";
    private static final String DEFS = "#/$defs/";
    private static final String BRANCH = "__branch";

    private final Map<String, Object> document;
    private final Settings settings;
    private final Map<String, Object> components;

    /**
     * The schemas of one contract document.
     *
     * @param contract the fetched OpenAPI document
     * @param settings the settings, for {@code strictRequests} and {@code validateFormats}
     */
    @SuppressWarnings("unchecked")
    RequestSchemas(Path contract, Settings settings) {
        try {
            this.document = (Map<String, Object>) new Load(LoadSettings.builder().setSchema(new CoreSchema()).build())
                    .loadFromString(Files.readString(contract, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.settings = settings;
        this.components = map(map(document.get("components")).get("schemas"));
    }

    /**
     * The schema of an operation's request body, in one media type.
     *
     * @param operation the operation
     * @param mediaType the media type, as the operation declares it
     * @return the schema's JSON text, or empty when there is none
     */
    Optional<String> body(Operation operation, String mediaType) {
        Map<String, Object> raw = operation(operation);
        Map<String, Object> body = resolve(map(raw.get("requestBody")), "#/components/requestBodies/");
        Object schema = map(map(body.get("content")).get(mediaType)).get("schema");
        return schema == null ? Optional.empty() : Optional.of(document(schema));
    }

    /**
     * The schema of one of an operation's parameters.
     *
     * @param operation the operation
     * @param in        {@code path}, {@code query} or {@code header}
     * @param name      the parameter's name
     * @return the schema's JSON text, or empty when there is none
     */
    Optional<String> parameter(Operation operation, String in, String name) {
        Map<String, Object> item = map(map(document.get("paths")).get(operation.path()));
        List<Object> parameters = new ArrayList<>(list(item.get("parameters")));
        parameters.addAll(list(operation(operation).get("parameters")));
        Object found = null;
        for (Object p : parameters) {
            Map<String, Object> parameter = resolve(map(p), "#/components/parameters/");
            boolean sameHeader = "header".equals(in) && name.equalsIgnoreCase(String.valueOf(parameter.get("name")));
            if (in.equals(parameter.get("in")) && (name.equals(parameter.get("name")) || sameHeader)) {
                found = parameter.get("schema");
            }
        }
        return found == null ? Optional.empty() : Optional.of(document(found));
    }

    private Map<String, Object> operation(Operation operation) {
        Map<String, Object> item = map(map(document.get("paths")).get(operation.path()));
        return map(item.get(operation.method().name().toLowerCase(Locale.ROOT)));
    }

    /** A self-contained document: the schema, with every component it reaches in {@code $defs}. */
    private String document(Object schema) {
        Set<String> reached = new java.util.TreeSet<>();
        Deque<Object> pending = new ArrayDeque<>(List.of(schema));
        while (!pending.isEmpty()) {
            Object next = pending.pop();
            for (String ref : refs(next)) {
                if (reached.add(ref)) pending.push(components.get(ref));
            }
        }
        Map<String, Object> defs = new TreeMap<>();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("$schema", DIALECT);
        Object top = rewrite(copy(schema));
        boolean strict = settings.strictRequests();
        for (String ref : reached) {
            Object component = rewrite(copy(components.get(ref)));
            defs.put(ref, strict ? strictify(copy(component), false) : component);
            if (strict) defs.put(ref + BRANCH, strictify(copy(component), true));
        }
        if (strict) top = strictify(top, false);
        if (top instanceof Map<?, ?> m) {
            m.forEach((k, v) -> root.put((String) k, v));
        } else {
            root.put("allOf", List.of(top));
        }
        if (!defs.isEmpty()) root.put("$defs", defs);
        return CanonicalJson.write(root);
    }

    /** Every component a schema refers to directly. */
    private static List<String> refs(Object schema) {
        List<String> out = new ArrayList<>();
        walk(schema, value -> {
            if (value instanceof Map<?, ?> m && m.get("$ref") instanceof String ref && ref.startsWith(COMPONENTS)) {
                out.add(unescape(ref.substring(COMPONENTS.length())));
            }
            if (value instanceof Map<?, ?> m && m.get("discriminator") instanceof Map<?, ?> d) {
                map(d.get("mapping")).values().forEach(target -> {
                    if (target instanceof String ref && ref.startsWith(COMPONENTS)) {
                        out.add(unescape(ref.substring(COMPONENTS.length())));
                    }
                });
            }
        });
        return out;
    }

    private static void walk(Object value, java.util.function.Consumer<Object> visit) {
        visit.accept(value);
        if (value instanceof Map<?, ?> m) m.values().forEach(v -> walk(v, visit));
        if (value instanceof List<?> l) l.forEach(v -> walk(v, visit));
    }

    /** References rewritten to {@code $defs}, formats and 3.0 bounds made what 2020-12 means by them. */
    @SuppressWarnings("unchecked")
    private Object rewrite(Object value) {
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(v -> out.add(rewrite(v)));
            return out;
        }
        if (!(value instanceof Map<?, ?>)) return value;
        Map<String, Object> o = (Map<String, Object>) value;
        if (o.get("$ref") instanceof String ref && ref.startsWith(COMPONENTS)) {
            o.put("$ref", DEFS + escape(unescape(ref.substring(COMPONENTS.length()))));
        }
        if (o.get("discriminator") instanceof Map<?, ?> d && d.get("mapping") instanceof Map<?, ?> mapping) {
            ((Map<String, Object>) mapping).replaceAll((name, target) -> target instanceof String ref
                    && ref.startsWith(COMPONENTS) ? DEFS + escape(unescape(ref.substring(COMPONENTS.length()))) : target);
        }
        if (o.get("pattern") instanceof String && o.containsKey("format")) o.remove("format");
        if (o.get("format") instanceof String format && !settings.validateFormats().contains(format)) {
            o.put("x-format", o.remove("format"));
        }
        exclusive(o, "exclusiveMinimum", "minimum");
        exclusive(o, "exclusiveMaximum", "maximum");
        for (String key : List.copyOf(o.keySet())) {
            if (NAMED_SCHEMAS.contains(key) && o.get(key) instanceof Map<?, ?> named) {
                ((Map<String, Object>) named).replaceAll((name, schema) -> rewrite(schema));
            } else if (!LITERALS.contains(key)) {
                o.put(key, rewrite(o.get(key)));
            }
        }
        return o;
    }

    /** The keywords whose value is a map of schemas by name: a name is never a keyword, even {@code const}. */
    private static final Set<String> NAMED_SCHEMAS = Set.of("properties", "patternProperties", "dependentSchemas",
            "$defs");

    /** The keywords whose value is an instance, not a schema: nothing in it is rewritten. */
    private static final Set<String> LITERALS = Set.of("enum", "const", "examples", "example", "default");

    private static void exclusive(Map<String, Object> o, String exclusive, String inclusive) {
        if (!(o.get(exclusive) instanceof Boolean on)) return;
        o.remove(exclusive);
        if (on && o.containsKey(inclusive)) o.put(exclusive, o.remove(inclusive));
    }

    /** A schema made strict, as the core's notion of validity is when {@code strictRequests} is on. */
    @SuppressWarnings("unchecked")
    private Object strictify(Object schema, boolean open) {
        if (!(schema instanceof Map<?, ?>)) return schema;
        Map<String, Object> o = (Map<String, Object>) schema;
        for (String key : List.of("properties", "patternProperties")) {
            if (o.get(key) instanceof Map<?, ?> children) {
                Map<String, Object> c = (Map<String, Object>) children;
                c.replaceAll((k, v) -> strictify(v, false));
            }
        }
        if (o.get("additionalProperties") instanceof Map<?, ?>) {
            o.put("additionalProperties", strictify(o.get("additionalProperties"), false));
        }
        if (o.containsKey("items")) o.put("items", strictify(o.get("items"), false));
        for (String key : List.of("allOf", "oneOf", "anyOf")) {
            if (!(o.get(key) instanceof List<?> branches)) continue;
            List<Object> out = new ArrayList<>();
            for (Object branch : branches) {
                if (branch instanceof Map<?, ?> b && b.size() == 1 && b.get("$ref") instanceof String ref
                        && ref.startsWith(DEFS)) {
                    Map<String, Object> redirected = new LinkedHashMap<>();
                    redirected.put("$ref", ref + BRANCH);
                    out.add(redirected);
                } else {
                    out.add(strictify(branch, true));
                }
            }
            o.put(key, out);
        }
        boolean onlyRef = o.containsKey("$ref") && o.size() == 1;
        if (!open && !onlyRef && objectLike(o, new HashSet<>()) && !declaresOpenness(o, new HashSet<>())) {
            o.put("unevaluatedProperties", false);
        }
        return o;
    }

    private Object component(Object ref) {
        String name = ((String) ref).substring(DEFS.length());
        if (name.endsWith(BRANCH)) name = name.substring(0, name.length() - BRANCH.length());
        return components.get(unescape(name));
    }

    private boolean objectLike(Object schema, Set<Object> seen) {
        if (!(schema instanceof Map<?, ?> s) || !seen.add(System.identityHashCode(schema))) return false;
        if ("object".equals(s.get("type")) || s.containsKey("properties")) return true;
        Object ref = s.get("$ref");
        if (ref instanceof String r && (r.startsWith(DEFS) || r.startsWith(COMPONENTS))
                && objectLike(r.startsWith(DEFS) ? component(r) : components.get(unescape(r.substring(COMPONENTS.length()))), seen)) {
            return true;
        }
        for (Object branch : list(s.get("allOf"))) {
            if (objectLike(branch, seen)) return true;
        }
        return false;
    }

    private boolean declaresOpenness(Object schema, Set<Object> seen) {
        if (!(schema instanceof Map<?, ?> s) || !seen.add(System.identityHashCode(schema))) return false;
        if (s.containsKey("additionalProperties") || s.containsKey("patternProperties")) return true;
        Object ref = s.get("$ref");
        if (ref instanceof String r && (r.startsWith(DEFS) || r.startsWith(COMPONENTS))
                && declaresOpenness(r.startsWith(DEFS) ? component(r) : components.get(unescape(r.substring(COMPONENTS.length()))), seen)) {
            return true;
        }
        for (Object branch : list(s.get("allOf"))) {
            if (declaresOpenness(branch, seen)) return true;
        }
        return false;
    }

    private Map<String, Object> resolve(Map<String, Object> value, String prefix) {
        Map<String, Object> current = value;
        for (int i = 0; i < 10 && current.get("$ref") instanceof String ref && ref.startsWith(prefix); i++) {
            String kind = prefix.substring("#/components/".length(), prefix.length() - 1);
            current = map(map(map(document.get("components")).get(kind)).get(unescape(ref.substring(prefix.length()))));
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static Object copy(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) m).forEach((k, v) -> out.put(k, copy(v)));
            return out;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(v -> out.add(copy(v)));
            return out;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }

    private static String unescape(String segment) {
        return segment.replace("~1", "/").replace("~0", "~");
    }

    private static String escape(String name) {
        return name.replace("~", "~0").replace("/", "~1");
    }
}
