package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.model.MediaType;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Operation;
import com.arc_e_tect.gradle.apionly.transcriberj.model.RequestBody;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Response;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.CaseKind;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Every contract case of an operation: one for each response the contract alone says how to
 * provoke -- the exact {@code 2xx} responses, {@code 404}, {@code 406}, {@code 415} and the
 * invalid-request status -- and, for every response it declares, the cases that cover it or the
 * one reason none does.
 *
 * <p>Each case is a request the contract accepts, or one that differs from it in exactly one
 * place: the path values of a not-found case, the {@code Accept} of a not-acceptable one, the
 * {@code Content-Type} of an unsupported-media-type one, the one fault of an invalid request.
 * Every case but the not-acceptable one sends the same {@code Accept}: every media type the
 * operation's responses declare, as a client that cannot know which response it gets would send.
 */
final class ContractCases {

    /** Why a declared response has no case: a closed list. */
    enum Reason {
        /** A status range, such as {@code 4XX}, or {@code default}: too broad to provoke. */
        RANGE_OR_DEFAULT("range status or default"),
        /** A status whose answer depends on behaviour or state the contract does not describe. */
        BEHAVIOUR_OR_STATE("depends on behaviour or state the contract does not describe"),
        /** A {@code 404} of an operation with no path parameter to name a missing resource by. */
        NOT_FOUND_WITHOUT_PATH_PARAMETER("404 without a path parameter"),
        /** A {@code 404} whose path parameter has no second valid value. */
        NOT_FOUND_WITHOUT_SECOND_VALUE("404 without a second valid path value"),
        /** A {@code 406} or {@code 415} for which every candidate media type is matched. */
        NO_MEDIA_TYPE_LEFT("406 or 415 with no media type left to choose"),
        /** A {@code 415} of an operation without a request body. */
        UNSUPPORTED_WITHOUT_BODY("415 without a request body"),
        /** There is no valid request to send. */
        NO_VALID_VALUE("no valid value"),
        /** The valid request reaches a construct no rule represents. */
        INSIDE_DEGRADED_CONSTRUCT("inside a degraded construct"),
        /** The {@code derive} setting does not name the kind. */
        KIND_SWITCHED_OFF("kind switched off"),
        /** The invalid-request status, which the constraint coverage accounts for. */
        INVALID_REQUEST_STATUS("the invalid-request status, covered by the constraint coverage");

        final String label;

        Reason(String label) {
            this.label = label;
        }
    }

    /**
     * One case.
     *
     * @param id           readable, unique within the operation, stable across runs
     * @param kind         what it tests
     * @param variant      for a success case, {@code required}, {@code full} or {@code noBody}; else null
     * @param description  one sentence saying what it sends
     * @param in           for an invalid request, where the fault is; else null
     * @param name         for an invalid request, the parameter's name; else null
     * @param pointer      for an invalid request, the JSON pointer into the body; else null
     * @param keyword      for an invalid request, the violated keyword; else null
     * @param request      the request
     * @param baseline     for an invalid request, the valid request it differs from; else null
     * @param status       the declared status it expects
     * @param contentTypes that response's content types
     * @param bodyClass    the simple name of the class of that response's body, or null
     */
    record Case(String id, CaseKind kind, String variant, String description, String in, String name,
                String pointer, String keyword, ValidRequests.Request request, ValidRequests.Request baseline,
                int status, List<String> contentTypes, String bodyClass) {
    }

    /**
     * One declared response, and what covers it.
     *
     * @param status the status as the operation declares it
     * @param cases  the ids of the cases that cover it; empty when a reason is given
     * @param reason why no case covers it, or null
     * @param detail what makes the reason apply here, or null
     */
    record Coverage(String status, List<String> cases, Reason reason, String detail) {
    }

    /**
     * What one operation gives.
     *
     * @param operation the operation
     * @param cases     every case, in canonical order
     * @param coverage  every declared response, in declaration order
     * @param notes     what was not derived although nothing is uncovered by it, such as a full
     *                  request equal to the required one
     */
    record Result(Operation operation, List<Case> cases, List<Coverage> coverage, List<String> notes) {
    }

