package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.model.Constraints;
import com.arc_e_tect.gradle.apionly.transcriberj.model.MediaType;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Operation;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Parameter;
import com.arc_e_tect.gradle.apionly.transcriberj.model.RequestBody;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Response;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Schema;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.GeneratedClass;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Origin;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * For every constraint a contract declares on request input, a case: a request that is
 * valid except for exactly that one constraint, paired with the response the contract
 * declares for an invalid request; or the one reason there is none.
 *
 * <p>Each case starts from the smallest valid request, and changes one thing. A
 * constraint of an optional parameter or member is reached by adding it first, with a
 * valid value. A case is kept only when {@link Faults} finds exactly the one fault it is
 * about: the keyword, at the pointer, declared by the schema the constraint is in. When
 * no value breaks the constraint alone, there is no case, and the constraint is reported
 * as not isolatable, naming the keyword that is broken with it.
 */
final class InvalidRequests {

    /** Why a constraint on request input has no case: a closed list. */
    enum Reason {
        /** Every violating value found breaks another keyword too. */
        NOT_ISOLATABLE("not isolatable"),
        /** Every violating value of a path parameter would reach another route. */
        CHANGES_ROUTE("changes the route"),
        /** The keyword is one the model does not type. */
        KEYWORD_NOT_MODELLED("keyword not modelled"),
        /** No value is generated for the parameter yet. */
        PARAMETER_STYLE_NOT_SUPPORTED("parameter style not supported"),
        /** The constraint is inside a construct generated code degrades. */
        DEGRADED_CONSTRUCT("inside a degraded construct"),
        /** The format's validation is not switched on. */
        FORMAT_VALIDATION_OFF("format validation not switched on"),
        /** A pattern beside the format decides what is valid. */
        PATTERN_OVERRIDES_FORMAT("pattern overrides format"),
        /** The operation declares no response for an invalid request. */
        NO_INVALID_REQUEST_STATUS("operation declares no invalid-request status"),
        /** The strict rule for unknown members is turned off. */
        STRICTNESS_OFF("strictness turned off"),
        /** Reached only by following a reference more often than recursionDepth allows. */
        BEYOND_RECURSION_DEPTH("beyond recursionDepth"),
        /** Every parameter value is a string, and none is null. */
        NOT_EXPRESSIBLE_IN_PARAMETER("not expressible in a parameter"),
        /** The format's validation is switched on, but no check for it exists. */
        FORMAT_NOT_SUPPORTED("format not supported"),
        /** There is no valid request to change. */
        NO_VALID_BASELINE("no valid baseline"),
        /** No value can violate the keyword. */
        ASSERTS_NOTHING("asserts nothing"),
        /** No rule derives a violation of the keyword. */
        NO_VIOLATION_RULE("no violation rule"),
        /** The constraint is in a media type no case is derived for. */
        MEDIA_TYPE_NOT_USED("media type not used");

        final String label;

        Reason(String label) {
            this.label = label;
        }
    }

    /** One constraint on request input, and what covers it. */
    static final class Constraint {
        final String in;
        final String name;
        final String mediaType;
        final String pointer;
        final String keyword;
        final String schemaLocation;
        final List<Draft> drafts = new ArrayList<>();
        Reason reason;
        String detail;
        /** The constraint whose cases and reason this one shares: a 3.0 boolean exclusive bound's. */
        Constraint sharing;
        Node node;
        Schema declaring;

        Constraint(String in, String name, String mediaType, String pointer, String keyword, String schemaLocation) {
            this.in = in;
            this.name = name;
            this.mediaType = mediaType;
            this.pointer = pointer;
            this.keyword = keyword;
            this.schemaLocation = schemaLocation;
        }

        /** The ids of the cases that cover it, once they are final. */
        List<String> cases() {
            return drafts.stream().map(d -> d.id).toList();
        }

        void uncovered(Reason why, String because) {
            if (reason == null && drafts.isEmpty()) {
                reason = why;
                detail = because;
            }
        }
    }

    /**
     * One case.
     *
     * @param id             readable, unique within the operation, stable across runs
     * @param description    one sentence saying what is wrong with the request
     * @param in             {@code path}, {@code query}, {@code header} or {@code body}
     * @param name           the parameter's name, or null in the body
     * @param pointer        the JSON pointer into the body, or null for a parameter
     * @param keyword        the violated keyword, as the contract spells it
     * @param request        the violating request
     * @param baseline       the valid request it differs from in the one place the fault is
     * @param status         the declared invalid-request status
     * @param contentTypes   the declared response's content types
     * @param bodyClass      the simple name of the class of the declared response's body, or null
     * @param representative whether it is the first case of its operation
     */
    record Case(String id, String description, String in, String name, String pointer, String keyword,
                ValidRequests.Request request, ValidRequests.Request baseline, int status, List<String> contentTypes,
                String bodyClass, boolean representative) {
    }

    /** A case before its id is final: ids are made unique in canonical order. */
    static final class Draft {
        String id;
        final String description;
        final String in;
        final String name;
        final String pointer;
        final String keyword;
        final ValidRequests.Request request;
        final ValidRequests.Request baseline;
        final Expected expected;

        Draft(String id, String description, String in, String name, String pointer, String keyword,
              ValidRequests.Request request, ValidRequests.Request baseline, Expected expected) {
            this.id = id;
            this.description = description;
            this.in = in;
            this.name = name;
            this.pointer = pointer;
            this.keyword = keyword;
            this.request = request;
            this.baseline = baseline;
            this.expected = expected;
        }
    }

    /**
     * What one operation gives.
     *
     * @param operation   the operation
     * @param location    its JSON pointer
     * @param cases       its cases, in canonical order
     * @param constraints every constraint on its request input, in the order they were found
     * @param declared    whether it declares the invalid-request status
     */
    record Result(Operation operation, String location, List<Case> cases, List<Constraint> constraints,
                  boolean declared) {

        /** Whether it has constrained input but declares no invalid-request status: the S3 gap. */
        boolean gap() {
            return !declared && !constraints.isEmpty();
        }
    }

    /** Where a value stands in the request, and the schemas it must satisfy there. */
    final class Node {
        final String in;
        final String name;
        final String mediaType;
        final String pointer;
        final List<Faults.At> roots;
        final List<Faults.At> parts;
        final Node parent;
        /** The member it is of its parent object, or null for an item or the root. */
        final String member;
        /** The components each enclosing value referred to, so that recursion is counted. */
        final List<Set<String>> ancestors;
        /** Whether it is a branch of a choice: the object the choice is part of decides strictness. */
        final boolean branch;
        Reason flag;
        String flagDetail;

        Node(String in, String name, String mediaType, String pointer, List<Faults.At> roots, Node parent,
             String member, List<Set<String>> ancestors, boolean branch) {
            this.in = in;
            this.name = name;
            this.mediaType = mediaType;
            this.pointer = pointer;
            this.roots = roots;
            this.parts = Faults.parts(shapes, roots);
            this.parent = parent;
            this.member = member;
            this.ancestors = ancestors;
            this.branch = branch;
        }

        /** The steps from the root value to this one: member names and item indexes. */
        List<Object> path() {
            List<Object> out = new ArrayList<>();
            for (Node n = this; n.parent != null; n = n.parent) {
                if (n.branch) continue;
                out.add(0, n.member != null ? n.member : (Object) 0);
            }
            return out;
        }

        String label() {
            return in.equals(BODY) ? dotted(pointer) : "`" + name + "`";
        }
    }

    static final String PATH = "path";
    static final String QUERY = "query";
    static final String HEADER = "header";
    static final String BODY = "body";

    /** The keywords a case can be about, in canonical order. */
    static final List<String> KEYWORD_ORDER = List.of("required", "type", "null", "enum", "const", "minLength",
            "maxLength", "pattern", "format", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
            "multipleOf", "minItems", "maxItems", "uniqueItems", "minProperties", "maxProperties",
            "additionalProperties");

    private static final List<String> LOCATION_ORDER = List.of(PATH, QUERY, HEADER, BODY);

    /** The formats JSON Schema 2020-12 defines: for these, a missing pattern is worth a recommendation. */
    static final List<String> WELL_KNOWN_FORMATS = List.of("date-time", "date", "time", "duration", "email",
            "idn-email", "hostname", "idn-hostname", "ipv4", "ipv6", "uri", "uri-reference", "iri", "iri-reference",
            "uuid", "uri-template", "json-pointer", "relative-json-pointer", "regex");

