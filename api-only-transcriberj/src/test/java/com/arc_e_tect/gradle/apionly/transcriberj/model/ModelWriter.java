package com.arc_e_tect.gradle.apionly.transcriberj.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a model back into the plain maps and lists it was parsed from.
 *
 * Test-only. If the rendering of every component and path equals the parsed
 * document, the model has kept everything: nothing was dropped on the way in.
 */
final class ModelWriter {

    private ModelWriter() {
    }

    static Map<String, Object> schemas(ContractModel model) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Component component : model.components()) {
            Map<String, Object> rendered = new LinkedHashMap<>();
            if (component.provenance().fragmentPath() != null) {
                rendered.put("x-fragment-path", component.provenance().fragmentPath());
            }
            rendered.putAll(schema(component.schema()));
            out.put(component.name(), rendered);
        }
        return out;
    }

    static Map<String, Object> otherComponents(ContractModel model) {
        return new LinkedHashMap<>(model.otherComponents());
    }

    static Map<String, Object> paths(ContractModel model) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (PathItem item : model.paths()) {
            Map<String, Object> rendered = new LinkedHashMap<>();
            if (item.parameters() != null) rendered.put("parameters", parameters(item.parameters()));
            for (Operation operation : item.operations()) {
                rendered.put(operation.method().key(), operation(operation));
            }
            rendered.putAll(item.other());
            out.put(item.path(), rendered);
        }
        return out;
    }

    private static Map<String, Object> operation(Operation operation) {
        Map<String, Object> out = new LinkedHashMap<>();
        putIfPresent(out, "operationId", operation.operationId());
        putIfPresent(out, "tags", operation.tags());
        putIfPresent(out, "summary", operation.summary());
        putIfPresent(out, "description", operation.description());
        if (operation.parameters() != null) out.put("parameters", parameters(operation.parameters()));
        if (operation.requestBody() != null) {
            RequestBody body = operation.requestBody();
            Map<String, Object> rendered = new LinkedHashMap<>();
            putIfPresent(rendered, "description", body.description());
            putIfPresent(rendered, "required", body.required());
            if (body.content() != null) rendered.put("content", content(body.content()));
            rendered.putAll(body.other());
            out.put("requestBody", rendered);
        }
        if (operation.responses() != null) {
            Map<String, Object> responses = new LinkedHashMap<>();
            for (Response response : operation.responses()) {
                Map<String, Object> rendered = new LinkedHashMap<>();
                putIfPresent(rendered, "description", response.description());
                if (response.content() != null) rendered.put("content", content(response.content()));
                rendered.putAll(response.other());
                responses.put(response.status(), rendered);
            }
            out.put("responses", responses);
        }
        out.putAll(operation.other());
        return out;
    }

    private static List<Object> parameters(List<Parameter> parameters) {
        List<Object> out = new ArrayList<>();
        for (Parameter parameter : parameters) {
            Map<String, Object> rendered = new LinkedHashMap<>();
            putIfPresent(rendered, "name", parameter.name());
            putIfPresent(rendered, "in", parameter.in());
            putIfPresent(rendered, "required", parameter.required());
            putIfPresent(rendered, "description", parameter.description());
            if (parameter.schema() != null) rendered.put("schema", render(parameter.schema()));
            rendered.putAll(parameter.other());
            out.add(rendered);
        }
        return out;
    }

    private static Map<String, Object> content(List<MediaType> content) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (MediaType mediaType : content) {
            Map<String, Object> rendered = new LinkedHashMap<>();
            if (mediaType.schema() != null) rendered.put("schema", render(mediaType.schema()));
            rendered.putAll(mediaType.other());
            out.put(mediaType.contentType(), rendered);
        }
        return out;
    }

    /** A schema as it was written: a mapping, or the literal true or false. */
    static Object render(Schema schema) {
        return schema.literal() != null ? schema.literal() : schema(schema);
    }

    private static Map<String, Object> schema(Schema schema) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (schema.ref() != null) out.put("$ref", "#/components/schemas/" + schema.ref());
        if (schema.types() != null) {
            out.put("type", schema.typeWrittenAsList() ? schema.types() : schema.types().get(0));
        }
        putIfPresent(out, "format", schema.format());
        putIfPresent(out, "description", schema.description());
        if (schema.constValue() != null) out.put("const", schema.constValue().value());
        putIfPresent(out, "enum", schema.enumValues());
        putIfPresent(out, "required", schema.required());
        if (schema.properties() != null) out.put("properties", schemaMap(schema.properties()));
        if (schema.patternProperties() != null) out.put("patternProperties", schemaMap(schema.patternProperties()));
        if (schema.items() != null) out.put("items", render(schema.items()));
        if (schema.additionalProperties() != null) {
            AdditionalProperties additional = schema.additionalProperties();
            out.put("additionalProperties",
                    additional.schema() != null ? render(additional.schema()) : additional.allowed());
        }
        if (schema.allOf() != null) out.put("allOf", schemaList(schema.allOf()));
        if (schema.oneOf() != null) out.put("oneOf", schemaList(schema.oneOf()));
        if (schema.anyOf() != null) out.put("anyOf", schemaList(schema.anyOf()));
        if (schema.discriminator() != null) {
            Discriminator discriminator = schema.discriminator();
            Map<String, Object> rendered = new LinkedHashMap<>();
            putIfPresent(rendered, "propertyName", discriminator.propertyName());
            putIfPresent(rendered, "mapping", discriminator.mapping());
            rendered.putAll(discriminator.other());
            out.put("discriminator", rendered);
        }
        Constraints c = schema.constraints();
        putIfPresent(out, "minLength", c.minLength());
        putIfPresent(out, "maxLength", c.maxLength());
        putIfPresent(out, "pattern", c.pattern());
        putIfPresent(out, "minimum", c.minimum());
        putIfPresent(out, "maximum", c.maximum());
        putIfPresent(out, "exclusiveMinimum", c.exclusiveMinimum());
        putIfPresent(out, "exclusiveMaximum", c.exclusiveMaximum());
        putIfPresent(out, "multipleOf", c.multipleOf());
        putIfPresent(out, "minItems", c.minItems());
        putIfPresent(out, "maxItems", c.maxItems());
        putIfPresent(out, "uniqueItems", c.uniqueItems());
        putIfPresent(out, "minProperties", c.minProperties());
        putIfPresent(out, "maxProperties", c.maxProperties());
        out.putAll(schema.annotations());
        out.putAll(schema.unmodelled());
        return out;
    }

    private static Map<String, Object> schemaMap(Map<String, Schema> schemas) {
        Map<String, Object> out = new LinkedHashMap<>();
        schemas.forEach((name, s) -> out.put(name, render(s)));
        return out;
    }

    private static List<Object> schemaList(List<Schema> schemas) {
        List<Object> out = new ArrayList<>();
        schemas.forEach(s -> out.add(render(s)));
        return out;
    }

    private static void putIfPresent(Map<String, Object> out, String key, Object value) {
        if (value != null) out.put(key, value);
    }
}
