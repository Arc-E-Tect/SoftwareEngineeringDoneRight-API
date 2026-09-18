package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.model.AdditionalProperties;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Component;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Constraints;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Construct;
import com.arc_e_tect.gradle.apionly.transcriberj.model.ContractModel;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Discriminator;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Finding;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Schema;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Treatment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a schema amounts to for code generation: whether it is an object, the
 * properties it has once every {@code allOf} is flattened, and which Java type
 * stands for each value.
 */
final class Shapes {

    /** How a value is passed to {@code body(...)} and written into it. */
    enum Kind {
        STRING("String", "String"),
        INTEGER("int", "Integer"),
        LONG("long", "Long"),
        NUMBER("double", "Double"),
        BOOLEAN("boolean", "Boolean"),
        /** JSON another body, or the caller, has already written. */
        JSON("String", "String");

        final String required;
        final String optional;

        Kind(String required, String optional) {
            this.required = required;
            this.optional = optional;
        }
    }

    /**
     * A value's Java type: a single value, or a list of them.
     *
     * @param kind the value's kind, or the kind of each item
     * @param list whether it is a list
     */
    record ValueType(Kind kind, boolean list) {

        String javaType(boolean optional) {
            if (list) return "java.util.List<" + kind.optional + ">";
            return optional ? kind.optional : kind.required;
        }

        String render(String variable) {
            String one = switch (kind) {
                case STRING -> "ContractJson::string";
                case JSON -> "ContractJson::embed";
                default -> "String::valueOf";
            };
            if (list) {
                return "ContractJson.array(" + variable + ".stream().map(" + one + ").toList())";
            }
            return switch (kind) {
                case STRING -> "ContractJson.string(" + variable + ")";
                case JSON -> "ContractJson.embed(" + variable + ")";
                default -> "String.valueOf(" + variable + ")";
            };
        }
    }

    /** A schema no rule represents yet, found while generating a method. */
    static final class Unrepresentable extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient Finding finding;