    /** The name of the member an unknown-member case adds, unless the object declares or matches it. */
    static final String UNKNOWN_MEMBER = "unexpectedMember";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");
    private static final Pattern JSON_NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");
    private static final Object MISSING = new Object();
    private static final int CANDIDATES = 200;

    private final Shapes shapes;
    private final CoreClassNames names;
    private final ValidValues values;
    private final ValidRequests requests;
    private final Settings settings;
    private final Faults faults;
    private final int status;
    private final Map<String, EcmaPattern> patterns = new java.util.HashMap<>();
    private final Map<String, String> formatRecommendations = new LinkedHashMap<>();
    private final Set<String> warnings = new LinkedHashSet<>();

    InvalidRequests(Shapes shapes, CoreClassNames names, ValidValues values,
                    ValidRequests requests, Settings settings) {
        this.shapes = shapes;
        this.names = names;
        this.values = values;
        this.requests = requests;
        this.settings = settings;
        this.faults = new Faults(shapes, settings.strictRequests());
        this.status = Integer.parseInt(settings.invalidRequestStatus());
    }

    /** Every format recommendation made, by location: the format with no pattern beside it. */
    Map<String, String> formatRecommendations() {
        return Collections.unmodifiableMap(formatRecommendations);
    }

    /** Every warning, in the order it was first made. */
    Set<String> warnings() {
        return Collections.unmodifiableSet(warnings);
    }

    // --------------------------------------------------------------- walk

    /** The cases of one operation, and the coverage of every constraint on its request input. */
    Result derive(Operation operation) {
        String location = CoreClassNames.operationLocation(operation);
        List<Constraint> constraints = new ArrayList<>();
        for (ValidRequests.Located located : requests.parameters(operation)) {
            parameter(located, constraints);
        }
        List<MediaType> json = new ArrayList<>();
        RequestBody body = operation.requestBody();
        if (body != null && body.content() != null && !body.content().isEmpty()) {
            String at = ValidRequests.bodyLocation(operation);
            if (body.required() != null) {
                Constraint c = new Constraint(BODY, null, null, "", "required", at);
                if (!body.required()) c.uncovered(Reason.ASSERTS_NOTHING, "required: false");
                constraints.add(c);
            }
            json.addAll(body.content().stream().filter(m -> ValidRequests.isJson(m.contentType())).toList());
            for (MediaType m : body.content()) {
                if (m.schema() == null) continue;
                String schemaAt = at + "/content/" + Shapes.escape(m.contentType()) + "/schema";
                Node root = new Node(BODY, null, m.contentType(), "", List.of(new Faults.At(m.schema(), schemaAt)),
                        null, null, List.of(), false);
                if (!ValidRequests.isJson(m.contentType())) {
                    root.flag = Reason.MEDIA_TYPE_NOT_USED;
                    root.flagDetail = m.contentType() + " is not JSON; cases are derived for JSON bodies only";
                }
                walk(root, constraints);
            }
        }
        for (Constraint c : constraints) {
            if (c.node != null && c.node.flag != null) c.uncovered(c.node.flag, c.node.flagDetail);
            formatAdvice(c);
        }

        // The gap is the reason of every constraint that has none of its own.
        Response declared = declaredResponse(operation);
        if (declared == null) {
            for (Constraint c : constraints) {
                c.uncovered(Reason.NO_INVALID_REQUEST_STATUS, "declare a " + status + " response");
            }
            return new Result(operation, location, List.of(), constraints, false);
        }
        List<Draft> cases = new ArrayList<>();
        Expected expected = expected(operation, declared);
        for (Constraint c : constraints) {
            if (c.reason != null || c.sharing != null) continue;
            deriveCase(operation, c, json, expected, cases);
        }
        for (Constraint c : constraints) {
            if (c.sharing != null) {
                c.drafts.addAll(c.sharing.drafts);
                c.reason = c.sharing.reason;
                c.detail = c.sharing.detail;
            }
        }
        return new Result(operation, location, order(cases), constraints, true);
    }

    private void parameter(ValidRequests.Located located, List<Constraint> constraints) {
        Parameter p = located.parameter();
        String in = p.in() == null ? "unknown" : p.in();
        String unsupported = requests.unsupported(p);
        if (p.required() != null) {
            Constraint c = new Constraint(in, p.name(), null, "", "required", located.location());
            if (!p.required()) {
                c.uncovered(Reason.ASSERTS_NOTHING, "required: false");
            } else if (unsupported != null) {
                c.uncovered(Reason.PARAMETER_STYLE_NOT_SUPPORTED, unsupported);
            } else if (in.equals(PATH)) {
                c.uncovered(Reason.CHANGES_ROUTE, "a path without the parameter is a different route");
            }
            constraints.add(c);
        }
        if (p.schema() == null) {
            if (p.other().containsKey("content")) {
                Constraint c = new Constraint(in, p.name(), null, "", "content", located.location());
                c.uncovered(Reason.PARAMETER_STYLE_NOT_SUPPORTED, unsupported);
                constraints.add(c);
            }
            return;
        }
        Node root = new Node(in, p.name(), null, "", List.of(new Faults.At(p.schema(), located.location() + "/schema")),
                null, null, List.of(), false);
        if (unsupported != null) {
            root.flag = Reason.PARAMETER_STYLE_NOT_SUPPORTED;
            root.flagDetail = unsupported;
        }
        walk(root, constraints);
    }

    private void walk(Node node, List<Constraint> into) {
        Set<String> refs = Faults.refs(shapes, node.roots);
        if (node.flag == null) {
            for (String ref : refs) {
                long count = node.ancestors.stream().filter(s -> s.contains(ref)).count();
                if (count > settings.recursionDepth()) {
                    node.flag = Reason.BEYOND_RECURSION_DEPTH;
                    node.flagDetail = "reached by following the reference to component " + ref + " more than "
                            + "recursionDepth (" + settings.recursionDepth() + ") times";
                }
            }
        }
        boolean beyond = node.flag == Reason.BEYOND_RECURSION_DEPTH;
        if (node.flag == null) {
            for (Faults.At part : node.parts) {
                List<Schema> choice = part.schema().oneOf() != null ? part.schema().oneOf() : part.schema().anyOf();
                if (choice != null && choice.stream().anyMatch(b -> b.ref() == null)) {
                    node.flag = Reason.DEGRADED_CONSTRUCT;
                    node.flagDetail = (part.schema().oneOf() != null ? "oneOf" : "anyOf") + " with an inline branch at "
                            + part.location() + "; name the branches in the specification so that each becomes a component";
                }
            }
        }
        if (node.flag == null) {
            for (Faults.At part : node.parts) {
                for (String keyword : part.schema().unmodelled().keySet()) {
                    if (node.flag == null && Faults.UNMODELLED_ASSERTIONS.contains(keyword)) {
                        node.flag = Reason.KEYWORD_NOT_MODELLED;
                        node.flagDetail = "validity there depends on " + keyword + " at " + part.location()
                                + ", which the model does not type";
                    }
                }
            }
        }
        for (Faults.At part : node.parts) keywords(node, part, into);
        if (!node.branch && Faults.objectLike(node.parts) && !Faults.declaresOpenness(node.parts)) {
            Constraint c = add(node, part0(node), "additionalProperties", node.pointer, into);
            if (!settings.strictRequests()) c.uncovered(Reason.STRICTNESS_OFF, "strictRequests is false");
        }
        if (beyond) return;
        List<Set<String>> ancestors = new ArrayList<>(node.ancestors);
        ancestors.add(refs);
        List<Set<String>> childAncestors = List.copyOf(ancestors);

        Set<String> declared = new LinkedHashSet<>();
        for (Faults.At part : node.parts) {
            if (part.schema().properties() != null) declared.addAll(part.schema().properties().keySet());
        }
        for (String name : declared) {
            member(node, name, childAncestors, into);
        }
        for (Faults.At part : node.parts) {
            if (part.schema().patternProperties() == null) continue;
            for (String regex : part.schema().patternProperties().keySet()) {
                String name = matchingName(regex, declared);
                if (name == null) continue;
                member(node, name, childAncestors, into);
            }
        }
        for (Faults.At part : node.parts) {
            if (part.schema().additionalProperties() != null && part.schema().additionalProperties().schema() != null) {
                member(node, additionalName(node, declared), childAncestors, into);
                break;
            }
        }
        List<Faults.At> items = new ArrayList<>();
        for (Faults.At part : node.parts) {
            if (part.schema().items() != null) items.add(new Faults.At(part.schema().items(), part.location() + "/items"));
        }
        if (!items.isEmpty()) {
            Node child = new Node(node.in, node.name, node.mediaType, node.pointer + "/0", items, node, null,
                    childAncestors, false);
            inherit(node, child);
            walk(child, into);
        }
        for (Faults.At part : node.parts) {
            String keyword = part.schema().oneOf() != null ? "oneOf" : part.schema().anyOf() != null ? "anyOf" : null;
            if (keyword == null) continue;
            List<Schema> branches = keyword.equals("oneOf") ? part.schema().oneOf() : part.schema().anyOf();
            for (int i = 0; i < branches.size(); i++) {
                Node child = new Node(node.in, node.name, node.mediaType, node.pointer,
                        List.of(new Faults.At(branches.get(i), part.location() + "/" + keyword + "/" + i)), node, null,
                        node.ancestors, true);
                inherit(node, child);
                if (child.flag == null) {
                    child.flag = Reason.NOT_ISOLATABLE;
                    child.flagDetail = "inside a branch of the " + keyword + " at " + part.location()
                            + ": a violation there fails the " + keyword + ", conflicting keyword " + keyword;
                }
                walk(child, into);
            }
        }
    }

