package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.model.AdditionalProperties;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Constraints;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Schema;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every keyword a value violates, each with the JSON pointer of the instance it is
 * violated at and the location of the schema that declares it: what decides whether a
 * derived invalid request has exactly one fault.
 *
 * <p>It evaluates what the model types, as a JSON Schema 2020-12 validator would, with
 * the two rewrites the valid-value oracle makes: a {@code format} beside a
 * {@code pattern} is not asserted, since the pattern wins; and a 3.0
 * {@code minimum} with {@code exclusiveMinimum: true} is violated as
 * {@code exclusiveMinimum}. A string meeting a {@code format} no check exists for is reported
 * as an unchecked fault, since it cannot be vouched for. A {@code required} member that is missing, and a member
 * that is not allowed, are reported at the member's pointer rather than at the object's.
 *
 * <p>With strictness on, an object whose schemas declare neither
 * {@code additionalProperties} nor {@code patternProperties} allows only the members
 * some part of its {@code allOf} declares; a member it does not is reported as
 * {@code additionalProperties}. The branches of a {@code oneOf} or {@code anyOf} are
 * not strict at their top level, since the object they are part of decides that.
 */
final class Faults {

    /**
     * One violated keyword, or one this class cannot check.
     *
     * @param keyword        the keyword, as the contract spells it
     * @param pointer        the JSON pointer of the instance, relative to the value evaluated
     * @param schemaLocation the JSON pointer of the schema that declares the keyword
     * @param checked        false for a {@code format} no check exists for: the value may or may
     *                       not be of it, so it cannot be vouched for either way
     */
    record Fault(String keyword, String pointer, String schemaLocation, boolean checked) {

        Fault(String keyword, String pointer, String schemaLocation) {
            this(keyword, pointer, schemaLocation, true);
        }
    }

    /** A schema, and where in the contract it is written. */
    record At(Schema schema, String location) {
    }

    /** A value that cannot be evaluated, since a keyword that applies to it is one the model does not type. */
    static final class Unevaluable extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String keyword;
        final String location;