    /** The media types a not-acceptable case tries, in order: the first no response matches is sent. */
    static final List<String> NOT_ACCEPTABLE_CANDIDATES = List.of("application/vnd.apionly.not-acceptable",
            "text/vnd.apionly.not-acceptable");

    /** The media types an unsupported-media-type case tries, in order. */
    static final List<String> UNSUPPORTED_CANDIDATES = List.of("text/plain",
            "application/vnd.apionly.unsupported-media-type", "text/vnd.apionly.unsupported-media-type");

    private static final Pattern SUCCESS = Pattern.compile("2[0-9][0-9]");
    private static final Pattern EXACT = Pattern.compile("[1-5][0-9][0-9]");
    private static final String ACCEPT = "Accept";

    private final CoreClassNames names;
    private final ValidValues values;
    private final ValidRequests requests;
    private final InvalidRequests invalid;
    private final Settings settings;

    ContractCases(CoreClassNames names, ValidValues values, ValidRequests requests, InvalidRequests invalid,
                  Settings settings) {
        this.names = names;
        this.values = values;
        this.requests = requests;
        this.invalid = invalid;
        this.settings = settings;
    }

    /**
     * Every case of one operation, and the coverage of every response it declares.
     *
     * @param operation the operation
     * @param invalid   its invalid-request derivation
     * @return the cases and the coverage
     */
    Result derive(Operation operation, InvalidRequests.Result invalid) {
        String accept = accept(operation);
        List<Case> cases = new ArrayList<>();
        Map<String, Coverage> coverage = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        List<Response> responses = operation.responses() == null ? List.of() : operation.responses();

        for (Response response : responses) {
            String status = response.status();
            if (status.equals(settings.invalidRequestStatus())) continue;
            if (!EXACT.matcher(status).matches()) {
                coverage.put(status, uncovered(status, Reason.RANGE_OR_DEFAULT, status + " is not one status"));
            } else if (SUCCESS.matcher(status).matches()) {
                success(operation, response, accept, cases, coverage, notes);
            } else if (status.equals("404")) {
                notFound(operation, response, accept, cases, coverage);
            } else if (status.equals("406")) {
                notAcceptable(operation, response, cases, coverage);
            } else if (status.equals("415")) {
                unsupported(operation, response, accept, cases, coverage);
            } else {
                coverage.put(status, uncovered(status, Reason.BEHAVIOUR_OR_STATE,
                        "the contract does not say what provokes " + status));
            }
        }

        // A kind switched off: what it would have covered is not derived, and says why. A response
        // no case of it could cover keeps its own reason, which the setting does not change.
        for (Map.Entry<String, Coverage> entry : coverage.entrySet()) {
            Coverage c = entry.getValue();
            if (c.reason() != null) continue;
            CaseKind kind = cases.stream().filter(k -> k.id().equals(c.cases().get(0))).findFirst().orElseThrow().kind();
            if (!settings.derives(kind)) {
                entry.setValue(uncovered(c.status(), Reason.KIND_SWITCHED_OFF, "derive does not name " + kind.setting()));
            }
        }
        cases.removeIf(c -> !settings.derives(c.kind()));

        List<String> variants = List.of("required", "full", "noBody");
        cases.sort(java.util.Comparator.<Case>comparingInt(c -> c.kind().ordinal())
                .thenComparingInt(Case::status)
                .thenComparingInt(c -> c.variant() == null ? 0 : variants.indexOf(c.variant())));

        List<Case> invalidCases = new ArrayList<>();
        for (InvalidRequests.Case c : invalid.cases()) {
            invalidCases.add(new Case(c.id(), CaseKind.INVALID_REQUEST, null, c.description(), c.in(), c.name(),
                    c.pointer(), c.keyword(), withAccept(c.request(), accept), c.baseline(), c.status(),
                    c.contentTypes(), c.bodyClass()));
        }
        if (invalid.declared()) {
            String status = settings.invalidRequestStatus();
            if (invalid.switchedOff()) {
                coverage.put(status, uncovered(status, Reason.KIND_SWITCHED_OFF, "derive does not name invalidRequest"));
            } else if (invalidCases.isEmpty()) {
                coverage.put(status, uncovered(status, Reason.INVALID_REQUEST_STATUS,
                        "no constraint on the request input has a case; see the constraint coverage"));
            } else {
                cases.addAll(invalidCases);
                coverage.put(status, new Coverage(status, invalidCases.stream().map(Case::id).toList(), null, null));
            }
        }

        List<Coverage> ordered = new ArrayList<>();
        for (Response response : responses) {
            Coverage c = coverage.get(response.status());
            if (c != null) ordered.add(c);
        }
        return new Result(operation, List.copyOf(cases), List.copyOf(ordered), List.copyOf(notes));
    }