    private static Faults.At part0(Node node) {
        return node.parts.get(0);
    }

    private static void inherit(Node parent, Node child) {
        if (parent.flag != null && parent.flag != Reason.BEYOND_RECURSION_DEPTH) {
            child.flag = parent.flag;
            child.flagDetail = parent.flagDetail;
        }
    }

    private void member(Node node, String name, List<Set<String>> ancestors, List<Constraint> into) {
        List<Faults.At> roots = memberRoots(node, name);
        if (roots.isEmpty()) return;
        Node child = new Node(node.in, node.name, node.mediaType, node.pointer + "/" + Shapes.escape(name), roots,
                node, name, ancestors, false);
        inherit(node, child);
        walk(child, into);
    }

    /** Every schema a member of this name must satisfy, as the valid-value generator finds them. */
    private List<Faults.At> memberRoots(Node node, String name) {
        List<Faults.At> roots = new ArrayList<>();
        for (Faults.At part : node.parts) {
            Schema s = part.schema();
            boolean matched = false;
            if (s.properties() != null && s.properties().containsKey(name)) {
                roots.add(new Faults.At(s.properties().get(name), part.location() + "/properties/" + Shapes.escape(name)));
                matched = true;
            }
            if (s.patternProperties() != null) {
                for (Map.Entry<String, Schema> p : s.patternProperties().entrySet()) {
                    if (find(p.getKey(), name)) {
                        roots.add(new Faults.At(p.getValue(), part.location() + "/patternProperties/"
                                + Shapes.escape(p.getKey())));
                        matched = true;
                    }
                }
            }
            if (!matched && s.additionalProperties() != null && s.additionalProperties().schema() != null) {
                roots.add(new Faults.At(s.additionalProperties().schema(), part.location() + "/additionalProperties"));
            }
        }
        return roots;
    }

    /** A member name the pattern matches and no property declares, or null when none is found. */
    private String matchingName(String regex, Set<String> declared) {
        EcmaPattern p = pattern(regex);
        if (p == null) return null;
        Iterator<String> names = Lazy.limit(p.values(1, 64), 64);
        while (names.hasNext()) {
            String name = names.next();
            if (!declared.contains(name)) return name;
        }
        return null;
    }

    private String additionalName(Node node, Set<String> declared) {
        for (int i = 1; ; i++) {
            String name = "additionalMember" + (i == 1 ? "" : i);
            if (!declared.contains(name) && !matchesAPattern(node, name)) return name;
        }
    }

    private boolean matchesAPattern(Node node, String name) {
        for (Faults.At part : node.parts) {
            if (part.schema().patternProperties() == null) continue;
            for (String regex : part.schema().patternProperties().keySet()) {
                if (find(regex, name)) return true;
            }
        }
        return false;
    }

    /** The constraints one schema declares at a node. */
    private void keywords(Node node, Faults.At part, List<Constraint> into) {
        Schema s = part.schema();
        if (s.literal() != null) return;
        Constraints c = s.constraints();
        if (s.types() != null) add(node, part, "type", node.pointer, into);
        if (s.constValue() != null) add(node, part, "const", node.pointer, into);
        if (s.enumValues() != null) add(node, part, "enum", node.pointer, into);
        vacuous(add(node, part, "minLength", c.minLength(), into), c.minLength());
        if (c.maxLength() != null) add(node, part, "maxLength", node.pointer, into);
        if (c.pattern() != null) add(node, part, "pattern", node.pointer, into);
        if (s.format() != null) add(node, part, "format", node.pointer, into);
        Constraint minimum = c.minimum() == null ? null : add(node, part, "minimum", node.pointer, into);
        Constraint maximum = c.maximum() == null ? null : add(node, part, "maximum", node.pointer, into);
        exclusive(node, part, "exclusiveMinimum", c.exclusiveMinimum(), minimum, into);
        exclusive(node, part, "exclusiveMaximum", c.exclusiveMaximum(), maximum, into);
        if (c.multipleOf() != null) add(node, part, "multipleOf", node.pointer, into);
        vacuous(add(node, part, "minItems", c.minItems(), into), c.minItems());
        if (c.maxItems() != null) add(node, part, "maxItems", node.pointer, into);
        if (c.uniqueItems() != null) {
            Constraint u = add(node, part, "uniqueItems", node.pointer, into);
            if (!c.uniqueItems()) u.uncovered(Reason.ASSERTS_NOTHING, "uniqueItems: false");
        }
        vacuous(add(node, part, "minProperties", c.minProperties(), into), c.minProperties());
        if (c.maxProperties() != null) add(node, part, "maxProperties", node.pointer, into);
        if (s.required() != null) {
            if (s.required().isEmpty()) {
                add(node, part, "required", node.pointer, into).uncovered(Reason.ASSERTS_NOTHING, "required: []");
            }
            for (String name : new LinkedHashSet<>(s.required())) {
                add(node, part, "required", node.pointer + "/" + Shapes.escape(name), into);
            }
        }
        if (s.additionalProperties() != null && Boolean.FALSE.equals(s.additionalProperties().allowed())) {
            add(node, part, "additionalProperties", node.pointer, into);
        }
        for (String keyword : List.of("oneOf", "anyOf")) {
            List<Schema> branches = keyword.equals("oneOf") ? s.oneOf() : s.anyOf();
            if (branches == null) continue;
            Constraint choice = add(node, part, keyword, node.pointer, into);
            if (branches.stream().anyMatch(b -> b.ref() == null)) {
                choice.uncovered(Reason.DEGRADED_CONSTRUCT, keyword + " with an inline branch");
            } else {
                choice.uncovered(Reason.NO_VIOLATION_RULE, keyword + " composes schemas rather than constraining a value");
            }
        }
        for (String keyword : s.unmodelled().keySet()) {
            if (Faults.UNMODELLED_ASSERTIONS.contains(keyword)) {
                add(node, part, keyword, node.pointer, into).uncovered(Reason.KEYWORD_NOT_MODELLED,
                        keyword + " is not typed by the model");
            }
        }
    }

    private Constraint add(Node node, Faults.At part, String keyword, Number value, List<Constraint> into) {
        return value == null ? null : add(node, part, keyword, node.pointer, into);
    }

    private static void vacuous(Constraint c, Number value) {
        if (c != null && value.doubleValue() <= 0) c.uncovered(Reason.ASSERTS_NOTHING, c.keyword + ": 0");
    }

    private void exclusive(Node node, Faults.At part, String keyword, Object value, Constraint bound,
                           List<Constraint> into) {
        if (value == null) return;
        Constraint c = add(node, part, keyword, node.pointer, into);
        if (value instanceof Boolean b) {
            if (!b || bound == null) {
                c.uncovered(Reason.ASSERTS_NOTHING, keyword + ": " + b + (b ? " without the bound it modifies" : ""));
            } else {
                c.sharing = bound;
            }
        }
    }

    private Constraint add(Node node, Faults.At part, String keyword, String pointer, List<Constraint> into) {
        Constraint c = new Constraint(node.in, node.in.equals(BODY) ? null : node.name, node.in.equals(BODY)
                ? node.mediaType : null, pointer, keyword, part.location());
        c.node = node;
        c.declaring = part.schema();
        into.add(c);
        return c;
    }

