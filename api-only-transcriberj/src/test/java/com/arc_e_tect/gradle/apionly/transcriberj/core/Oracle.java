package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.regex.JoniRegularExpressionFactory;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.schema.CoreSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The independent oracle (T12.1): a JSON Schema 2020-12 validator that shares no code
 * with the generator, run over the contract document itself.
 *
 * <p>Formats are asserted, and patterns are evaluated as ECMA-262 by joni. The document is
 * read with the same YAML 1.2 reader the plugin reads contracts with, and changed in two
 * ways only: where a schema has both {@code pattern} and {@code format}, the format is
 * dropped, since the pattern wins (S8); and an OpenAPI 3.0 boolean
 * {@code exclusiveMinimum}/{@code exclusiveMaximum} becomes the numeric form 2020-12 knows.
 */
final class Oracle {

    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String BASE = "https://transcriberj.test/contract.json";

    final JsonNode document;
    private final SchemaRegistry registry;
    private final Map<String, Schema> schemas = new HashMap<>();

    Oracle(Path contract) {
        try {
            Object parsed = new Load(LoadSettings.builder().setSchema(new CoreSchema()).build())
                    .loadFromString(Files.readString(contract, StandardCharsets.UTF_8));
            ObjectNode root = (ObjectNode) node(parsed);
            normalise(root);
            root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
            root.put("$id", BASE);
            this.document = root;
            String text = JSON.writeValueAsString(root);
            SchemaRegistryConfig config = new SchemaRegistryConfig.Builder()
                    .formatAssertionsEnabled(true)
                    .regularExpressionFactory(JoniRegularExpressionFactory.getInstance())
                    .build();
            this.registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> builder
                    .schemaRegistryConfig(config)
                    .schemas(Map.of(BASE, text)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The errors validating an instance against the schema at a JSON pointer into the document. */
    List<String> errors(String pointer, JsonNode instance) {
        Schema schema = schemas.computeIfAbsent(pointer, p -> registry.getSchema(
                "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"$ref\":\"" + BASE + "#"
                        + encode(p) + "\"}"));
        List<String> out = new ArrayList<>();
        for (Error e : schema.validate(instance)) {
            out.add(e.getKeyword() + " at " + e.getInstanceLocation() + ": " + e.getMessage());
        }
        return out;
    }

    /** Whether an instance is valid against the schema at a pointer. */
    boolean valid(String pointer, JsonNode instance) {
        return errors(pointer, instance).isEmpty();
    }

    /** The node at a pointer into the document, following a {@code $ref} it holds. */
    JsonNode resolve(String pointer) {
        JsonNode node = document.at(pointer);
        while (node.has("$ref") && node.get("$ref").stringValue().startsWith("#")) {
            node = document.at(node.get("$ref").stringValue().substring(1));
        }
        return node;
    }

    /** A pointer's segments percent-encoded where a URI fragment needs it; '/' and '~' kept. */
    private static String encode(String pointer) {
        StringBuilder out = new StringBuilder();
        for (String segment : pointer.split("/", -1)) {
            if (!out.isEmpty() || !segment.isEmpty()) out.append('/');
            out.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20").replace("%7E", "~"));
        }
        return out.toString();
    }

    /** A value as the YAML reader gives it, as a node, every number exact. */
    static JsonNode node(Object value) {
        if (value == null) return NODES.nullNode();
        if (value instanceof String s) return NODES.stringNode(s);
        if (value instanceof Boolean b) return NODES.booleanNode(b);
        if (value instanceof Integer || value instanceof Long) return NODES.numberNode(((Number) value).longValue());
        if (value instanceof BigInteger i) return NODES.numberNode(i);
        if (value instanceof Number n) return NODES.numberNode(new BigDecimal(n.toString()));
        if (value instanceof List<?> list) {
            ArrayNode out = NODES.arrayNode();
            list.forEach(v -> out.add(node(v)));
            return out;
        }
        ObjectNode out = NODES.objectNode();
        ((Map<?, ?>) value).forEach((k, v) -> out.set(String.valueOf(k), node(v)));
        return out;
    }

    /** Drops a format beside a pattern, and rewrites the 3.0 boolean exclusive bounds, everywhere. */
    private static void normalise(JsonNode node) {
        if (node instanceof ObjectNode o) {
            if (o.has("pattern") && o.get("pattern").isString() && o.has("format")) o.remove("format");
            exclusive(o, "exclusiveMinimum", "minimum");
            exclusive(o, "exclusiveMaximum", "maximum");
            o.values().forEach(Oracle::normalise);
        } else if (node instanceof ArrayNode a) {
            a.values().forEach(Oracle::normalise);
        }
    }

    private static void exclusive(ObjectNode o, String exclusive, String inclusive) {
        if (!o.has(exclusive) || !o.get(exclusive).isBoolean()) return;
        boolean on = o.get(exclusive).booleanValue();
        o.remove(exclusive);
        if (on && o.has(inclusive)) o.set(exclusive, o.remove(inclusive));
    }
}
