package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.model.AdditionalProperties;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Const;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Constraints;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Discriminator;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Schema;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A valid value of a schema, built from its constraints alone.
 *
 * <p>Deterministic: every choice -- which enum value, which branch, which character -- is
 * made by a fixed rule, never at random, so the same contract yields the same values on
 * every run. {@link Variant#REQUIRED} is minimal: the required members only, plus what
 * {@code minProperties} forces, and exactly {@code minItems} items. {@link Variant#FULL}
 * has every declared member, and at least one item where {@code maxItems} allows it.
 *
 * <p>Every keyword of an {@code allOf} applies, so its branches are intersected: the
 * larger {@code minLength}, the smaller {@code maxLength}, every {@code pattern}, the
 * values common to every {@code enum}, the stricter bounds. That is this class's merge,
 * not {@code Shapes}', which keeps the first definition of a keyword for describing a
 * schema and must go on doing so.
 *
 * <p>When no valid value exists, or this generator cannot find one, it throws
 * {@link Unsatisfiable} naming the location and the reason; it never falls back to
 * something that merely looks plausible. A construct no rule represents throws
 * {@link Shapes.Unrepresentable}, as it does wherever else it is reached.
 */
final class ValidValues {

    /** Which valid value. */
    enum Variant {
        /** The smallest: required members only, {@code minItems} items. */
        REQUIRED,
        /** Every declared member, and at least one item where allowed. */
        FULL
    }

    /** No valid value exists, or none could be found. */
    static class Unsatisfiable extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String location;
        final String reason;

        Unsatisfiable(String location, String reason) {
            super(reason + " at " + location, null, false, false);
            this.location = location;
            this.reason = reason;
        }
    }

    /** A reference that would be followed more often than {@code recursionDepth} allows. */
    static final class TooDeep extends Unsatisfiable {
        private static final long serialVersionUID = 1L;

        TooDeep(String location, String reason) {
            super(location, reason);
        }
    }

    /** How many candidates are tried for one value before this generator gives up. */
    static final int SEARCH = 2000;

    /** The keywords that assert something the model does not type: a value cannot be checked against them. */
    private static final List<String> UNMODELLED_ASSERTIONS = List.of("not", "if", "then", "else",
            "dependentSchemas", "dependentRequired", "dependencies", "prefixItems", "additionalItems", "contains",
            "minContains", "maxContains", "propertyNames", "unevaluatedItems", "unevaluatedProperties",
            "$dynamicRef", "$recursiveRef");

    private final Shapes shapes;
    private final int recursionDepth;
    private final Map<String, EcmaPattern> patterns = new HashMap<>();
    private final Set<String> unsupportedFormats = new LinkedHashSet<>();

    ValidValues(Shapes shapes, int recursionDepth) {
        this.shapes = shapes;
        this.recursionDepth = recursionDepth;
    }

    /**
     * A valid value of a schema.
     *
     * @throws Unsatisfiable when no valid value exists or none can be found
     * @throws Shapes.Unrepresentable when the value reaches a construct no rule represents
     */
    Object value(Schema schema, String location, Variant variant) {
        Context context = new Context(variant, List.of());
        Intersection in = intersection(List.of(new At(schema, location)), Set.of(), context);
        return first(candidates(in, context), in);
    }

    /**
     * A valid value of several schemas at once: a member that more than one part of an
     * {@code allOf} defines, for instance.
     *
     * @throws Unsatisfiable when no valid value exists or none can be found
     * @throws Shapes.Unrepresentable when the value reaches a construct no rule represents
     */
    Object value(List<Faults.At> roots, Variant variant) {
        Context context = new Context(variant, List.of());
        Intersection in = intersection(at(roots), Set.of(), context);
        return first(candidates(in, context), in);
    }

    /**
     * Up to {@code count} distinct valid values of several schemas at once, in the order
     * they are generated: fewer when fewer are found.
     *
     * @throws Unsatisfiable when no valid value exists or none can be found
     * @throws Shapes.Unrepresentable when a value reaches a construct no rule represents
     */
    List<Object> values(List<Faults.At> roots, int count) {
        Context context = new Context(Variant.REQUIRED, List.of());
        Intersection in = intersection(at(roots), Set.of(), context);
        Iterator<Object> candidates = Lazy.limit(candidates(in, context), SEARCH);
        List<Object> out = new ArrayList<>();
        while (out.size() < count && candidates.hasNext()) {
            Object value = candidates.next();
            if (out.stream().noneMatch(v -> ValueJson.equal(v, value))) out.add(value);
        }
        return out;
    }

    private static List<At> at(List<Faults.At> roots) {
        return roots.stream().map(r -> new At(r.schema(), r.location())).toList();
    }

    /** Whether a value is valid against a schema, as far as the keywords the model types go. */
    boolean valid(Object value, Schema schema, String location) {
        return valid(value, schema, location, 0);
    }

    /** Every {@code format} met that no value is generated for: {@code location: format}. */
    Set<String> unsupportedFormats() {
        return Collections.unmodifiableSet(unsupportedFormats);
    }

    // ---------------------------------------------------------- structure

    /** A schema, and where in the contract it is written. */
    private record At(Schema schema, String location) {
    }

    /**
     * Where generation is: the variant, and for each enclosing value the components it
     * referred to, so that recursion is counted.
     */
    private record Context(Variant variant, List<Set<String>> ancestors) {

        Context child(Set<String> refs) {
            List<Set<String>> out = new ArrayList<>(ancestors);
            out.add(refs);
            return new Context(variant, List.copyOf(out));
        }

        int count(String ref) {
            return (int) ancestors.stream().filter(s -> s.contains(ref)).count();
        }
    }

    /** One {@code oneOf} or {@code anyOf}, and the schema it is written in. */
    private record Choice(At owner, boolean exclusive, List<At> branches) {
    }

    /** A bound: its value, and whether the value itself is excluded. */
    private record Bound(BigDecimal value, boolean exclusive, String location) {
    }

    private Intersection intersection(List<At> roots, Set<String> resolved, Context context) {
        Intersection in = new Intersection(roots, resolved);
        for (String ref : in.refs) {
            if (context.count(ref) > recursionDepth) {
                throw new TooDeep(in.location(), "the reference to component " + ref + " would be followed more than "
                        + "recursionDepth (" + recursionDepth + ") times; a value must stop recursing before that, "
                        + "and this one is required to go on");
            }
        }
        return in;
    }

    /**
     * Every schema a value must be valid against at once: the roots, with each
     * {@code $ref} and {@code allOf} expanded, in the order {@code Shapes} flattens them.
     */
    private final class Intersection {
        final List<At> roots;
        final List<At> parts = new ArrayList<>();
        final Set<String> refs = new LinkedHashSet<>();
        final Set<String> resolved;

        Intersection(List<At> roots, Set<String> resolved) {
            this.roots = roots;
            this.resolved = resolved;
            Set<String> seen = new HashSet<>();
            for (At root : roots) expand(root, seen);
        }

        private void expand(At at, Set<String> seen) {
            Schema s = at.schema();
            shapes.unrepresentable(s, at.location());
            // A component's own schema is a visit to it, as much as a reference to it is.
            String prefix = "/components/schemas/";
            if (at.location().startsWith(prefix) && at.location().indexOf('/', prefix.length()) < 0) {
                String name = at.location().substring(prefix.length()).replace("~1", "/").replace("~0", "~");
                if (seen.add(name)) refs.add(name);
            }
            if (s.ref() != null && seen.add(s.ref())) {
                refs.add(s.ref());
                expand(new At(shapes.component(s.ref()).orElseThrow(),
                        "/components/schemas/" + Shapes.escape(s.ref())), seen);
            }
            parts.add(at);
            if (s.allOf() != null) {
                for (int i = 0; i < s.allOf().size(); i++) {
                    expand(new At(s.allOf().get(i), at.location() + "/allOf/" + i), seen);
                }
            }
        }

        String location() {
            return roots.get(0).location();
        }

        Intersection with(At extra, Set<String> alsoResolved) {
            List<At> more = new ArrayList<>(roots);
            if (extra != null) more.add(extra);
            Set<String> all = new HashSet<>(resolved);
            all.addAll(alsoResolved);
            return new Intersection(more, all);
        }

        /** A value that satisfies every part's own keywords, with each resolved choice left to its chooser. */
        boolean accepts(Object value) {
            for (At part : parts) {
                if (!own(value, part.schema(), part.location(), !resolved.contains(part.location()), 0)) return false;
            }
            return true;
        }

        void assertionsModelled() {
            for (At part : parts) {
                for (String keyword : part.schema().unmodelled().keySet()) {
                    if (UNMODELLED_ASSERTIONS.contains(keyword)) {
                        throw new Unsatisfiable(part.location(), "validity depends on " + keyword
                                + ", which the model does not type, so no value is generated rather than one guessed at");
                    }
                }
            }
        }

        List<Choice> choices() {
            List<Choice> out = new ArrayList<>();
            for (At part : parts) {
                if (resolved.contains(part.location())) continue;
                Schema s = part.schema();
                if (s.oneOf() != null) out.add(new Choice(part, true, branches(part, "oneOf", s.oneOf())));
                else if (s.anyOf() != null) out.add(new Choice(part, false, branches(part, "anyOf", s.anyOf())));
            }
            return out;
        }

        private List<At> branches(At owner, String keyword, List<Schema> branches) {
            List<At> out = new ArrayList<>();
            for (int i = 0; i < branches.size(); i++) {
                out.add(new At(branches.get(i), owner.location() + "/" + keyword + "/" + i));
            }
            return out;
        }

        /** The one type every part allows, or null when none says; throws when they disagree. */
        String type() {
            Set<String> allowed = null;
            List<String> said = new ArrayList<>();
            for (At part : parts) {
                if (part.schema().types() == null) continue;
                String t = part.schema().types().get(0);
                said.add(t + " at " + part.location());
                Set<String> these = t.equals("number") ? Set.of("number", "integer") : Set.of(t);
                if (allowed == null) {
                    allowed = new HashSet<>(these);
                } else {
                    allowed.retainAll(these);
                }
            }
            if (allowed == null) return inferred();
            if (allowed.isEmpty()) {
                throw new Unsatisfiable(location(), "no value has every type required: " + String.join(", ", said));
            }
            return allowed.contains("number") ? "number" : allowed.iterator().next();
        }

        /** The type the keywords imply when no part names one; null when nothing implies one. */
        private String inferred() {
            for (At part : parts) {
                Schema s = part.schema();
                Constraints c = s.constraints();
                if (s.properties() != null || s.required() != null || s.patternProperties() != null
                        || s.additionalProperties() != null || c.minProperties() != null || c.maxProperties() != null) {
                    return "object";
                }
                if (s.items() != null || c.minItems() != null || c.maxItems() != null || c.uniqueItems() != null) {
                    return "array";
                }
                if (c.minLength() != null || c.maxLength() != null || c.pattern() != null
                        || (s.format() != null && Formats.supported(s.format()))) {
                    return "string";
                }
                if (c.minimum() != null || c.maximum() != null || c.exclusiveMinimum() instanceof Number
                        || c.exclusiveMaximum() instanceof Number || c.multipleOf() != null) {
                    return "number";
                }
            }
            return null;
        }

        List<Object> consts() {
            List<Object> out = new ArrayList<>();
            for (At part : parts) {
                Const c = part.schema().constValue();
                if (c != null) out.add(ValueJson.of(c.value()));
            }
            return out;
        }

        List<List<Object>> enums() {
            List<List<Object>> out = new ArrayList<>();
            for (At part : parts) {
                if (part.schema().enumValues() != null) {
                    out.add(part.schema().enumValues().stream().map(ValueJson::of).toList());
                }
            }
            return out;
        }

        long minimum(java.util.function.Function<Constraints, Number> keyword) {
            long out = 0;
            for (At part : parts) {
                Number n = keyword.apply(part.schema().constraints());
                if (n != null) out = Math.max(out, (long) Math.ceil(n.doubleValue()));
            }
            return out;
        }

        long maximum(java.util.function.Function<Constraints, Number> keyword) {
            long out = Long.MAX_VALUE;
            for (At part : parts) {
                Number n = keyword.apply(part.schema().constraints());
                if (n != null) out = Math.min(out, (long) Math.floor(n.doubleValue()));
            }
            return out;
        }

        List<At> with(java.util.function.Predicate<Schema> having) {
            return parts.stream().filter(p -> having.test(p.schema())).toList();
        }
    }

    // --------------------------------------------------------- candidates

    private Object first(Iterator<Object> candidates, Intersection in) {
        if (candidates.hasNext()) return candidates.next();
        throw new Unsatisfiable(in.location(), "no value satisfying every keyword was found among the first "
                + SEARCH + " candidates this generator tries");
    }

    /** Valid values of an intersection, lazily, in a fixed order. */
    private Iterator<Object> candidates(Intersection in, Context context) {
        in.assertionsModelled();
        List<Choice> choices = in.choices();
        if (!choices.isEmpty()) return chosen(in, choices.get(0), context);

        List<Object> consts = in.consts();
        if (!consts.isEmpty()) {
            for (Object other : consts) {
                if (!ValueJson.equal(consts.get(0), other)) {
                    throw new Unsatisfiable(in.location(), "the const values " + ValueJson.write(consts.get(0))
                            + " and " + ValueJson.write(other) + " differ, so no value is both");
                }
            }
            if (!in.accepts(consts.get(0))) {
                throw new Unsatisfiable(in.location(), "the const value " + ValueJson.write(consts.get(0))
                        + " does not satisfy the other keywords that apply to it");
            }
            return Lazy.of(consts.get(0));
        }

        List<List<Object>> enums = in.enums();
        if (!enums.isEmpty()) {
            List<Object> common = new ArrayList<>();
            for (Object value : enums.get(0)) {
                if (enums.stream().allMatch(e -> e.stream().anyMatch(v -> ValueJson.equal(v, value)))) common.add(value);
            }
            if (common.isEmpty()) {
                throw new Unsatisfiable(in.location(), "the enums that apply here have no value in common: "
                        + String.join(" and ", enums.stream().map(ValueJson::write).toList()));
            }
            List<Object> valid = common.stream().filter(in::accepts).toList();
            if (valid.isEmpty()) {
                throw new Unsatisfiable(in.location(), "none of the enum values " + ValueJson.write(common)
                        + " satisfies the other keywords that apply to it");
            }
            return valid.iterator();
        }

        String type = in.type();
        if (type == null) return Lazy.of(ValueJson.NULL);
        return switch (type) {
            case "string" -> strings(in);
            case "integer", "number" -> numbers(in, type.equals("integer"));
            case "boolean" -> Lazy.filter(List.<Object>of(false, true).iterator(), in::accepts);
            case "object" -> objects(in, context);
            case "array" -> arrays(in, context);
            default -> Lazy.filter(Lazy.of(ValueJson.NULL), in::accepts);
        };
    }

    // ------------------------------------------------------------ choices

    /**
     * The first branch, in declared order, whose value is valid; for {@code oneOf}, valid
     * against exactly that one branch. With a discriminator, the discriminating property
     * takes the value the mapping gives the branch.
     */
    private Iterator<Object> chosen(Intersection in, Choice choice, Context context) {
        String keyword = choice.exclusive() ? "oneOf" : "anyOf";
        List<String> reasons = new ArrayList<>();
        boolean allTooDeep = true;
        for (int i = 0; i < choice.branches().size(); i++) {
            At branch = choice.branches().get(i);
            try {
                Intersection with = in.with(branch, Set.of(choice.owner().location()));
                At discriminating = discriminating(choice, branch);
                if (discriminating != null) with = with.with(discriminating, Set.of());
                for (String ref : with.refs) {
                    if (context.count(ref) > recursionDepth) {
                        throw new TooDeep(branch.location(), "branch " + i + " recurses beyond recursionDepth");
                    }
                }
                Iterator<Object> values = candidates(with, context);
                if (!values.hasNext()) {
                    allTooDeep = false;
                    reasons.add("branch " + i + " has no valid value");
                    continue;
                }
                Object value = values.next();
                if (choice.exclusive()) {
                    List<Integer> matching = matching(value, choice);
                    if (matching.size() != 1) {
                        allTooDeep = false;
                        int index = i;
                        reasons.add("the value of branch " + i + ", " + ValueJson.write(value)
                                + ", is also valid against branch(es) "
                                + matching.stream().filter(m -> m != index).toList());
                        continue;
                    }
                    return Lazy.concat(() -> Lazy.of(value),
                            () -> Lazy.filter(values, v -> matching(v, choice).size() == 1));
                }
                return Lazy.concat(() -> Lazy.of(value), () -> values);
            } catch (TooDeep e) {
                reasons.add("branch " + i + ": " + e.reason);
            } catch (Unsatisfiable e) {
                allTooDeep = false;
                reasons.add("branch " + i + ": " + e.reason + " at " + e.location);
            }
        }
        String reason = "no branch of the " + keyword + " yields a value" + (choice.exclusive()
                ? " valid against exactly one branch" : "") + ": " + String.join("; ", reasons);
        if (allTooDeep) throw new TooDeep(choice.owner().location(), reason);
        throw new Unsatisfiable(choice.owner().location(), reason);
    }

    /** The indexes of the branches of a choice a value is valid against. */
    private List<Integer> matching(Object value, Choice choice) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < choice.branches().size(); i++) {
            At b = choice.branches().get(i);
            if (valid(value, b.schema(), b.location(), 0)) out.add(i);
        }
        return out;
    }

    /** The discriminating property of a branch, as a schema requiring its mapped value; or null. */
    private static At discriminating(Choice choice, At branch) {
        Discriminator d = choice.owner().schema().discriminator();
        if (d == null || d.propertyName() == null || branch.schema().ref() == null) return null;
        String ref = branch.schema().ref();
        String value = ref;
        if (d.mapping() != null) {
            for (Map.Entry<String, String> m : d.mapping().entrySet()) {
                String target = m.getValue();
                if (target.equals(ref) || target.equals("#/components/schemas/" + Shapes.escape(ref))) {
                    value = m.getKey();
                    break;
                }
            }
        }
        Schema constant = schema(null, null, null, new Const(value), null, null);
        Schema owner = schema(null, List.of(d.propertyName()), Map.of(d.propertyName(), constant), null, null, null);
        return new At(owner, choice.owner().location() + "/discriminator");
    }

    /** A schema with only the given keywords. */
    private static Schema schema(List<String> types, List<String> required, Map<String, Schema> properties,
                                 Const constant, Schema items, AdditionalProperties additional) {
        return new Schema(null, null, types, false, null, null, constant, null, required, properties, null, items,
                additional, null, null, null, null,
                new Constraints(null, null, null, null, null, null, null, null, null, null, null, null, null),
                Map.of(), Map.of());
    }

    // ------------------------------------------------------------ strings

    private Iterator<Object> strings(Intersection in) {
        long min = in.minimum(Constraints::minLength);
        long max = in.maximum(Constraints::maxLength);
        if (min > max) {
            throw new Unsatisfiable(in.location(), "minLength " + min + " exceeds maxLength " + max);
        }
        List<EcmaPattern> compiled = new ArrayList<>();
        for (At part : in.with(s -> s.constraints().pattern() != null)) {
            EcmaPattern p = pattern(part.schema().constraints().pattern(), part.location());
            if (p.shortestMatch() < 0) {
                throw new Unsatisfiable(part.location(), "pattern " + p.source() + " matches no string of at most "
                        + EcmaPattern.MAX_LENGTH + " code points");
            }
            if (p.shortestMatch() > max) {
                throw new Unsatisfiable(part.location(), "every match of pattern " + p.source() + " is at least "
                        + p.shortestMatch() + " code points long, and maxLength is " + max);
            }
            compiled.add(p);
        }
        if (min > EcmaPattern.MAX_LENGTH) {
            throw new Unsatisfiable(in.location(), "minLength " + min + " is beyond the " + EcmaPattern.MAX_LENGTH
                    + " code points this generator produces");
        }
        int lo = (int) min;
        int hi = (int) Math.min(max, Integer.MAX_VALUE);
        if (!compiled.isEmpty()) {
            Iterator<String> values = Lazy.limit(compiled.get(0).values(lo, hi), SEARCH);
            Iterator<Object> valid = Lazy.filter(Lazy.map(values, v -> (Object) v),
                    v -> compiled.stream().allMatch(p -> p.find((String) v)) && in.accepts(v));
            if (!valid.hasNext()) {
                throw new Unsatisfiable(in.location(), "no string of " + lo + " to " + (max == Long.MAX_VALUE ? "any"
                        : max) + " code points matching " + String.join(" and ", compiled.stream()
                        .map(EcmaPattern::source).toList()) + " was found among the first " + SEARCH
                        + " candidates this generator tries");
            }
            return valid;
        }
        for (At part : in.with(s -> s.format() != null)) {
            String format = part.schema().format();
            if (!Formats.supported(format)) {
                unsupportedFormats.add(part.location() + ": " + format);
                continue;
            }
            Iterator<Object> valid = Lazy.filter(Lazy.map(Formats.values(format, lo, hi), v -> (Object) v),
                    in::accepts);
            if (!valid.hasNext()) {
                throw new Unsatisfiable(part.location(), "this generator has no " + format + " value of " + lo + " to "
                        + (max == Long.MAX_VALUE ? "any" : max) + " code points; its canonical value is "
                        + Formats.canonical(format));
            }
            return valid;
        }
        int length = hi == 0 ? 0 : Math.max(lo, 1);
        return Lazy.filter(Lazy.limit(filler(length), SEARCH), in::accepts);
    }

    /** {@code a}, {@code b}, ... {@code z}, then {@code aa}...: strings of the given length and up. */
    private static Iterator<Object> filler(int length) {
        return new Iterator<>() {
            private char[] next = "a".repeat(length).toCharArray();
            private boolean empty = length == 0;

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public Object next() {
                if (empty) {
                    empty = false;
                    next = new char[]{'a'};
                    return "";
                }
                String out = new String(next);
                int i = next.length - 1;
                while (i >= 0 && next[i] == 'z') next[i--] = 'a';
                if (i < 0) {
                    next = "a".repeat(next.length + 1).toCharArray();
                } else {
                    next[i]++;
                }
                return out;
            }
        };
    }

    private EcmaPattern pattern(String source, String location) {
        EcmaPattern known = patterns.get(source);
        if (known != null) return known;
        try {
            EcmaPattern compiled = EcmaPattern.compile(source);
            patterns.put(source, compiled);
            return compiled;
        } catch (EcmaPattern.Unsupported e) {
            throw new Unsatisfiable(location, "unsatisfiable by this generator: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ numbers

    private Iterator<Object> numbers(Intersection in, boolean integer) {
        Bound lower = null;
        Bound upper = null;
        BigDecimal step = integer ? BigDecimal.ONE : null;
        for (At part : in.parts) {
            Constraints c = part.schema().constraints();
            lower = stricter(lower, c.minimum(), c.exclusiveMinimum(), part.location(), true);
            upper = stricter(upper, c.maximum(), c.exclusiveMaximum(), part.location(), false);
            if (c.multipleOf() != null) {
                BigDecimal m = (BigDecimal) ValueJson.of(c.multipleOf());
                if (m.signum() <= 0) {
                    throw new Unsatisfiable(part.location(), "multipleOf " + ValueJson.number(m) + " is not positive");
                }
                step = step == null ? m : lcm(step, m);
            }
        }
        if (lower != null && upper != null) {
            int cmp = lower.value().compareTo(upper.value());
            if (cmp > 0 || (cmp == 0 && (lower.exclusive() || upper.exclusive()))) {
                throw new Unsatisfiable(in.location(), "the bounds leave no number: " + describe(lower, true) + " and "
                        + describe(upper, false));
            }
        }
        BigDecimal start = start(lower, upper, step);
        if (start == null || !within(start, lower, upper)) {
            throw new Unsatisfiable(in.location(), "no " + (step == null ? "number" : "multiple of "
                    + ValueJson.number(step)) + " lies " + (lower == null ? "" : describe(lower, true))
                    + (lower != null && upper != null ? " and " : "") + (upper == null ? "" : describe(upper, false)));
        }
        BigDecimal unit = step == null ? BigDecimal.ONE : step;
        Bound lo = lower;
        Bound hi = upper;
        BigDecimal first = start;
        Iterator<Object> up = Lazy.filter(Lazy.map(Lazy.range(0, SEARCH), k -> (Object) first.add(unit.multiply(
                BigDecimal.valueOf(k)))), v -> within((BigDecimal) v, lo, hi));
        Iterator<Object> down = Lazy.filter(Lazy.map(Lazy.range(1, SEARCH), k -> (Object) first.subtract(unit.multiply(
                BigDecimal.valueOf(k)))), v -> within((BigDecimal) v, lo, hi));
        return Lazy.filter(Lazy.map(Lazy.limit(Lazy.concat(() -> up, () -> down), SEARCH),
                v -> integer ? (Object) ((BigDecimal) v).setScale(0, RoundingMode.UNNECESSARY) : v), in::accepts);
    }

    /**
     * The first value: 0 when valid; otherwise the smallest valid value at or above the
     * lower bound; otherwise the largest at or below the upper bound.
     */
    private static BigDecimal start(Bound lower, Bound upper, BigDecimal step) {
        if (within(BigDecimal.ZERO, lower, upper)) return BigDecimal.ZERO;
        if (lower != null) {
            if (step != null) return multiple(lower.value(), step, lower.exclusive(), RoundingMode.CEILING);
            if (!lower.exclusive()) return lower.value();
            BigDecimal above = lower.value().setScale(0, RoundingMode.FLOOR).add(BigDecimal.ONE);
            if (upper == null || within(above, null, upper)) return above;
            return lower.value().add(upper.value()).divide(BigDecimal.valueOf(2));
        }
        if (step != null) return multiple(upper.value(), step, upper.exclusive(), RoundingMode.FLOOR);
        if (!upper.exclusive()) return upper.value();
        return upper.value().setScale(0, RoundingMode.CEILING).subtract(BigDecimal.ONE);
    }

    /** The multiple of {@code step} nearest {@code bound} in the direction given, past it when it is excluded. */
    private static BigDecimal multiple(BigDecimal bound, BigDecimal step, boolean exclusive, RoundingMode direction) {
        BigDecimal m = bound.divide(step, 0, direction).multiply(step);
        if (exclusive && m.compareTo(bound) == 0) {
            m = direction == RoundingMode.CEILING ? m.add(step) : m.subtract(step);
        }
        return m;
    }

    private static boolean within(BigDecimal v, Bound lower, Bound upper) {
        if (lower != null) {
            int c = v.compareTo(lower.value());
            if (c < 0 || (c == 0 && lower.exclusive())) return false;
        }
        if (upper != null) {
            int c = v.compareTo(upper.value());
            if (c > 0 || (c == 0 && upper.exclusive())) return false;
        }
        return true;
    }

    /**
     * The stricter of a bound and those one part declares: {@code minimum} with a 3.0
     * boolean {@code exclusiveMinimum}, and a 3.1 numeric {@code exclusiveMinimum}.
     */
    private static Bound stricter(Bound current, Number inclusive, Object exclusive, String location, boolean lower) {
        Bound out = current;
        if (inclusive != null) {
            out = tighter(out, new Bound((BigDecimal) ValueJson.of(inclusive), Boolean.TRUE.equals(exclusive), location),
                    lower);
        }
        if (exclusive instanceof Number n) {
            out = tighter(out, new Bound((BigDecimal) ValueJson.of(n), true, location), lower);
        }
        return out;
    }

    private static Bound tighter(Bound a, Bound b, boolean lower) {
        if (a == null) return b;
        int c = a.value().compareTo(b.value());
        if (c == 0) return a.exclusive() ? a : b;
        return (c > 0) == lower ? a : b;
    }

    private static String describe(Bound b, boolean lower) {
        return (lower ? (b.exclusive() ? "> " : ">= ") : (b.exclusive() ? "< " : "<= ")) + ValueJson.number(b.value())
                + " (" + b.location() + ")";
    }

    /** The least common multiple of two positive decimals, exactly. */
    static BigDecimal lcm(BigDecimal a, BigDecimal b) {
        int scale = Math.max(Math.max(a.scale(), b.scale()), 0);
        BigInteger x = a.setScale(scale, RoundingMode.UNNECESSARY).unscaledValue();
        BigInteger y = b.setScale(scale, RoundingMode.UNNECESSARY).unscaledValue();
        BigInteger lcm = x.divide(x.gcd(y)).multiply(y);
        return new BigDecimal(lcm, scale).stripTrailingZeros();
    }

    // ------------------------------------------------------------- arrays

    private Iterator<Object> arrays(Intersection in, Context context) {
        long min = in.minimum(Constraints::minItems);
        long max = in.maximum(Constraints::maxItems);
        if (min > max) throw new Unsatisfiable(in.location(), "minItems " + min + " exceeds maxItems " + max);
        if (min > SEARCH) {
            throw new Unsatisfiable(in.location(), "minItems " + min + " is more items than this generator produces");
        }
        boolean unique = in.parts.stream().anyMatch(p -> Boolean.TRUE.equals(p.schema().constraints().uniqueItems()));
        int count = (int) (context.variant() == Variant.REQUIRED ? min : Math.min(Math.max(min, 1), max));
        List<At> items = new ArrayList<>();
        for (At part : in.with(s -> s.items() != null)) {
            items.add(new At(part.schema().items(), part.location() + "/items"));
        }
        List<Object> list;
        try {
            list = items(in, items, count, unique, context);
        } catch (TooDeep e) {
            if (count == min) throw e;
            list = items(in, items, (int) min, unique, context);
        }
        return Lazy.filter(Lazy.<Object>of(list), in::accepts);
    }

    private List<Object> items(Intersection in, List<At> items, int count, boolean unique, Context context) {
        if (count == 0) return List.of();
        Context child = context.child(in.refs);
        Intersection each = items.isEmpty() ? null : intersection(items, Set.of(), child);
        Iterator<Object> values = each == null ? Lazy.of(ValueJson.NULL) : candidates(each, child);
        String at = items.isEmpty() ? in.location() + "/items" : items.get(0).location();
        if (!unique) {
            if (!values.hasNext()) throw new Unsatisfiable(at, "no valid item was found");
            Object value = values.next();
            return Collections.nCopies(count, value);
        }
        List<Object> distinct = new ArrayList<>();
        int tried = 0;
        while (distinct.size() < count && tried < SEARCH && values.hasNext()) {
            Object value = values.next();
            tried++;
            if (distinct.stream().noneMatch(d -> ValueJson.equal(d, value))) distinct.add(value);
        }
        if (distinct.size() < count) {
            throw new Unsatisfiable(in.location(), "uniqueItems needs " + count + " distinct items, but "
                    + (values.hasNext() ? "only " + distinct.size() + " were found among the first " + SEARCH
                    + " candidates this generator tries" : "only " + distinct.size()
                    + " distinct value(s) satisfy the item schema"));
        }
        return List.copyOf(distinct);
    }

    // ------------------------------------------------------------ objects

    private Iterator<Object> objects(Intersection in, Context context) {
        Set<String> declared = new LinkedHashSet<>();
        Set<String> required = new LinkedHashSet<>();
        for (At part : in.parts) {
            if (part.schema().properties() != null) declared.addAll(part.schema().properties().keySet());
            if (part.schema().required() != null) required.addAll(part.schema().required());
        }
        List<String> names = new ArrayList<>();
        for (String name : declared) {
            if (context.variant() == Variant.FULL || required.contains(name)) names.add(name);
        }
        for (String name : required) {
            if (!names.contains(name)) names.add(name);
        }
        Context child = context.child(in.refs);
        Map<String, Object> out = new LinkedHashMap<>();
        for (String name : names) {
            boolean isRequired = required.contains(name);
            try {
                Intersection member = member(in, name, child);
                out.put(name, first(candidates(member, child), member));
            } catch (TooDeep e) {
                if (isRequired || context.variant() == Variant.REQUIRED) throw e;
            }
        }
        long minProperties = in.minimum(Constraints::minProperties);
        long maxProperties = in.maximum(Constraints::maxProperties);
        if (out.size() < minProperties) fill(in, out, declared, minProperties, child);
        if (out.size() > maxProperties) {
            throw new Unsatisfiable(in.location(), "the object needs " + out.size() + " member(s) -- "
                    + (context.variant() == Variant.REQUIRED ? "those it requires" : "every one it declares")
                    + " -- but maxProperties is " + maxProperties);
        }
        Map<String, Object> value = Collections.unmodifiableMap(out);
        if (!in.accepts(value)) {
            throw new Unsatisfiable(in.location(), "the object built from its members' values does not satisfy the "
                    + "keywords that apply to it as a whole");
        }
        return Lazy.concat(() -> Lazy.of(value), () -> variants(in, value, child));
    }

    /** The object with one member at a time given its next valid value, for {@code uniqueItems}. */
    private Iterator<Object> variants(Intersection in, Map<String, Object> base, Context child) {
        return Lazy.filter(Lazy.flatMap(base.keySet().iterator(), name -> {
            Iterator<Object> values = candidates(member(in, name, child), child);
            if (values.hasNext()) values.next();
            return Lazy.map(Lazy.limit(values, SEARCH), v -> {
                Map<String, Object> copy = new LinkedHashMap<>(base);
                copy.put(name, v);
                return (Object) Collections.unmodifiableMap(copy);
            });
        }), in::accepts);
    }

    /**
     * Adds members until there are {@code minProperties}: first the declared optional ones,
     * in declaration order; then members named after a {@code patternProperties} pattern;
     * then members {@code additionalProperties} allows, named {@code additional1} and on.
     */
    private void fill(Intersection in, Map<String, Object> out, Set<String> declared, long minProperties,
                      Context child) {
        List<String> candidates = new ArrayList<>(declared);
        for (At part : in.with(s -> s.patternProperties() != null)) {
            for (String regex : part.schema().patternProperties().keySet()) {
                Iterator<String> names = Lazy.limit(pattern(regex, part.location() + "/patternProperties/"
                        + Shapes.escape(regex)).values(1, 64), 64);
                names.forEachRemaining(candidates::add);
            }
        }
        for (int i = 1; i <= minProperties + 1; i++) candidates.add("additional" + i);
        List<String> reasons = new ArrayList<>();
        for (String name : candidates) {
            if (out.size() >= minProperties) return;
            if (out.containsKey(name)) continue;
            try {
                Intersection member = member(in, name, child);
                out.put(name, first(candidates(member, child), member));
            } catch (Unsatisfiable e) {
                reasons.add(name + ": " + e.reason);
            }
        }
        if (out.size() < minProperties) {
            throw new Unsatisfiable(in.location(), "minProperties " + minProperties + " needs more members than can be "
                    + "added" + (reasons.isEmpty() ? "" : ": " + String.join("; ", reasons.subList(0,
                    Math.min(3, reasons.size())))));
        }
    }

    /**
     * Every schema a member of this name must be valid against: in each part, the property
     * it declares and each pattern property its name matches, or else the part's
     * {@code additionalProperties}.
     */
    private Intersection member(Intersection in, String name, Context child) {
        List<At> roots = new ArrayList<>();
        for (At part : in.parts) {
            Schema s = part.schema();
            boolean matched = false;
            if (s.properties() != null && s.properties().containsKey(name)) {
                roots.add(new At(s.properties().get(name), part.location() + "/properties/" + Shapes.escape(name)));
                matched = true;
            }
            if (s.patternProperties() != null) {
                for (Map.Entry<String, Schema> p : s.patternProperties().entrySet()) {
                    String at = part.location() + "/patternProperties/" + Shapes.escape(p.getKey());
                    if (pattern(p.getKey(), at).find(name)) {
                        roots.add(new At(p.getValue(), at));
                        matched = true;
                    }
                }
            }
            AdditionalProperties additional = s.additionalProperties();
            if (!matched && additional != null) {
                if (additional.schema() != null) {
                    roots.add(new At(additional.schema(), part.location() + "/additionalProperties"));
                } else if (Boolean.FALSE.equals(additional.allowed())) {
                    throw new Unsatisfiable(part.location(), "member " + name + " is not allowed here: "
                            + "additionalProperties is false, and this schema neither declares it nor matches it "
                            + "with patternProperties");
                }
            }
        }
        if (roots.isEmpty()) {
            roots.add(new At(schema(null, null, null, null, null, null), in.location() + "/properties/"
                    + Shapes.escape(name)));
        }
        return intersection(roots, Set.of(), child);
    }

    // ---------------------------------------------------------- validation

    private boolean valid(Object value, Schema schema, String location, int depth) {
        if (depth > 64) return false;
        if (schema.literal() != null) return schema.literal();
        if (schema.ref() != null) {
            if (!valid(value, shapes.component(schema.ref()).orElseThrow(),
                    "/components/schemas/" + Shapes.escape(schema.ref()), depth + 1)) {
                return false;
            }
        }
        if (!own(value, schema, location, true, depth)) return false;
        if (schema.allOf() != null) {
            for (int i = 0; i < schema.allOf().size(); i++) {
                if (!valid(value, schema.allOf().get(i), location + "/allOf/" + i, depth + 1)) return false;
            }
        }
        return true;
    }

    /** Whether a value satisfies a schema's own keywords: not its {@code $ref} or {@code allOf}, which are parts of their own. */
    private boolean own(Object value, Schema s, String location, boolean choices, int depth) {
        for (String keyword : s.unmodelled().keySet()) {
            if (UNMODELLED_ASSERTIONS.contains(keyword)) {
                throw new Unsatisfiable(location, "validity depends on " + keyword + ", which the model does not type");
            }
        }
        String type = ValueJson.type(value);
        if (s.types() != null && s.types().stream().noneMatch(t -> t.equals(type)
                || (t.equals("number") && type.equals("integer")))) {
            return false;
        }
        if (s.constValue() != null && !ValueJson.equal(ValueJson.of(s.constValue().value()), value)) return false;
        if (s.enumValues() != null && s.enumValues().stream().noneMatch(e -> ValueJson.equal(ValueJson.of(e), value))) {
            return false;
        }
        Constraints c = s.constraints();
        boolean ok = switch (value) {
            case String string -> string(string, s, c, location);
            case BigDecimal number -> number(number, c);
            case List<?> list -> array(list, s, c, location, depth);
            case Map<?, ?> map -> object(map, s, c, location, depth);
            default -> true;
        };
        if (!ok) return false;
        if (choices && s.oneOf() != null) {
            int count = 0;
            for (int i = 0; i < s.oneOf().size(); i++) {
                if (valid(value, s.oneOf().get(i), location + "/oneOf/" + i, depth + 1)) count++;
            }
            if (count != 1) return false;
        }
        if (choices && s.anyOf() != null) {
            boolean any = false;
            for (int i = 0; i < s.anyOf().size() && !any; i++) {
                any = valid(value, s.anyOf().get(i), location + "/anyOf/" + i, depth + 1);
            }
            if (!any) return false;
        }
        return true;
    }

    private boolean string(String value, Schema s, Constraints c, String location) {
        int length = value.codePointCount(0, value.length());
        if (c.minLength() != null && length < c.minLength().doubleValue()) return false;
        if (c.maxLength() != null && length > c.maxLength().doubleValue()) return false;
        if (c.pattern() != null) return pattern(c.pattern(), location).find(value);
        return s.format() == null || Formats.accepts(s.format(), value);
    }

    private static boolean number(BigDecimal value, Constraints c) {
        Bound lower = stricter(null, c.minimum(), c.exclusiveMinimum(), "", true);
        Bound upper = stricter(null, c.maximum(), c.exclusiveMaximum(), "", false);
        if (!within(value, lower, upper)) return false;
        if (c.multipleOf() == null) return true;
        return value.remainder((BigDecimal) ValueJson.of(c.multipleOf())).signum() == 0;
    }

    private boolean array(List<?> list, Schema s, Constraints c, String location, int depth) {
        if (c.minItems() != null && list.size() < c.minItems().doubleValue()) return false;
        if (c.maxItems() != null && list.size() > c.maxItems().doubleValue()) return false;
        if (Boolean.TRUE.equals(c.uniqueItems())) {
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    if (ValueJson.equal(list.get(i), list.get(j))) return false;
                }
            }
        }
        if (s.items() != null) {
            for (Object item : list) {
                if (!valid(item, s.items(), location + "/items", depth + 1)) return false;
            }
        }
        return true;
    }

    private boolean object(Map<?, ?> map, Schema s, Constraints c, String location, int depth) {
        if (c.minProperties() != null && map.size() < c.minProperties().doubleValue()) return false;
        if (c.maxProperties() != null && map.size() > c.maxProperties().doubleValue()) return false;
        if (s.required() != null && !map.keySet().containsAll(s.required())) return false;
        for (Map.Entry<?, ?> member : map.entrySet()) {
            String name = (String) member.getKey();
            boolean matched = false;
            if (s.properties() != null && s.properties().containsKey(name)) {
                matched = true;
                if (!valid(member.getValue(), s.properties().get(name), location + "/properties/"
                        + Shapes.escape(name), depth + 1)) {
                    return false;
                }
            }
            if (s.patternProperties() != null) {
                for (Map.Entry<String, Schema> p : s.patternProperties().entrySet()) {
                    String at = location + "/patternProperties/" + Shapes.escape(p.getKey());
                    if (pattern(p.getKey(), at).find(name)) {
                        matched = true;
                        if (!valid(member.getValue(), p.getValue(), at, depth + 1)) return false;
                    }
                }
            }
            AdditionalProperties additional = s.additionalProperties();
            if (!matched && additional != null) {
                if (Boolean.FALSE.equals(additional.allowed())) return false;
                if (additional.schema() != null && !valid(member.getValue(), additional.schema(),
                        location + "/additionalProperties", depth + 1)) {
                    return false;
                }
            }
        }
        return true;
    }
}