    /** The format reasons and recommendations, which do not depend on whether a case can be derived. */
    private void formatAdvice(Constraint c) {
        if (!c.keyword.equals("format") || c.node == null) return;
        String format = c.declaring.format();
        boolean patterned = c.node.parts.stream().anyMatch(p -> p.schema().constraints().pattern() != null);
        if (!patterned && WELL_KNOWN_FORMATS.contains(format)) {
            formatRecommendations.putIfAbsent(c.schemaLocation, format);
        }
        if (!settings.validateFormats().contains(format)) {
            c.uncovered(Reason.FORMAT_VALIDATION_OFF, "validateFormats does not name " + format);
        } else if (patterned) {
            warnings.add(c.schemaLocation + ": format " + format + " is ignored, since a pattern applies there too "
                    + "and the pattern wins; no format case is derived");
            c.uncovered(Reason.PATTERN_OVERRIDES_FORMAT, "a pattern applies beside format " + format);
        } else if (!Formats.supported(format)) {
            c.uncovered(Reason.FORMAT_NOT_SUPPORTED, "no check exists for format " + format);
        }
    }

    // ----------------------------------------------------------- response

    /** What every case of an operation expects. */
    record Expected(List<String> contentTypes, String bodyClass) {
    }

    private Response declaredResponse(Operation operation) {
        if (operation.responses() == null) return null;
        for (Response r : operation.responses()) {
            if (settings.invalidRequestStatus().equals(r.status())) return r;
        }
        return null;
    }

    private Expected expected(Operation operation, Response response) {
        List<String> types = response.content() == null ? List.of()
                : response.content().stream().map(MediaType::contentType).toList();
        String bodyClass = null;
        if (response.content() != null && !response.content().isEmpty() && response.content().get(0).schema() != null) {
            MediaType first = response.content().get(0);
            Schema schema = first.schema();
            java.util.Optional<GeneratedClass> generated;
            if (schema.ref() != null) {
                generated = names.component(Origin.SCHEMA, schema.ref());
            } else if (response.reference() != null) {
                generated = names.component(Origin.RESPONSE, response.reference().name());
            } else {
                generated = names.inline(CoreClassNames.operationLocation(operation) + "/responses/"
                        + Shapes.escape(response.status()) + "/content/" + Shapes.escape(first.contentType())
                        + "/schema");
            }
            bodyClass = generated.map(GeneratedClass::simpleName).orElse(null);
        }
        return new Expected(types, bodyClass);
    }

    // --------------------------------------------------------- derivation

    /** A violating value, and what the case says about it. */
    private record Candidate(Object value, String description) {
    }

    private void deriveCase(Operation operation, Constraint c, List<MediaType> json, Expected expected,
                            List<Draft> cases) {
        if (c.in.equals(BODY) && c.node == null) {
            if (!Boolean.TRUE.equals(operation.requestBody().required())) return;
            ValidRequests.Request noBody;
            ValidRequests.Request withBody;
            try {
                withBody = requests.request(operation, ValidRequests.Kind.REQUIRED);
                noBody = new ValidRequests.Request(withBody.method(), withBody.pathTemplate(), withBody.pathValues(),
                        withBody.query(), withBody.headers(), null, null);
            } catch (ValidValues.Unsatisfiable | Shapes.Unrepresentable e) {
                c.uncovered(Reason.NO_VALID_BASELINE, why(e));
                return;
            }
            String unsure = unverifiable(operation, withBody);
            if (unsure != null) {
                c.uncovered(Reason.NO_VALID_BASELINE, unsure);
                return;
            }
            cases.add(newCase(c, "body-required", "no body, though the body is required", BODY, null, "",
                    "required", noBody, withBody, expected));
            return;
        }
        if (!c.in.equals(BODY) && c.node == null) {
            parameterRequired(operation, c, expected, cases);
            return;
        }
        ValidRequests.Request baseline;
        MediaType media = c.in.equals(BODY) ? json.stream().filter(m -> m.contentType().equals(c.mediaType))
                .findFirst().orElse(null) : null;
        try {
            baseline = requests.request(operation, ValidRequests.Kind.REQUIRED, media);
        } catch (ValidValues.Unsatisfiable | Shapes.Unrepresentable e) {
            c.uncovered(Reason.NO_VALID_BASELINE, why(e));
            return;
        }
        String unsure = unverifiable(operation, baseline);
        if (unsure != null) {
            c.uncovered(Reason.NO_VALID_BASELINE, unsure);
            return;
        }
        try {
            if (c.in.equals(BODY)) {
                body(operation, c, baseline, json.size() > 1, expected, cases);
            } else {
                parameterValue(operation, c, baseline, expected, cases);
            }
        } catch (Faults.Unevaluable e) {
            c.uncovered(Reason.KEYWORD_NOT_MODELLED, "validity there depends on " + e.keyword + " at " + e.location
                    + ", which the model does not type");
        }
    }

    private static String uncheckable(Faults.Fault f) {
        return "the valid request holds a value at " + pointerOrRoot(f.pointer()) + " for the format "
                + "declared at " + f.schemaLocation() + ", which no check exists for, so it cannot be vouched for";
    }

    /** Why a valid request cannot be vouched for, or null when it can: a value of a format no check exists for. */
    private String unverifiable(Operation operation, ValidRequests.Request request) {
        for (ValidRequests.Located located : requests.parameters(operation)) {
            Parameter p = located.parameter();
            if (p.schema() == null || p.in() == null) continue;
            String value = parameterValue(request, p.in(), p.name(), operation);
            if (value == null) continue;
            List<Faults.At> roots = List.of(new Faults.At(p.schema(), located.location() + "/schema"));
            for (Faults.Fault f : faults.of(convert(value, Faults.parts(shapes, roots)), roots)) {
                if (!f.checked()) return "parameter " + p.name() + ": " + uncheckable(f);
            }
        }
        if (request.contentType() == null || request.body() == null) return null;
        MediaType media = operation.requestBody().content().stream()
                .filter(m -> m.contentType().equals(request.contentType())).findFirst().orElse(null);
        if (media == null || media.schema() == null) return null;
        List<Faults.At> roots = List.of(new Faults.At(media.schema(), ValidRequests.bodyLocation(operation)
                + "/content/" + Shapes.escape(media.contentType()) + "/schema"));
        for (Faults.Fault f : faults.of(request.body(), roots)) {
            if (!f.checked()) return uncheckable(f);
        }
        return null;
    }

    private static String why(RuntimeException e) {
        if (e instanceof ValidValues.Unsatisfiable u) return u.reason + " at " + u.location;
        Shapes.Unrepresentable r = (Shapes.Unrepresentable) e;
        return r.finding.construct() + " at " + r.finding.location() + " (" + r.finding.detail() + ")";
    }

    private static Draft newCase(Constraint c, String id, String description, String in,
                                 String name, String pointer, String keyword, ValidRequests.Request request,
                                 ValidRequests.Request baseline, Expected expected) {
        Draft out = new Draft(id, description, in, name, pointer, keyword, request, baseline, expected);
        c.drafts.add(out);
        return out;
    }

    // --------------------------------------------------------- parameters

    private void parameterRequired(Operation operation, Constraint c, Expected expected, List<Draft> cases) {
        ValidRequests.Request baseline;
        try {
            baseline = requests.request(operation, ValidRequests.Kind.REQUIRED);
        } catch (ValidValues.Unsatisfiable | Shapes.Unrepresentable e) {
            c.uncovered(Reason.NO_VALID_BASELINE, why(e));
            return;
        }
        String unsure = unverifiable(operation, baseline);
        if (unsure != null) {
            c.uncovered(Reason.NO_VALID_BASELINE, unsure);
            return;
        }
        ValidRequests.Request without = withParameter(operation, baseline, c.in, c.name, null);
        cases.add(newCase(c, c.in + "-" + c.name + "-required", c.in + " parameter `" + c.name
                + "` missing, though it is required", c.in, c.name, null, "required", without, baseline, expected));
    }