        Unevaluable(String keyword, String location) {
            super(keyword + " at " + location, null, false, false);
            this.keyword = keyword;
            this.location = location;
        }
    }

    /** The keywords that assert something the model does not type. */
    static final List<String> UNMODELLED_ASSERTIONS = List.of("not", "if", "then", "else",
            "dependentSchemas", "dependentRequired", "dependencies", "prefixItems", "additionalItems", "contains",
            "minContains", "maxContains", "propertyNames", "unevaluatedItems", "unevaluatedProperties",
            "$dynamicRef", "$recursiveRef");

    private final Shapes shapes;
    private final boolean strict;
    private final Map<String, EcmaPattern> patterns = new HashMap<>();

    Faults(Shapes shapes, boolean strict) {
        this.shapes = shapes;
        this.strict = strict;
    }

    /** Every fault of a value against the schemas it must satisfy at once. */
    List<Fault> of(Object value, List<At> roots) {
        List<Fault> out = new ArrayList<>();
        evaluate(value, roots, "", false, out, 0);
        return out;
    }

    /**
     * Every schema a value must satisfy at once: the roots, with each {@code $ref} and
     * {@code allOf} expanded, in the order the valid-value generator expands them.
     */
    static List<At> parts(Shapes shapes, List<At> roots) {
        List<At> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (At root : roots) expand(shapes, root, seen, out);
        return out;
    }

    /** The components the expansion of the roots refers to, in the order it meets them. */
    static Set<String> refs(Shapes shapes, List<At> roots) {
        Set<String> out = new LinkedHashSet<>();
        for (At part : parts(shapes, roots)) {
            String prefix = "/components/schemas/";
            if (part.location().startsWith(prefix) && part.location().indexOf('/', prefix.length()) < 0) {
                out.add(part.location().substring(prefix.length()).replace("~1", "/").replace("~0", "~"));
            }
        }
        return out;
    }

    private static void expand(Shapes shapes, At at, Set<String> seen, List<At> out) {
        Schema s = at.schema();
        if (s.ref() != null && seen.add(s.ref())) {
            expand(shapes, new At(shapes.component(s.ref()).orElseThrow(),
                    "/components/schemas/" + Shapes.escape(s.ref())), seen, out);
        }
        out.add(at);
        if (s.allOf() != null) {
            for (int i = 0; i < s.allOf().size(); i++) {
                expand(shapes, new At(s.allOf().get(i), at.location() + "/allOf/" + i), seen, out);
            }
        }
    }

    /** Whether any of the parts describes an object: it names the type, or declares properties. */
    static boolean objectLike(List<At> parts) {
        return parts.stream().anyMatch(p -> (p.schema().types() != null && p.schema().types().contains("object"))
                || p.schema().properties() != null);
    }

    /** Whether some part declares {@code additionalProperties} or {@code patternProperties}: strictness then does not apply. */
    static boolean declaresOpenness(List<At> parts) {
        return parts.stream().anyMatch(p -> p.schema().additionalProperties() != null
                || p.schema().patternProperties() != null);
    }

    private void evaluate(Object value, List<At> roots, String pointer, boolean lenientTop, List<Fault> out,
                          int depth) {
        if (depth > 64) throw new Unevaluable("$ref", roots.get(0).location());
        List<At> parts = parts(shapes, roots);
        for (At part : parts) own(value, part, pointer, out, depth);
        if (value instanceof Map<?, ?> map) members(map, parts, pointer, lenientTop, out, depth);
        if (value instanceof List<?> list) items(list, parts, pointer, out, depth);
    }

    private void own(Object value, At part, String pointer, List<Fault> out, int depth) {
        Schema s = part.schema();
        String at = part.location();
        if (s.literal() != null) {
            if (!s.literal()) out.add(new Fault("false", pointer, at));
            return;
        }
        for (String keyword : s.unmodelled().keySet()) {
            if (UNMODELLED_ASSERTIONS.contains(keyword)) throw new Unevaluable(keyword, at);
        }
        String type = ValueJson.type(value);
        if (s.types() != null && s.types().stream().noneMatch(t -> t.equals(type)
                || (t.equals("number") && type.equals("integer")))) {
            out.add(new Fault("type", pointer, at));
        }
        if (s.constValue() != null && !ValueJson.equal(ValueJson.of(s.constValue().value()), value)) {
            out.add(new Fault("const", pointer, at));
        }
        if (s.enumValues() != null && s.enumValues().stream().noneMatch(e -> ValueJson.equal(ValueJson.of(e), value))) {
            out.add(new Fault("enum", pointer, at));
        }
        Constraints c = s.constraints();
        switch (value) {
            case String string -> string(string, s, c, pointer, at, out);
            case BigDecimal number -> number(number, c, pointer, at, out);
            case List<?> list -> array(list, c, pointer, at, out);
            case Map<?, ?> map -> object(map, s, c, pointer, at, out);
            default -> {
            }
        }
        if (s.oneOf() != null && matching(value, s.oneOf(), at + "/oneOf/", pointer, depth) != 1) {
            out.add(new Fault("oneOf", pointer, at));
        }
        if (s.anyOf() != null && matching(value, s.anyOf(), at + "/anyOf/", pointer, depth) == 0) {
            out.add(new Fault("anyOf", pointer, at));
        }
    }

    private int matching(Object value, List<Schema> branches, String prefix, String pointer, int depth) {
        int count = 0;
        for (int i = 0; i < branches.size(); i++) {
            List<Fault> faults = new ArrayList<>();
            evaluate(value, List.of(new At(branches.get(i), prefix + i)), pointer, true, faults, depth + 1);
            if (faults.isEmpty()) count++;
        }
        return count;
    }

    private void string(String value, Schema s, Constraints c, String pointer, String at, List<Fault> out) {
        int length = value.codePointCount(0, value.length());
        if (c.minLength() != null && length < c.minLength().doubleValue()) out.add(new Fault("minLength", pointer, at));
        if (c.maxLength() != null && length > c.maxLength().doubleValue()) out.add(new Fault("maxLength", pointer, at));
        if (c.pattern() != null && !pattern(c.pattern()).find(value)) out.add(new Fault("pattern", pointer, at));
        if (c.pattern() == null && s.format() != null) {
            if (!Formats.supported(s.format())) {
                out.add(new Fault("format", pointer, at, false));
            } else if (!Formats.accepts(s.format(), value)) {
                out.add(new Fault("format", pointer, at));
            }
        }
    }

    private static void number(BigDecimal value, Constraints c, String pointer, String at, List<Fault> out) {
        if (c.minimum() != null) {
            int cmp = value.compareTo((BigDecimal) ValueJson.of(c.minimum()));
            if (Boolean.TRUE.equals(c.exclusiveMinimum()) ? cmp <= 0 : cmp < 0) {
                out.add(new Fault(Boolean.TRUE.equals(c.exclusiveMinimum()) ? "exclusiveMinimum" : "minimum", pointer, at));
            }
        }
        if (c.exclusiveMinimum() instanceof Number n && value.compareTo((BigDecimal) ValueJson.of(n)) <= 0) {
            out.add(new Fault("exclusiveMinimum", pointer, at));
        }
        if (c.maximum() != null) {
            int cmp = value.compareTo((BigDecimal) ValueJson.of(c.maximum()));
            if (Boolean.TRUE.equals(c.exclusiveMaximum()) ? cmp >= 0 : cmp > 0) {
                out.add(new Fault(Boolean.TRUE.equals(c.exclusiveMaximum()) ? "exclusiveMaximum" : "maximum", pointer, at));
            }
        }
        if (c.exclusiveMaximum() instanceof Number n && value.compareTo((BigDecimal) ValueJson.of(n)) >= 0) {
            out.add(new Fault("exclusiveMaximum", pointer, at));
        }
        if (c.multipleOf() != null && value.remainder((BigDecimal) ValueJson.of(c.multipleOf())).signum() != 0) {
            out.add(new Fault("multipleOf", pointer, at));
        }
    }

    private static void array(List<?> list, Constraints c, String pointer, String at, List<Fault> out) {
        if (c.minItems() != null && list.size() < c.minItems().doubleValue()) out.add(new Fault("minItems", pointer, at));
        if (c.maxItems() != null && list.size() > c.maxItems().doubleValue()) out.add(new Fault("maxItems", pointer, at));
        if (Boolean.TRUE.equals(c.uniqueItems())) {
            boolean unique = true;
            for (int i = 0; i < list.size() && unique; i++) {
                for (int j = i + 1; j < list.size() && unique; j++) {
                    unique = !ValueJson.equal(list.get(i), list.get(j));
                }
            }
            if (!unique) out.add(new Fault("uniqueItems", pointer, at));
        }
    }

    private void object(Map<?, ?> map, Schema s, Constraints c, String pointer, String at, List<Fault> out) {
        if (c.minProperties() != null && map.size() < c.minProperties().doubleValue()) {
            out.add(new Fault("minProperties", pointer, at));
        }
        if (c.maxProperties() != null && map.size() > c.maxProperties().doubleValue()) {
            out.add(new Fault("maxProperties", pointer, at));
        }
        if (s.required() != null) {
            for (String name : s.required()) {
                if (!map.containsKey(name)) out.add(new Fault("required", pointer + "/" + Shapes.escape(name), at));
            }
        }
        AdditionalProperties additional = s.additionalProperties();
        if (additional != null && Boolean.FALSE.equals(additional.allowed())) {
            for (Object key : map.keySet()) {
                String name = (String) key;
                if (!declared(s, name)) out.add(new Fault("additionalProperties", pointer + "/" + Shapes.escape(name), at));
            }
        }
    }

    /** Whether a schema's own {@code properties} or {@code patternProperties} name a member. */
    private boolean declared(Schema s, String name) {
        if (s.properties() != null && s.properties().containsKey(name)) return true;
        if (s.patternProperties() == null) return false;
        return s.patternProperties().keySet().stream().anyMatch(p -> pattern(p).find(name));
    }

    private void members(Map<?, ?> map, List<At> parts, String pointer, boolean lenientTop, List<Fault> out,
                         int depth) {
        if (strict && !lenientTop && objectLike(parts) && !declaresOpenness(parts)) {
            Set<String> known = known(parts, 0);
            for (Object key : map.keySet()) {
                if (!known.contains((String) key)) {
                    out.add(new Fault("additionalProperties", pointer + "/" + Shapes.escape((String) key),
                            parts.get(0).location()));
                }
            }
        }
        for (Map.Entry<?, ?> member : map.entrySet()) {
            String name = (String) member.getKey();
            List<At> roots = new ArrayList<>();
            for (At part : parts) {
                Schema s = part.schema();
                boolean matched = false;
                if (s.properties() != null && s.properties().containsKey(name)) {
                    roots.add(new At(s.properties().get(name), part.location() + "/properties/" + Shapes.escape(name)));
                    matched = true;
                }
                if (s.patternProperties() != null) {
                    for (Map.Entry<String, Schema> p : s.patternProperties().entrySet()) {
                        if (pattern(p.getKey()).find(name)) {
                            roots.add(new At(p.getValue(), part.location() + "/patternProperties/"
                                    + Shapes.escape(p.getKey())));
                            matched = true;
                        }
                    }
                }
                if (!matched && s.additionalProperties() != null && s.additionalProperties().schema() != null) {
                    roots.add(new At(s.additionalProperties().schema(), part.location() + "/additionalProperties"));
                }
            }
            if (!roots.isEmpty()) {
                evaluate(member.getValue(), roots, pointer + "/" + Shapes.escape(name), false, out, depth + 1);
            }
        }
    }

    /** Every member name the parts declare, and those the branches of their choices declare. */
    private Set<String> known(List<At> parts, int depth) {
        Set<String> out = new HashSet<>();
        if (depth > 16) return out;
        for (At part : parts) {
            Schema s = part.schema();
            if (s.properties() != null) out.addAll(s.properties().keySet());
            for (List<Schema> choice : java.util.Arrays.asList(s.oneOf(), s.anyOf())) {
                if (choice == null) continue;
                for (int i = 0; i < choice.size(); i++) {
                    out.addAll(known(parts(shapes, List.of(new At(choice.get(i), part.location() + "/choice/" + i))),
                            depth + 1));
                }
            }
        }
        return out;
    }

    private void items(List<?> list, List<At> parts, String pointer, List<Fault> out, int depth) {
        List<At> roots = new ArrayList<>();
        for (At part : parts) {
            if (part.schema().items() != null) roots.add(new At(part.schema().items(), part.location() + "/items"));
        }
        if (roots.isEmpty()) return;
        for (int i = 0; i < list.size(); i++) {
            evaluate(list.get(i), roots, pointer + "/" + i, false, out, depth + 1);
        }
    }

    private EcmaPattern pattern(String source) {
        EcmaPattern known = patterns.get(source);
        if (known != null) return known;
        try {
            EcmaPattern compiled = EcmaPattern.compile(source);
            patterns.put(source, compiled);
            return compiled;
        } catch (EcmaPattern.Unsupported e) {
            throw new Unevaluable("pattern", source);
        }
    }
}