    // ------------------------------------------------------------- success

    private void success(Operation operation, Response response, String accept, List<Case> cases,
                         Map<String, Coverage> coverage, List<String> notes) {
        String status = response.status();
        ValidRequests.Request required = required(operation, status, coverage);
        if (required == null) return;
        InvalidRequests.Expected expected = InvalidRequests.expected(names, operation, response);
        List<Case> derived = new ArrayList<>();
        derived.add(successCase(status, "required", "a valid request with the required parameters and body members "
                + "only", required, accept, expected));
        try {
            ValidRequests.Request full = requests.request(operation, ValidRequests.Kind.FULL);
            String unverifiable = invalid.unverifiable(operation, full);
            if (sameShape(full, required)) {
                notes.add("success-" + status + "-full is not derived: the full request is the required one, as "
                        + "the operation declares no optional parameter or member" + (full.namedExample() == null ? ""
                        : "; the example " + full.namedExample() + " that x-transcriberj-examples names for it is "
                        + "not sent"));
            } else if (unverifiable != null) {
                notes.add("success-" + status + "-full is not derived: " + unverifiable);
            } else {
                derived.add(successCase(status, "full", "a valid request with every parameter and body member the "
                        + "contract declares", full, accept, expected));
            }
        } catch (ValidValues.Unsatisfiable | Shapes.Unrepresentable e) {
            notes.add("success-" + status + "-full is not derived: " + e.getMessage());
        }
        if (ValidRequests.bodyOptional(operation)) {
            derived.add(successCase(status, "noBody", "a valid request without the optional body",
                    requests.request(operation, ValidRequests.Kind.NO_BODY), accept, expected));
        }
        cases.addAll(derived);
        coverage.put(status, new Coverage(status, derived.stream().map(Case::id).toList(), null, null));
    }

    private Case successCase(String status, String variant, String description, ValidRequests.Request request,
                             String accept, InvalidRequests.Expected expected) {
        String id = "success-" + status + "-" + (variant.equals("noBody") ? "no-body" : variant);
        return new Case(id, CaseKind.SUCCESS, variant, description, null, null, null, null,
                withAccept(request, accept), null, Integer.parseInt(status), expected.contentTypes(),
                expected.bodyClass());
    }

    // ----------------------------------------------------------- not found

    private void notFound(Operation operation, Response response, String accept, List<Case> cases,
                          Map<String, Coverage> coverage) {
        String status = response.status();
        List<String> placeholders = placeholders(operation.path());
        if (placeholders.isEmpty()) {
            coverage.put(status, uncovered(status, Reason.NOT_FOUND_WITHOUT_PATH_PARAMETER,
                    operation.path() + " names no resource by a path parameter"));
            return;
        }
        ValidRequests.Request required = required(operation, status, coverage);
        if (required == null) return;
        Map<String, ValidRequests.Located> byName = new LinkedHashMap<>();
        for (ValidRequests.Located located : requests.parameters(operation)) {
            if ("path".equals(located.parameter().in())) byName.put(located.parameter().name(), located);
        }
        List<String> others = new ArrayList<>();
        for (int i = 0; i < placeholders.size(); i++) {
            ValidRequests.Located located = byName.get(placeholders.get(i));
            String baseline = required.pathValues().get(i);
            String other = null;
            try {
                List<Object> candidates = values.values(List.of(new Faults.At(located.parameter().schema(),
                        located.location() + "/schema")), 3);
                for (Object candidate : candidates) {
                    String wire = ValidRequests.wire(candidate, located);
                    if (!wire.equals(baseline)) {
                        other = wire;
                        break;
                    }
                }
            } catch (ValidValues.Unsatisfiable | Shapes.Unrepresentable e) {
                other = null;
            }
            if (other == null) {
                coverage.put(status, uncovered(status, Reason.NOT_FOUND_WITHOUT_SECOND_VALUE, "path parameter "
                        + placeholders.get(i) + " has no valid value but " + baseline));
                return;
            }
            others.add(other);
        }
        ValidRequests.Request request = new ValidRequests.Request(required.method(), required.pathTemplate(),
                List.copyOf(others), required.query(), required.headers(), required.contentType(), required.body(),
                required.namedExample(), required.examples());
        InvalidRequests.Expected expected = InvalidRequests.expected(names, operation, response);
        Case c = new Case("not-found", CaseKind.NOT_FOUND, null, "a valid request naming a resource that does not "
                + "exist", null, null, null, null, withAccept(request, accept), null, 404, expected.contentTypes(),
                expected.bodyClass());
        cases.add(c);
        coverage.put(status, new Coverage(status, List.of(c.id()), null, null));
    }