    private void parameterValue(Operation operation, Constraint c, ValidRequests.Request baseline, Expected expected,
                                List<Draft> cases) {
        Node node = c.node;
        String current = parameterValue(baseline, c.in, c.name, operation);
        if (current == null) {
            try {
                current = ValidRequests.wire(values.value(node.roots, ValidValues.Variant.REQUIRED),
                        new ValidRequests.Located(null, node.roots.get(0).location()));
            } catch (ValidValues.Unsatisfiable | Shapes.Unrepresentable e) {
                c.uncovered(Reason.NO_VALID_BASELINE, why(e));
                return;
            }
        }
        if (c.keyword.equals("type") && !List.of("integer", "number", "boolean").contains(c.declaring.types().get(0))) {
            c.uncovered(Reason.NOT_EXPRESSIBLE_IN_PARAMETER, "every value of a parameter travels as a string");
            return;
        }
        Object value = convert(current, node.parts);
        for (Faults.Fault f : faults.of(value, node.roots)) {
            if (!f.checked()) {
                c.uncovered(Reason.NO_VALID_BASELINE, "parameter " + c.name + ": " + uncheckable(f));
                return;
            }
        }
        List<Candidate> candidates = candidates(c, node, value);
        Faults.Fault expectedFault = new Faults.Fault(expectedKeyword(c), "", c.schemaLocation);
        Map<String, Integer> conflicts = new LinkedHashMap<>();
        boolean routeOnly = false;
        for (Candidate candidate : candidates) {
            String wire = wireValue(candidate.value());
            if (wire == null) continue;
            if (c.in.equals(PATH) && (wire.isEmpty() || wire.contains("/") || wire.contains("?") || wire.contains("#"))) {
                routeOnly = true;
                continue;
            }
            List<Faults.Fault> found = faults.of(convert(wire, node.parts), node.roots);
            if (found.equals(List.of(expectedFault))) {
                ValidRequests.Request request = withParameter(operation, baseline, c.in, c.name, wire);
                ValidRequests.Request valid = withParameter(operation, baseline, c.in, c.name, current);
                cases.add(newCase(c, c.in + "-" + c.name + "-" + idKeyword(c), node.label() + " "
                        + candidate.description(), c.in, c.name, null, expectedKeyword(c), request, valid, expected));
                return;
            }
            conflict(found, expectedFault, conflicts);
        }
        if (routeOnly && conflicts.isEmpty()) {
            c.uncovered(Reason.CHANGES_ROUTE, "every violating value found is empty or contains /, ? or #");
        } else {
            notIsolatable(c, conflicts);
        }
    }

    private static String wireValue(Object value) {
        if (value instanceof String s) return s;
        if (value instanceof BigDecimal n) return ValueJson.number(n);
        if (value instanceof Boolean b) return b.toString();
        return null;
    }

    /** A parameter's value as a server converts it: a number or a boolean where its schema says so and it reads as one. */
    static Object convert(String wire, List<Faults.At> parts) {
        String type = parts.stream().filter(p -> p.schema().types() != null).map(p -> p.schema().types().get(0))
                .findFirst().orElse("string");
        if ((type.equals("integer") || type.equals("number")) && JSON_NUMBER.matcher(wire).matches()) {
            return new BigDecimal(wire);
        }
        if (type.equals("boolean") && (wire.equals("true") || wire.equals("false"))) return Boolean.valueOf(wire);
        return wire;
    }

    private String parameterValue(ValidRequests.Request r, String in, String name, Operation operation) {
        if (in.equals(PATH)) {
            List<String> placeholders = placeholders(operation.path());
            int i = placeholders.indexOf(name);
            return i < 0 ? null : r.pathValues().get(i);
        }
        List<ValidRequests.Pair> pairs = in.equals(QUERY) ? r.query() : r.headers();
        return pairs.stream().filter(p -> p.name().equals(name)).map(ValidRequests.Pair::value).findFirst().orElse(null);
    }

    private static List<String> placeholders(String path) {
        List<String> out = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(path);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** The request with one parameter set to a value, added where it was not sent, or left out when the value is null. */
    private ValidRequests.Request withParameter(Operation operation, ValidRequests.Request r, String in, String name,
                                                String value) {
        if (in.equals(PATH)) {
            List<String> path = new ArrayList<>(r.pathValues());
            path.set(placeholders(operation.path()).indexOf(name), value);
            return new ValidRequests.Request(r.method(), r.pathTemplate(), List.copyOf(path), r.query(), r.headers(),
                    r.contentType(), r.body());
        }
        List<ValidRequests.Pair> current = in.equals(QUERY) ? r.query() : r.headers();
        List<ValidRequests.Pair> out = new ArrayList<>();
        boolean placed = false;
        for (ValidRequests.Located located : requests.parameters(operation)) {
            Parameter p = located.parameter();
            if (!in.equals(p.in())) continue;
            if (p.name().equals(name)) {
                if (value != null) out.add(new ValidRequests.Pair(name, value));
                placed = true;
            } else {
                current.stream().filter(x -> x.name().equals(p.name())).forEach(out::add);
            }
        }
        if (!placed && value != null) out.add(new ValidRequests.Pair(name, value));
        return in.equals(QUERY)
                ? new ValidRequests.Request(r.method(), r.pathTemplate(), r.pathValues(), List.copyOf(out), r.headers(),
                r.contentType(), r.body())
                : new ValidRequests.Request(r.method(), r.pathTemplate(), r.pathValues(), r.query(), List.copyOf(out),
                r.contentType(), r.body());
    }

    // --------------------------------------------------------------- body

    private void body(Operation operation, Constraint c, ValidRequests.Request baseline, boolean severalTypes,
                      Expected expected, List<Draft> cases) {
        Node node = c.node;
        Node root = node;
        while (root.parent != null) root = root.parent;
        List<Faults.At> rootSchema = root.roots;
        Object prepared;
        try {
            prepared = present(baseline.body(), node);
        } catch (ValidValues.Unsatisfiable | Shapes.Unrepresentable e) {
            c.uncovered(Reason.NO_VALID_BASELINE, why(e));
            return;
        }
        List<Faults.Fault> before = faults.of(prepared, rootSchema);
        Faults.Fault unchecked = before.stream().filter(f -> !f.checked()).findFirst().orElse(null);
        if (unchecked != null) {
            c.uncovered(Reason.NO_VALID_BASELINE, uncheckable(unchecked));
            return;
        }
        if (!before.isEmpty()) {
            Faults.Fault f = before.get(0);
            c.uncovered(Reason.NOT_ISOLATABLE, "conflicting keyword " + f.keyword() + ": adding " + node.label()
                    + " with a valid value already breaks " + f.keyword() + " at " + pointerOrRoot(f.pointer()));
            return;
        }
        List<Object> path = node.path();
        Object current = get(prepared, path);
        String media = severalTypes ? "-" + slug(c.mediaType) : "";
        Map<String, Integer> conflicts = new LinkedHashMap<>();
        List<Variant> variants = new ArrayList<>();
        variants.add(new Variant(idKeyword(c), expectedKeyword(c), candidates(c, node, current)));
        if (c.keyword.equals("type") && !node.pointer.isEmpty() && !c.declaring.types().contains("null")) {
            variants.add(new Variant("null", "type", List.of(new Candidate(ValueJson.NULL,
                    "null, which its type does not allow"))));
        }
        for (Variant variant : variants) {
            for (Candidate candidate : variant.candidates()) {
                Faults.Fault expectedFault = new Faults.Fault(variant.keyword(), expectedPointer(c, candidate),
                        c.schemaLocation);
                Object mutated = replace(prepared, path, candidate.value());
                List<Faults.Fault> found = faults.of(mutated, rootSchema);
                if (found.equals(List.of(expectedFault))) {
                    ValidRequests.Request request = new ValidRequests.Request(baseline.method(), baseline.pathTemplate(),
                            baseline.pathValues(), baseline.query(), baseline.headers(), baseline.contentType(), mutated);
                    ValidRequests.Request valid = new ValidRequests.Request(baseline.method(), baseline.pathTemplate(),
                            baseline.pathValues(), baseline.query(), baseline.headers(), baseline.contentType(), prepared);
                    String id = "body" + media + segments(c.pointer) + "-" + variant.id();
                    cases.add(newCase(c, id, subject(c, node) + " " + candidate.description(), BODY, null,
                            expectedFault.pointer(), variant.keyword(), request, valid, expected));
                    break;
                }
                conflict(found, expectedFault, conflicts);
            }
        }
        if (c.drafts.isEmpty()) notIsolatable(c, conflicts);
    }

    private record Variant(String id, String keyword, List<Candidate> candidates) {
    }

    private static String subject(Constraint c, Node node) {
        if (c.keyword.equals("required") && !c.pointer.equals(node.pointer)) return dotted(c.pointer);
        return node.label();
    }

    /** A pointer into the body as a reader writes it: {@code `items[0].quantity`}, or {@code the body}. */
    static String dotted(String pointer) {
        if (pointer.isEmpty()) return "the body";
        StringBuilder out = new StringBuilder();
        for (String segment : pointer.substring(1).split("/", -1)) {
            String s = segment.replace("~1", "/").replace("~0", "~");
            if (!s.isEmpty() && s.chars().allMatch(Character::isDigit)) {
                out.append('[').append(s).append(']');
            } else {
                out.append(out.isEmpty() ? "" : ".").append(s);
            }
        }
        return "`" + out + "`";
    }

    private static String label(String pointer) {
        String last = pointer.substring(pointer.lastIndexOf('/') + 1);
        return last.replace("~1", "/").replace("~0", "~");
    }

    private static String pointerOrRoot(String pointer) {
        return pointer.isEmpty() ? "the root" : pointer;
    }

    /** The value at the node's path, with every missing member and item on the way added with a valid value. */
    private Object present(Object body, Node node) {
        List<Node> chain = new ArrayList<>();
        for (Node n = node; n != null; n = n.parent) {
            if (!n.branch || n == node) chain.add(0, n);
        }
        Object out = body;
        List<Object> path = new ArrayList<>();
        for (int i = 1; i < chain.size(); i++) {
            Node step = chain.get(i);
            if (step.branch) break;
            Object key = step.member != null ? step.member : (Object) 0;
            path.add(key);
            if (get(out, path) == MISSING) {
                Object value = values.value(step.roots, ValidValues.Variant.REQUIRED);
                out = set(out, path, value);
            }
        }
        return out;
    }

    private static Object get(Object root, List<Object> path) {
        Object out = root;
        for (Object step : path) {
            if (step instanceof String name && out instanceof Map<?, ?> map) {
                if (!map.containsKey(name)) return MISSING;
                out = map.get(name);
            } else if (step instanceof Integer index && out instanceof List<?> list) {
                if (index >= list.size()) return MISSING;
                out = list.get(index);
            } else {
                return MISSING;
            }
        }
        return out;
    }

    /** A copy of the root with the value at the path replaced, added, or removed when the value is {@link #MISSING}. */
    private static Object set(Object root, List<Object> path, Object value) {
        if (path.isEmpty()) return value;
        Object step = path.get(0);
        List<Object> rest = path.subList(1, path.size());
        if (step instanceof String name && root instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put((String) k, v));
            Object child = rest.isEmpty() ? value : set(copy.getOrDefault(name, MISSING), rest, value);
            if (child == MISSING) {
                copy.remove(name);
            } else {
                copy.put(name, child);
            }
            return Collections.unmodifiableMap(copy);
        }
        if (step instanceof Integer index && root instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list);
            Object child = rest.isEmpty() ? value : set(index < copy.size() ? copy.get(index) : MISSING, rest, value);
            if (index < copy.size()) {
                copy.set(index, child);
            } else {
                copy.add(child);
            }
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalStateException("no value at " + path);
    }

