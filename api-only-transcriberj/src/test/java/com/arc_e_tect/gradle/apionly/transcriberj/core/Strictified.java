package com.arc_e_tect.gradle.apionly.transcriberj.core;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A contract document made strict for the oracle (T13.1): {@code unevaluatedProperties: false}
 * on every object-level schema that declares neither {@code additionalProperties} nor
 * {@code patternProperties}, itself or through its {@code allOf}.
 *
 * <p>A branch of an {@code allOf}, {@code oneOf} or {@code anyOf} is left open at its top
 * level, and a component it refers to is replaced by a copy open at its top level, named
 * {@code <component>__branch}: {@code unevaluatedProperties} in a branch would see only that
 * branch's properties, and forbid those the others declare. So the object as a whole decides,
 * which is what proves the strict rule is judged against the merged {@code allOf}.
 */
final class Strictified {

    private static final String SUFFIX = "__branch";

    private final Map<String, JsonNode> components = new LinkedHashMap<>();

    private Strictified(JsonNode document) {
        JsonNode schemas = document.path("components").path("schemas");
        if (schemas.isObject()) schemas.properties().forEach(e -> components.put(e.getKey(), e.getValue()));
    }

    /** A strict copy of a document the oracle has already rewritten. */
    static ObjectNode of(JsonNode document) {
        ObjectNode copy = (ObjectNode) document.deepCopy();
        Strictified s = new Strictified(document);
        JsonNode schemas = copy.path("components").path("schemas");
        if (schemas instanceof ObjectNode o) {
            for (Map.Entry<String, JsonNode> c : s.components.entrySet()) {
                o.set(c.getKey() + SUFFIX, s.strictify(c.getValue().deepCopy(), true));
                o.set(c.getKey(), s.strictify(c.getValue().deepCopy(), false));
            }
        }
        for (JsonNode item : copy.path("paths")) {
            for (JsonNode operation : item) {
                s.media(operation.path("requestBody"));
            }
        }
        for (JsonNode body : copy.path("components").path("requestBodies")) s.media(body);
        return copy;
    }

    private void media(JsonNode body) {
        for (JsonNode media : body.path("content")) {
            if (media instanceof ObjectNode m && m.has("schema")) m.set("schema", strictify(m.get("schema"), false));
        }
    }

    private JsonNode strictify(JsonNode schema, boolean open) {
        if (!(schema instanceof ObjectNode o)) return schema;
        for (String key : List.of("properties", "patternProperties")) {
            if (o.get(key) instanceof ObjectNode children) {
                children.properties().forEach(e -> children.set(e.getKey(), strictify(e.getValue(), false)));
            }
        }
        if (o.get("additionalProperties") instanceof ObjectNode) {
            o.set("additionalProperties", strictify(o.get("additionalProperties"), false));
        }
        if (o.has("items")) o.set("items", strictify(o.get("items"), false));
        for (String key : List.of("allOf", "oneOf", "anyOf")) {
            if (!(o.get(key) instanceof ArrayNode branches)) continue;
            for (int i = 0; i < branches.size(); i++) {
                JsonNode branch = branches.get(i);
                if (branch.has("$ref") && branch.size() == 1) {
                    ((ObjectNode) branch).put("$ref", branch.get("$ref").stringValue() + SUFFIX);
                } else {
                    branches.set(i, strictify(branch, true));
                }
            }
        }
        boolean onlyRef = o.has("$ref") && o.size() == 1;
        if (!open && !onlyRef && objectLike(o, new HashSet<>()) && !declaresOpenness(o, new HashSet<>())) {
            o.put("unevaluatedProperties", false);
        }
        return o;
    }

    private JsonNode component(JsonNode ref) {
        String name = ref.stringValue().substring("#/components/schemas/".length());
        if (name.endsWith(SUFFIX)) name = name.substring(0, name.length() - SUFFIX.length());
        return components.get(name.replace("~1", "/").replace("~0", "~"));
    }

    private boolean objectLike(JsonNode s, Set<JsonNode> seen) {
        if (!seen.add(s)) return false;
        JsonNode type = s.path("type");
        if (type.isString() && type.stringValue().equals("object") || s.has("properties")) return true;
        if (s.has("$ref") && s.get("$ref").stringValue().startsWith("#/components/schemas/")
                && objectLike(component(s.get("$ref")), seen)) {
            return true;
        }
        for (JsonNode branch : s.path("allOf")) {
            if (objectLike(branch, seen)) return true;
        }
        return false;
    }

    private boolean declaresOpenness(JsonNode s, Set<JsonNode> seen) {
        if (!seen.add(s)) return false;
        if (s.has("additionalProperties") || s.has("patternProperties")) return true;
        if (s.has("$ref") && s.get("$ref").stringValue().startsWith("#/components/schemas/")
                && declaresOpenness(component(s.get("$ref")), seen)) {
            return true;
        }
        for (JsonNode branch : s.path("allOf")) {
            if (declaresOpenness(branch, seen)) return true;
        }
        return false;
    }
}