    // ------------------------------------------------------ not acceptable

    private void notAcceptable(Operation operation, Response response, List<Case> cases,
                               Map<String, Coverage> coverage) {
        String status = response.status();
        List<String> offered = responseMediaTypes(operation);
        if (offered.isEmpty()) {
            coverage.put(status, uncovered(status, Reason.NO_MEDIA_TYPE_LEFT, "no response declares content, so "
                    + "no Accept can be refused"));
            return;
        }
        String chosen = firstUnmatched(NOT_ACCEPTABLE_CANDIDATES, offered);
        if (chosen == null) {
            coverage.put(status, uncovered(status, Reason.NO_MEDIA_TYPE_LEFT, "the responses' media types "
                    + offered + " match every candidate " + NOT_ACCEPTABLE_CANDIDATES));
            return;
        }
        ValidRequests.Request required = required(operation, status, coverage);
        if (required == null) return;
        InvalidRequests.Expected expected = InvalidRequests.expected(names, operation, response);
        Case c = new Case("not-acceptable", CaseKind.NOT_ACCEPTABLE, null, "a valid request accepting only "
                + chosen + ", which no response offers", null, null, null, null, withAccept(required, chosen), null,
                406, expected.contentTypes(), expected.bodyClass());
        cases.add(c);
        coverage.put(status, new Coverage(status, List.of(c.id()), null, null));
    }

    // ---------------------------------------------- unsupported media type

    private void unsupported(Operation operation, Response response, String accept, List<Case> cases,
                             Map<String, Coverage> coverage) {
        String status = response.status();
        RequestBody body = operation.requestBody();
        if (body == null || body.content() == null || body.content().isEmpty()) {
            coverage.put(status, uncovered(status, Reason.UNSUPPORTED_WITHOUT_BODY, "the operation declares no "
                    + "request body"));
            return;
        }
        List<String> accepted = body.content().stream().map(MediaType::contentType).toList();
        String chosen = firstUnmatched(UNSUPPORTED_CANDIDATES, accepted);
        if (chosen == null) {
            coverage.put(status, uncovered(status, Reason.NO_MEDIA_TYPE_LEFT, "the request body's media types "
                    + accepted + " match every candidate " + UNSUPPORTED_CANDIDATES));
            return;
        }
        ValidRequests.Request required = required(operation, status, coverage);
        if (required == null) return;
        ValidRequests.Request request = new ValidRequests.Request(required.method(), required.pathTemplate(),
                required.pathValues(), required.query(), required.headers(), chosen, required.body(),
                required.namedExample(), required.examples());
        InvalidRequests.Expected expected = InvalidRequests.expected(names, operation, response);
        Case c = new Case("unsupported-media-type", CaseKind.UNSUPPORTED_MEDIA_TYPE, null, "the valid body sent as "
                + chosen + ", which the operation does not accept", null, null, null, null,
                withAccept(request, accept), null, 415, expected.contentTypes(), expected.bodyClass());
        cases.add(c);
        coverage.put(status, new Coverage(status, List.of(c.id()), null, null));
    }