    /** The body with the constraint's one change made: the value at the node replaced, or a member removed or added. */
    private static Object replace(Object body, List<Object> path, Object value) {
        if (value instanceof Change change) {
            List<Object> member = new ArrayList<>(path);
            member.add(change.member());
            return set(body, member, change.value());
        }
        return set(body, path, value);
    }

    /** A change to one member of an object: removed when its value is {@link #MISSING}. */
    private record Change(String member, Object value) {
    }

    /** Where the fault is: at the member an unknown-member case adds, otherwise at the constraint's pointer. */
    private static String expectedPointer(Constraint c, Candidate candidate) {
        if (c.keyword.equals("additionalProperties") && candidate.value() instanceof Change change) {
            return c.pointer + "/" + Shapes.escape(change.member());
        }
        return c.pointer;
    }

    private static String expectedKeyword(Constraint c) {
        if (c.declaring != null) {
            Constraints k = c.declaring.constraints();
            if (c.keyword.equals("minimum") && Boolean.TRUE.equals(k.exclusiveMinimum())) return "exclusiveMinimum";
            if (c.keyword.equals("maximum") && Boolean.TRUE.equals(k.exclusiveMaximum())) return "exclusiveMaximum";
        }
        return c.keyword;
    }

    private static String idKeyword(Constraint c) {
        if (c.keyword.equals("additionalProperties")) return "unknown-member";
        return expectedKeyword(c);
    }

    private static void conflict(List<Faults.Fault> found, Faults.Fault expected, Map<String, Integer> conflicts) {
        if (!found.contains(expected)) return;
        for (Faults.Fault f : found) {
            if (f.equals(expected)) continue;
            String name = f.keyword().equals(expected.keyword()) ? f.keyword() + " (at " + f.schemaLocation() + ")"
                    : f.keyword();
            conflicts.merge(name, 1, Integer::sum);
        }
    }