        Unrepresentable(Finding finding) {
            super(finding.detail(), null, false, false);
            this.finding = finding;
        }
    }

    /**
     * One property of an object, after flattening.
     *
     * @param name     its name
     * @param schema   its schema, merged from every definition of it
     * @param required whether any definition requires it
     * @param location the JSON pointer of its first definition
     */
    record Property(String name, Schema schema, boolean required, String location) {
    }

    /**
     * An object, after flattening.
     *
     * @param properties its properties, in the order they are first defined
     * @param open       whether it allows members it does not list
     */
    record ObjectShape(List<Property> properties, boolean open) {
    }

    private static final Set<Construct> UNREPRESENTED = Set.of(
            Construct.MULTIPLE_TYPES, Construct.BOOLEAN_SCHEMA,
            Construct.ONE_OF_INLINE_BRANCHES, Construct.ANY_OF_INLINE_BRANCHES);

    private final Map<String, Component> components = new LinkedHashMap<>();
    private final Map<String, List<Finding>> findings = new LinkedHashMap<>();

    Shapes(ContractModel model) {
        model.components().forEach(c -> components.put(c.name(), c));
        model.findings().forEach(f -> findings.computeIfAbsent(f.location(), k -> new ArrayList<>()).add(f));
    }

    Optional<Schema> component(String name) {
        return Optional.ofNullable(components.get(name)).map(Component::schema);
    }

    private Schema follow(Schema schema) {
        Set<String> seen = new HashSet<>();
        while (schema.ref() != null && schema.properties() == null && schema.types() == null
                && seen.add(schema.ref())) {
            schema = components.get(schema.ref()).schema();
        }
        return schema;
    }

    /** Whether a schema describes an object, following references and {@code allOf}. */
    boolean isObject(Schema schema) {
        return isObject(schema, new HashSet<>());
    }

    private boolean isObject(Schema schema, Set<String> seen) {
        if (schema.literal() != null) return false;
        if (schema.ref() != null && schema.properties() == null && schema.types() == null) {
            return seen.add(schema.ref()) && isObject(components.get(schema.ref()).schema(), seen);
        }
        if (schema.properties() != null) return true;
        if (schema.types() != null) return schema.types().size() == 1 && schema.types().get(0).equals("object");
        return schema.allOf() != null && schema.allOf().stream().anyMatch(b -> isObject(b, seen));
    }

    /**
     * Whether a class of this schema writes a body. An object does. A choice -- a
     * {@code oneOf} or {@code anyOf} that is the whole of a schema -- does when one of its
     * branches is an object written inline, and then degrades, since no rule represents it;
     * a choice whose branches are all references is written by its branches instead.
     */
    boolean bodyShaped(Schema schema) {
        Schema s = follow(schema);
        List<Schema> choice = choice(s);
        if (choice != null) {
            return choice.stream().anyMatch(b -> b.ref() == null) && choice.stream().anyMatch(this::isObject);
        }
        return isObject(schema);
    }

    /**
     * The components a choice names as its branches, following references: those whose
     * bodies a caller writes when it writes one of this schema.
     */
    List<String> branches(Schema schema) {
        List<Schema> choice = choice(follow(schema));
        if (choice == null) return List.of();
        return choice.stream().filter(b -> b.ref() != null).map(Schema::ref).toList();
    }

    /** The branches of a schema that is nothing but a choice, or null. */
    private static List<Schema> choice(Schema schema) {
        if (schema.properties() != null || schema.allOf() != null) return null;
        return schema.oneOf() != null ? schema.oneOf() : schema.anyOf();
    }

    /** Whether a schema describes a list, following references. */
    boolean isArray(Schema schema) {
        Schema target = follow(schema);
        return target.literal() == null && (target.items() != null
                || (target.types() != null && target.types().equals(List.of("array"))));
    }

    /** An object schema, with every {@code allOf} flattened into it. */
    ObjectShape object(Schema schema, String location) {
        Map<String, List<Schema>> definitions = new LinkedHashMap<>();
        Map<String, String> locations = new LinkedHashMap<>();
        Set<String> required = new LinkedHashSet<>();
        boolean[] open = {false};
        flatten(schema, location, definitions, locations, required, open, new HashSet<>());
        List<Property> properties = new ArrayList<>();
        definitions.forEach((name, defs) ->
                properties.add(new Property(name, merge(defs), required.contains(name), locations.get(name))));
        return new ObjectShape(properties, open[0]);
    }

    private void flatten(Schema schema, String location, Map<String, List<Schema>> definitions,
                         Map<String, String> locations, Set<String> required, boolean[] open, Set<String> seen) {
        unrepresentable(schema, location);
        if (schema.ref() != null) {
            if (seen.add(schema.ref())) {
                flatten(components.get(schema.ref()).schema(), "/components/schemas/" + escape(schema.ref()),
                        definitions, locations, required, open, seen);
            }
        }
        if (schema.required() != null) required.addAll(schema.required());
        if (schema.additionalProperties() != null) {
            AdditionalProperties additional = schema.additionalProperties();
            open[0] |= additional.schema() != null || Boolean.TRUE.equals(additional.allowed());
        }
        open[0] |= schema.patternProperties() != null;
        if (schema.properties() != null) {
            schema.properties().forEach((name, property) -> {
                definitions.computeIfAbsent(name, k -> new ArrayList<>()).add(property);
                locations.putIfAbsent(name, location + "/properties/" + escape(name));
            });
        }
        if (schema.allOf() != null) {
            for (int i = 0; i < schema.allOf().size(); i++) {
                flatten(schema.allOf().get(i), location + "/allOf/" + i, definitions, locations, required, open,
                        seen);
            }
        }
    }

    /** One schema from several definitions of the same property: the first definition of each keyword wins. */
    private static Schema merge(List<Schema> definitions) {
        if (definitions.size() == 1) return definitions.get(0);
        Schema[] d = definitions.toArray(Schema[]::new);
        Map<String, Object> annotations = new LinkedHashMap<>();
        Map<String, Object> unmodelled = new LinkedHashMap<>();
        for (int i = d.length - 1; i >= 0; i--) {
            annotations.putAll(d[i].annotations());
            unmodelled.putAll(d[i].unmodelled());
        }
        Constraints[] c = java.util.Arrays.stream(d).map(Schema::constraints).toArray(Constraints[]::new);
        Schema typed = first(d, s -> s.types() != null ? s : null);
        return new Schema(
                first(d, Schema::literal),
                first(d, Schema::ref),
                typed == null ? null : typed.types(),
                typed != null && typed.typeWrittenAsList(),
                first(d, Schema::format),
                first(d, Schema::description),
                first(d, Schema::constValue),
                first(d, Schema::enumValues),
                first(d, Schema::required),
                first(d, Schema::properties),
                first(d, Schema::patternProperties),
                first(d, Schema::items),
                first(d, Schema::additionalProperties),
                first(d, Schema::allOf),
                first(d, Schema::oneOf),
                first(d, Schema::anyOf),
                first(d, Schema::discriminator),
                new Constraints(
                        first(c, Constraints::minLength), first(c, Constraints::maxLength),
                        first(c, Constraints::pattern), first(c, Constraints::minimum),
                        first(c, Constraints::maximum), first(c, Constraints::exclusiveMinimum),
                        first(c, Constraints::exclusiveMaximum), first(c, Constraints::multipleOf),
                        first(c, Constraints::minItems), first(c, Constraints::maxItems),
                        first(c, Constraints::uniqueItems), first(c, Constraints::minProperties),
                        first(c, Constraints::maxProperties)),
                java.util.Collections.unmodifiableMap(annotations),
                java.util.Collections.unmodifiableMap(unmodelled));
    }

    private static <S, T> T first(S[] sources, java.util.function.Function<S, T> field) {
        for (S source : sources) {
            T value = field.apply(source);
            if (value != null) return value;
        }
        return null;
    }

    /** Throws when a schema, at this location, is one no rule represents. */
    void unrepresentable(Schema schema, String location) {
        for (Finding f : findings.getOrDefault(location, List.of())) {
            if (UNREPRESENTED.contains(f.construct())) throw new Unrepresentable(f);
        }
        if (schema.literal() != null) {
            throw new Unrepresentable(new Finding(location, Construct.BOOLEAN_SCHEMA, Treatment.UNDECIDED,
                    schema.literal().toString()));
        }
        if (schema.types() != null && schema.types().size() > 1) {
            throw new Unrepresentable(new Finding(location, Construct.MULTIPLE_TYPES, Treatment.UNDECIDED,
                    "type: " + String.join(", ", schema.types())));
        }
    }

    /** Whether a reference at this location closes a cycle of components. */
    boolean closesCycle(String location) {
        return findings.getOrDefault(location, List.of()).stream()
                .anyMatch(f -> f.construct() == Construct.RECURSIVE_REF);
    }

    /** How a value of this schema is passed to {@code body(...)}. */
    ValueType valueType(Schema schema, String location) {
        unrepresentable(schema, location);
        if (schema.ref() != null && schema.types() == null && schema.properties() == null) {
            Schema target = components.get(schema.ref()).schema();
            return valueType(target, "/components/schemas/" + escape(schema.ref()));
        }
        if (isArray(schema)) {
            if (schema.items() == null) return new ValueType(Kind.JSON, true);
            ValueType item = valueType(schema.items(), location + "/items");
            return new ValueType(item.kind(), true);
        }
        if (isObject(schema) || schema.oneOf() != null || schema.anyOf() != null || schema.types() == null) {
            return new ValueType(Kind.JSON, false);
        }
        return new ValueType(switch (schema.types().get(0)) {
            case "string" -> Kind.STRING;
            case "integer" -> "int64".equals(schema.format()) ? Kind.LONG : Kind.INTEGER;
            case "number" -> Kind.NUMBER;
            case "boolean" -> Kind.BOOLEAN;
            default -> Kind.JSON;
        }, false);
    }

    /** The JSON type of a field, as {@code ContractField} names it. */
    String fieldType(Schema schema) {
        Schema target = follow(schema);
        if (target.literal() != null || (target.types() != null && target.types().size() > 1)) return "varies";
        if (isArray(target)) return "array";
        if (isObject(target)) return "object";
        if (target.types() == null) return "varies";
        return switch (target.types().get(0)) {
            case "integer", "number" -> "number";
            case "string", "boolean", "null", "object", "array" -> target.types().get(0);
            default -> "varies";
        };
    }

    /**
     * The components whose rendered JSON a body of this schema takes as an
     * argument: those of its object and list-of-object properties.
     */
    Set<String> renderedReferences(Schema schema) {
        Set<String> names = new LinkedHashSet<>();
        if (!isObject(schema)) return names;
        ObjectShape shape;
        try {
            shape = object(schema, "");
        } catch (Unrepresentable e) {
            return names;
        }
        for (Property p : shape.properties()) {
            Schema s = p.schema();
            if (s.ref() != null) {
                Schema target = components.get(s.ref()).schema();
                if (isObject(target)) names.add(s.ref());
                if (isArray(target) && target.items() != null && target.items().ref() != null
                        && isObject(target.items())) {
                    names.add(target.items().ref());
                }
            } else if (s.items() != null && s.items().ref() != null && isObject(s.items())) {
                names.add(s.items().ref());
            }
        }
        return names;
    }

    static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    /** A schema as the plain values it was parsed from, for hashing an inline schema. */
    static Object render(Schema schema) {
        if (schema.literal() != null) return schema.literal();
        Map<String, Object> out = new LinkedHashMap<>();
        if (schema.ref() != null) out.put("$ref", "#/components/schemas/" + escape(schema.ref()));
        if (schema.types() != null) {
            out.put("type", schema.typeWrittenAsList() ? schema.types() : schema.types().get(0));
        }
        put(out, "format", schema.format());
        put(out, "description", schema.description());
        if (schema.constValue() != null) out.put("const", schema.constValue().value());
        put(out, "enum", schema.enumValues());
        put(out, "required", schema.required());
        if (schema.properties() != null) out.put("properties", renderAll(schema.properties()));
        if (schema.patternProperties() != null) out.put("patternProperties", renderAll(schema.patternProperties()));
        if (schema.items() != null) out.put("items", render(schema.items()));
        if (schema.additionalProperties() != null) {
            AdditionalProperties a = schema.additionalProperties();
            out.put("additionalProperties", a.schema() != null ? render(a.schema()) : a.allowed());
        }
        if (schema.allOf() != null) out.put("allOf", schema.allOf().stream().map(Shapes::render).toList());
        if (schema.oneOf() != null) out.put("oneOf", schema.oneOf().stream().map(Shapes::render).toList());
        if (schema.anyOf() != null) out.put("anyOf", schema.anyOf().stream().map(Shapes::render).toList());
        if (schema.discriminator() != null) {
            Discriminator d = schema.discriminator();
            Map<String, Object> rendered = new LinkedHashMap<>();
            put(rendered, "propertyName", d.propertyName());
            put(rendered, "mapping", d.mapping());
            rendered.putAll(d.other());
            out.put("discriminator", rendered);
        }
        Constraints c = schema.constraints();
        put(out, "minLength", c.minLength());
        put(out, "maxLength", c.maxLength());
        put(out, "pattern", c.pattern());
        put(out, "minimum", c.minimum());
        put(out, "maximum", c.maximum());
        put(out, "exclusiveMinimum", c.exclusiveMinimum());
        put(out, "exclusiveMaximum", c.exclusiveMaximum());
        put(out, "multipleOf", c.multipleOf());
        put(out, "minItems", c.minItems());
        put(out, "maxItems", c.maxItems());
        put(out, "uniqueItems", c.uniqueItems());
        put(out, "minProperties", c.minProperties());
        put(out, "maxProperties", c.maxProperties());
        out.putAll(schema.annotations());
        out.putAll(schema.unmodelled());
        return out;
    }

    private static Map<String, Object> renderAll(Map<String, Schema> schemas) {
        Map<String, Object> out = new LinkedHashMap<>();
        schemas.forEach((name, s) -> out.put(name, render(s)));
        return out;
    }

    private static void put(Map<String, Object> out, String key, Object value) {
        if (value != null) out.put(key, value);
    }
}