    /**
     * The operation's required request, which every case but an invalid request's is built from; or
     * null, with the reason in the coverage, when there is none, or none that can be vouched for.
     */
    private ValidRequests.Request required(Operation operation, String status, Map<String, Coverage> coverage) {
        try {
            ValidRequests.Request required = requests.request(operation, ValidRequests.Kind.REQUIRED);
            String unverifiable = invalid.unverifiable(operation, required);
            if (unverifiable == null) return required;
            coverage.put(status, uncovered(status, Reason.NO_VALID_VALUE, unverifiable));
        } catch (Shapes.Unrepresentable e) {
            coverage.put(status, uncovered(status, Reason.INSIDE_DEGRADED_CONSTRUCT, e.getMessage()));
        } catch (ValidValues.Unsatisfiable e) {
            coverage.put(status, uncovered(status, Reason.NO_VALID_VALUE, e.reason + " at " + e.location));
        }
        return null;
    }

    // --------------------------------------------------------- media types

    /**
     * The {@code Accept} every case of an operation but the not-acceptable one sends: every media
     * type its responses declare, in declaration order, once each; null when none declares content.
     */
    static String accept(Operation operation) {
        List<String> types = responseMediaTypes(operation);
        return types.isEmpty() ? null : String.join(", ", types);
    }

    private static List<String> responseMediaTypes(Operation operation) {
        Set<String> out = new LinkedHashSet<>();
        if (operation.responses() != null) {
            for (Response r : operation.responses()) {
                if (r.content() != null) r.content().forEach(m -> out.add(m.contentType()));
            }
        }
        return List.copyOf(out);
    }

    /**
     * Whether two requests send the same parameters and a body of the same members and items, whatever
     * their values: the full request is then the required one, though examples may give it other values.
     */
    static boolean sameShape(ValidRequests.Request a, ValidRequests.Request b) {
        return a.pathValues().size() == b.pathValues().size()
                && a.query().stream().map(ValidRequests.Pair::name).toList()
                        .equals(b.query().stream().map(ValidRequests.Pair::name).toList())
                && a.headers().stream().map(ValidRequests.Pair::name).toList()
                        .equals(b.headers().stream().map(ValidRequests.Pair::name).toList())
                && java.util.Objects.equals(a.contentType(), b.contentType())
                && java.util.Objects.equals(shape(a.body()), shape(b.body()));
    }

    /** A value's members and items, each leaf the same: what is left when the values are taken out. */
    private static Object shape(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = new java.util.TreeMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), shape(v)));
            return out;
        }
        if (value instanceof List<?> list) return list.stream().map(ContractCases::shape).toList();
        return value == null ? null : "";
    }

    /** A request with an {@code Accept} header, after its declared headers, or unchanged when there is none. */
    static ValidRequests.Request withAccept(ValidRequests.Request request, String accept) {
        if (accept == null) return request;
        List<ValidRequests.Pair> headers = new ArrayList<>(request.headers());
        headers.add(new ValidRequests.Pair(ACCEPT, accept));
        return new ValidRequests.Request(request.method(), request.pathTemplate(), request.pathValues(),
                request.query(), List.copyOf(headers), request.contentType(), request.body(), request.namedExample(),
                request.examples());
    }

    /** The first candidate no declared media type matches, or null. */
    static String firstUnmatched(List<String> candidates, List<String> declared) {
        for (String candidate : candidates) {
            if (declared.stream().noneMatch(d -> matches(d, candidate))) return candidate;
        }
        return null;
    }

    /**
     * Whether a declared media type, which may be a range such as {@code application/*} or
     * {@code *}{@code /*}, matches a concrete one. Parameters are ignored, and so is case.
     */
    static boolean matches(String declared, String concrete) {
        String[] d = essence(declared);
        String[] c = essence(concrete);
        if (d[0].equals("*")) return true;
        if (!d[0].equals(c[0])) return false;
        return d[1].equals("*") || d[1].equals(c[1]);
    }

    private static String[] essence(String mediaType) {
        String bare = mediaType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        String[] parts = bare.split("/", 2);
        return new String[]{parts[0], parts.length > 1 ? parts[1] : "*"};
    }

    // ---------------------------------------------------------------- misc

    private static List<String> placeholders(String path) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = Pattern.compile("\\{([^}]+)}").matcher(path);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static Coverage uncovered(String status, Reason reason, String detail) {
        return new Coverage(status, List.of(), reason, detail);
    }
}