    private static void notIsolatable(Constraint c, Map<String, Integer> conflicts) {
        String keyword = conflicts.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null);
        c.uncovered(Reason.NOT_ISOLATABLE, keyword == null
                ? "conflicting keyword " + c.keyword + ": no value was found that breaks it"
                : "conflicting keyword " + keyword + ": every value found that breaks " + c.keyword + " breaks "
                + keyword + " too");
    }

    private static String segments(String pointer) {
        if (pointer.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String s : pointer.substring(1).split("/", -1)) {
            out.append('-').append(s.replace("~1", "/").replace("~0", "~"));
        }
        return out.toString();
    }

    private static String slug(String mediaType) {
        return mediaType.split(";", 2)[0].strip().replaceAll("[^A-Za-z0-9]+", "-");
    }

    // --------------------------------------------------------- candidates

    /** Violating values for a constraint, in the order they are tried: the one the table asks for first. */
    private List<Candidate> candidates(Constraint c, Node node, Object current) {
        Schema s = c.declaring;
        Constraints k = s.constraints();
        List<Candidate> out = new ArrayList<>();
        switch (c.keyword) {
            case "required" -> out.add(new Candidate(new Change(label(c.pointer), MISSING), "missing, though it is required"));
            case "type" -> {
                String type = s.types().get(0);
                out.add(new Candidate(nonCoercible(type), "sent as " + describe(nonCoercible(type)) + ", where the "
                        + "contract requires " + (List.of("integer", "object", "array").contains(type) ? "an " : "a ")
                        + type));
            }
            case "enum", "const" -> {
                List<Object> listed = c.keyword.equals("const") ? List.of(ValueJson.of(s.constValue().value()))
                        : s.enumValues().stream().map(ValueJson::of).toList();
                for (Object v : sameType(node, current, listed)) {
                    if (listed.stream().noneMatch(l -> ValueJson.equal(l, v))) {
                        out.add(new Candidate(v, c.keyword.equals("const") ? "not its const value"
                                : "not one of its enum values"));
                    }
                }
            }
            case "minLength" -> {
                int length = k.minLength().intValue() - 1;
                for (String v : strings(node, current, length)) {
                    out.add(new Candidate(v, "shorter than its minLength of " + k.minLength()));
                }
            }
            case "maxLength" -> {
                int length = k.maxLength().intValue() + 1;
                for (String v : strings(node, current, length)) {
                    out.add(new Candidate(v, "longer than its maxLength of " + k.maxLength()));
                }
            }
            case "pattern" -> {
                for (String v : nonMatching(node, current, x -> !find(k.pattern(), x))) {
                    out.add(new Candidate(v, "not matching its pattern " + k.pattern()));
                }
            }
            case "format" -> {
                for (String v : nonMatching(node, current, x -> !Formats.accepts(s.format(), x))) {
                    out.add(new Candidate(v, "not a valid " + s.format()));
                }
            }
            case "minimum", "maximum" -> bound(c, node, out);
            case "exclusiveMinimum", "exclusiveMaximum" -> {
                BigDecimal b = (BigDecimal) ValueJson.of((Number) (c.keyword.equals("exclusiveMinimum")
                        ? k.exclusiveMinimum() : k.exclusiveMaximum()));
                out.add(new Candidate(number(b), "equal to its exclusive "
                        + (c.keyword.equals("exclusiveMinimum") ? "minimum" : "maximum") + " of " + ValueJson.number(b)));
            }
            case "multipleOf" -> multipleOf(c, node, current, out);
            case "minItems", "maxItems", "uniqueItems" -> items(c, node, current, out);
            case "minProperties", "maxProperties" -> properties(c, node, current, out);
            case "additionalProperties" -> {
                String name = unknownName(node, s);
                out.add(new Candidate(new Change(name, "unexpected"), "with a member it does not declare, `" + name + "`"));
            }
            default -> {
            }
        }
        return out;
    }

    /** A value of another type that no lenient binder coerces into the declared one. */
    static Object nonCoercible(String type) {
        return switch (type) {
            case "integer", "number" -> "not-a-number";
            case "boolean" -> "not-a-boolean";
            case "array" -> Collections.unmodifiableMap(new LinkedHashMap<String, Object>());
            case "object" -> List.of();
            default -> Collections.unmodifiableMap(new LinkedHashMap<String, Object>());
        };
    }

    private static String describe(Object value) {
        if (value instanceof String s) return "the string \"" + s + "\"";
        return value instanceof Map<?, ?> ? "an object" : "an array";
    }

    /** A number written as an integer where it is a whole one. */
    private static Object number(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 0 ? value.setScale(0, RoundingMode.UNNECESSARY) : value;
    }

    /** The multiple that every multipleOf at the node shares, or null when none declares one; 1 for an integer. */
    private static BigDecimal step(Node node, Schema except) {
        BigDecimal step = null;
        for (Faults.At part : node.parts) {
            if (part.schema() == except || part.schema().constraints().multipleOf() == null) continue;
            BigDecimal m = (BigDecimal) ValueJson.of(part.schema().constraints().multipleOf());
            step = step == null ? m : ValidValues.lcm(step, m);
        }
        boolean integer = node.parts.stream().anyMatch(p -> p.schema().types() != null
                && p.schema().types().contains("integer"));
        if (integer) step = step == null ? BigDecimal.ONE : ValidValues.lcm(step, BigDecimal.ONE);
        return step;
    }

    private void bound(Constraint c, Node node, List<Candidate> out) {
        Constraints k = c.declaring.constraints();
        boolean lower = c.keyword.equals("minimum");
        BigDecimal b = (BigDecimal) ValueJson.of(lower ? k.minimum() : k.maximum());
        boolean exclusive = Boolean.TRUE.equals(lower ? k.exclusiveMinimum() : k.exclusiveMaximum());
        BigDecimal step = step(node, null);
        BigDecimal unit = step == null ? BigDecimal.ONE : step;
        List<BigDecimal> tries = new ArrayList<>();
        if (exclusive) {
            tries.add(b);
        }
        if (step != null) {
            BigDecimal m = b.divide(step, 0, lower ? RoundingMode.CEILING : RoundingMode.FLOOR).multiply(step);
            tries.add(lower ? m.subtract(step) : m.add(step));
        }
        tries.add(lower ? b.subtract(unit) : b.add(unit));
        String text = exclusive ? "equal to its exclusive " + (lower ? "minimum" : "maximum") + " of " + ValueJson.number(b)
                : (lower ? "below its minimum of " : "above its maximum of ") + ValueJson.number(b);
        for (BigDecimal t : new LinkedHashSet<>(tries)) out.add(new Candidate(number(t), text));
    }

    private void multipleOf(Constraint c, Node node, Object current, List<Candidate> out) {
        BigDecimal s = (BigDecimal) ValueJson.of(c.declaring.constraints().multipleOf());
        BigDecimal base = current instanceof BigDecimal n ? n : BigDecimal.ZERO;
        BigDecimal other = step(node, c.declaring);
        List<BigDecimal> tries = new ArrayList<>();
        if (other != null) {
            for (int i = 1; i <= 100; i++) {
                tries.add(base.add(other.multiply(BigDecimal.valueOf(i))));
                tries.add(base.subtract(other.multiply(BigDecimal.valueOf(i))));
            }
        } else {
            for (BigDecimal d : List.of(new BigDecimal("0.5"), new BigDecimal("0.25"), new BigDecimal("1.5"))) {
                tries.add(base.add(s.multiply(d)));
                tries.add(base.subtract(s.multiply(d)));
            }
        }
        for (BigDecimal t : tries) {
            if (t.remainder(s).signum() != 0) {
                out.add(new Candidate(number(t), "not a multiple of " + ValueJson.number(s)));
            }
        }
    }

    /** Values of the type the node's current value has, that satisfy what they can of the node's other keywords. */
    private List<Object> sameType(Node node, Object current, List<Object> listed) {
        List<Object> out = new ArrayList<>();
        Object sample = current != MISSING && current != null ? current : listed.isEmpty() ? null : listed.get(0);
        if (sample instanceof String s) {
            int length = s.codePointCount(0, s.length());
            for (int l = length; l <= length + 3; l++) out.addAll(strings(node, s, l));
            for (int l = Math.max(0, length - 3); l < length; l++) out.addAll(strings(node, s, l));
        } else if (sample instanceof BigDecimal n) {
            BigDecimal step = step(node, null);
            BigDecimal unit = step == null ? BigDecimal.ONE : step;
            for (int i = 1; i <= 50; i++) {
                out.add(number(n.add(unit.multiply(BigDecimal.valueOf(i)))));
                out.add(number(n.subtract(unit.multiply(BigDecimal.valueOf(i)))));
            }
        } else if (sample instanceof Boolean) {
            out.add(false);
            out.add(true);
        }
        return out;
    }

    /**
     * Strings of exactly the given length that satisfy what they can of the node's other
     * keywords: the pattern, else the format, else plain letters; the current value, cut or
     * lengthened, last.
     */
    private List<String> strings(Node node, Object current, int length) {
        List<String> out = new ArrayList<>();
        if (length < 0) return out;
        List<String> regexes = node.parts.stream().map(p -> p.schema().constraints().pattern())
                .filter(java.util.Objects::nonNull).toList();
        if (!regexes.isEmpty()) {
            EcmaPattern p = pattern(regexes.get(0));
            if (p != null && length <= EcmaPattern.MAX_LENGTH) {
                Iterator<String> it = Lazy.limit(p.values(length, length), CANDIDATES);
                while (it.hasNext()) out.add(it.next());
            }
        }
        boolean formatted = false;
        if (regexes.isEmpty()) {
            for (Faults.At part : node.parts) {
                String format = part.schema().format();
                if (format != null && Formats.supported(format)) {
                    formatted = true;
                    Formats.values(format, length, length).forEachRemaining(out::add);
                    if (format.equals("email")) {
                        String email = email(length);
                        if (email != null) out.add(email);
                    }
                }
            }
        }
        out.add("a".repeat(length));
        out.add("b".repeat(length));
        // A formatted value cut or lengthened is seldom still of its format, whatever a loose check says.
        if (!formatted && current instanceof String s && !s.isEmpty()) {
            int[] points = s.codePoints().toArray();
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < length; i++) b.appendCodePoint(points[Math.min(i, points.length - 1)]);
            out.add(b.toString());
        }
        return new ArrayList<>(new LinkedHashSet<>(out));
    }

    /**
     * An address of exactly the given length that a strict validator accepts: a local part of
     * at most 64 letters, labels of at most 63, and a real top-level domain; null below 6.
     */
    static String email(int length) {
        if (length < 6) return null;
        if (length <= 12) return "a".repeat(length - 5) + "@b.co";
        if (length <= 76) return "a".repeat(length - 12) + "@example.com";
        int rest = length - 76;
        if (rest == 1) return "a".repeat(63) + "@a.example.com";
        StringBuilder labels = new StringBuilder();
        while (rest > 0) {
            int take = Math.min(64, rest);
            if (rest - take == 1) take--;
            labels.append("a".repeat(take - 1)).append('.');
            rest -= take;
        }
        return "a".repeat(64) + "@" + labels + "example.com";
    }

    /** Strings of a length the node allows that fail a test: the pattern or the format being violated. */
    private List<String> nonMatching(Node node, Object current, java.util.function.Predicate<String> failing) {
        long lo = 0;
        long hi = Long.MAX_VALUE;
        for (Faults.At part : node.parts) {
            Constraints k = part.schema().constraints();
            if (k.minLength() != null) lo = Math.max(lo, (long) Math.ceil(k.minLength().doubleValue()));
            if (k.maxLength() != null) hi = Math.min(hi, (long) Math.floor(k.maxLength().doubleValue()));
        }
        List<Integer> lengths = new ArrayList<>();
        int preferred = current instanceof String s ? s.codePointCount(0, s.length()) : (int) Math.max(lo, 1);
        if (preferred >= lo && preferred <= hi) lengths.add(preferred);
        for (long l = Math.max(lo, 1); l <= Math.min(hi, lo + 8); l++) lengths.add((int) l);
        if (lo == 0) lengths.add(0);
        List<String> out = new ArrayList<>();
        for (int length : new LinkedHashSet<>(lengths)) {
            for (String fill : List.of("%", "~", "!", "0", "A", "-", "_", ".", "a", "Z")) {
                String v = fill.repeat(length);
                if (failing.test(v)) out.add(v);
            }
            if (current instanceof String s && !s.isEmpty() && s.codePointCount(0, s.length()) == length) {
                for (String fill : List.of("%", "~", "!", "A", "0")) {
                    String v = fill + s.substring(s.offsetByCodePoints(0, 1));
                    if (failing.test(v)) out.add(v);
                }
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(out));
    }

    private void items(Constraint c, Node node, Object current, List<Candidate> out) {
        List<Object> list = current instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>();
        Constraints k = c.declaring.constraints();
        List<Faults.At> itemRoots = new ArrayList<>();
        for (Faults.At part : node.parts) {
            if (part.schema().items() != null) itemRoots.add(new Faults.At(part.schema().items(), part.location() + "/items"));
        }
        switch (c.keyword) {
            case "minItems" -> {
                int count = k.minItems().intValue() - 1;
                if (count <= list.size()) {
                    out.add(new Candidate(List.copyOf(list.subList(0, count)), "with fewer items than its minItems of "
                            + k.minItems()));
                }
            }
            case "maxItems" -> {
                int count = k.maxItems().intValue() + 1;
                if (count > ValidValues.SEARCH) return;
                String text = "with more items than its maxItems of " + k.maxItems();
                List<Object> pool = pool(itemRoots, list, count);
                if (pool.size() >= count) out.add(new Candidate(List.copyOf(pool.subList(0, count)), text));
                if (!pool.isEmpty()) {
                    List<Object> repeated = new ArrayList<>(list);
                    while (repeated.size() < count) repeated.add(pool.get(0));
                    out.add(new Candidate(List.copyOf(repeated), text));
                }
            }
            default -> {
                String text = "with two equal items, though its items must be unique";
                List<Object> pool = pool(itemRoots, list, 1);
                if (pool.isEmpty()) return;
                List<Object> twice = new ArrayList<>(list);
                if (twice.size() >= 2) {
                    twice.set(1, twice.get(0));
                } else {
                    while (twice.size() < 2) twice.add(pool.get(0));
                }
                out.add(new Candidate(List.copyOf(twice), text));
            }
        }
    }

    /** Distinct valid items: the current ones first, then generated ones, up to the count. */
    private List<Object> pool(List<Faults.At> itemRoots, List<Object> current, int count) {
        List<Object> out = new ArrayList<>();
        for (Object v : current) {
            if (out.stream().noneMatch(o -> ValueJson.equal(o, v))) out.add(v);
        }
        if (out.size() >= count) return out;
        List<Object> generated;
        if (itemRoots.isEmpty()) {
            generated = new ArrayList<>();
            for (int i = 0; i < count; i++) generated.add(BigDecimal.valueOf(i));
        } else {
            try {
                generated = values.values(itemRoots, count + current.size());
            } catch (ValidValues.Unsatisfiable | Shapes.Unrepresentable e) {
                generated = List.of();
            }
        }
        for (Object v : generated) {
            if (out.size() >= count) break;
            if (out.stream().noneMatch(o -> ValueJson.equal(o, v))) out.add(v);
        }
        return out;
    }

    private void properties(Constraint c, Node node, Object current, List<Candidate> out) {
        if (!(current instanceof Map<?, ?> map)) return;
        Map<String, Object> members = new LinkedHashMap<>();
        map.forEach((key, v) -> members.put((String) key, v));
        Set<String> required = new HashSet<>();
        Set<String> declared = new LinkedHashSet<>();
        for (Faults.At part : node.parts) {
            if (part.schema().required() != null) required.addAll(part.schema().required());
            if (part.schema().properties() != null) declared.addAll(part.schema().properties().keySet());
        }
        Constraints k = c.declaring.constraints();
        if (c.keyword.equals("minProperties")) {
            int count = k.minProperties().intValue() - 1;
            List<String> order = new ArrayList<>(members.keySet());
            Collections.reverse(order);
            order.sort(Comparator.comparing(required::contains));
            Map<String, Object> fewer = new LinkedHashMap<>(members);
            for (String name : order) {
                if (fewer.size() <= count) break;
                fewer.remove(name);
            }
            if (fewer.size() == count) {
                out.add(new Candidate(Collections.unmodifiableMap(fewer), "with fewer members than its minProperties of "
                        + k.minProperties()));
            }
            return;
        }
        int count = k.maxProperties().intValue() + 1;
        Map<String, Object> more = new LinkedHashMap<>(members);
        List<String> candidates = new ArrayList<>(declared);
        for (Faults.At part : node.parts) {
            if (part.schema().patternProperties() == null) continue;
            for (String regex : part.schema().patternProperties().keySet()) {
                EcmaPattern p = pattern(regex);
                if (p != null) Lazy.limit(p.values(1, 64), 64).forEachRemaining(candidates::add);
            }
        }
        for (int i = 1; i <= count; i++) candidates.add("additional" + i);
        for (String name : candidates) {
            if (more.size() >= count) break;
            if (more.containsKey(name)) continue;
            List<Faults.At> roots = memberRoots(node, name);
            try {
                more.put(name, roots.isEmpty() ? "additional" : values.value(roots, ValidValues.Variant.REQUIRED));
            } catch (ValidValues.Unsatisfiable | Shapes.Unrepresentable e) {
                // Another name, then.
            }
        }
        if (more.size() == count) {
            out.add(new Candidate(Collections.unmodifiableMap(more), "with more members than its maxProperties of "
                    + k.maxProperties()));
        }
    }

    /** A member name no part declares and, where an object names its members by pattern, none matches. */
    private String unknownName(Node node, Schema declaring) {
        Set<String> declared = new HashSet<>();
        for (Faults.At part : node.parts) {
            if (part.schema().properties() != null) declared.addAll(part.schema().properties().keySet());
        }
        List<String> tries = new ArrayList<>();
        tries.add(UNKNOWN_MEMBER);
        for (int i = 2; i <= 9; i++) tries.add(UNKNOWN_MEMBER + i);
        tries.addAll(List.of("unexpected_member", "UNEXPECTED", "0", "~"));
        for (String name : tries) {
            if (!declared.contains(name) && !matchesAPattern(node, name)) return name;
        }
        return UNKNOWN_MEMBER;
    }

    private boolean find(String regex, String value) {
        EcmaPattern p = pattern(regex);
        if (p == null) throw new Faults.Unevaluable("pattern", regex);
        return p.find(value);
    }

    private EcmaPattern pattern(String source) {
        if (patterns.containsKey(source)) return patterns.get(source);
        EcmaPattern compiled;
        try {
            compiled = EcmaPattern.compile(source);
        } catch (EcmaPattern.Unsupported e) {
            compiled = null;
        }
        patterns.put(source, compiled);
        return compiled;
    }

    // -------------------------------------------------------------- order

    /** The cases in canonical order, their ids made unique, the first marked the representative. */
    private List<Case> order(List<Draft> cases) {
        List<Draft> sorted = new ArrayList<>(cases);
        Map<Draft, Integer> found = new java.util.IdentityHashMap<>();
        for (int i = 0; i < cases.size(); i++) found.put(cases.get(i), i);
        sorted.sort(Comparator.<Draft>comparingInt(x -> LOCATION_ORDER.indexOf(x.in))
                .thenComparingInt(x -> KEYWORD_ORDER.indexOf(x.id.endsWith("-null") && x.keyword.equals("type")
                        ? "null" : x.keyword))
                .thenComparing(x -> x.name != null ? x.name : x.pointer)
                .thenComparingInt(found::get));
        Set<String> ids = new HashSet<>();
        Set<String> identifiers = new HashSet<>();
        List<Case> out = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            Draft x = sorted.get(i);
            String id = x.id;
            for (int n = 2; ids.contains(id) || identifiers.contains(JavaText.variableName(id)); n++) {
                id = x.id + "-" + n;
            }
            ids.add(id);
            identifiers.add(JavaText.variableName(id));
            x.id = id;
            out.add(new Case(id, x.description, x.in, x.name, x.pointer, x.keyword, x.request, x.baseline, status,
                    x.expected.contentTypes(), x.expected.bodyClass(), i == 0));
        }
        return out;
    }
}
