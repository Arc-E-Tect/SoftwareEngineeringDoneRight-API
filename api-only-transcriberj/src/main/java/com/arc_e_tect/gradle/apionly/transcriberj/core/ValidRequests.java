package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.model.ContractModel;
import com.arc_e_tect.gradle.apionly.transcriberj.model.MediaType;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Operation;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Parameter;
import com.arc_e_tect.gradle.apionly.transcriberj.model.PathItem;
import com.arc_e_tect.gradle.apionly.transcriberj.model.RequestBody;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Schema;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A valid request for each operation: every parameter the variant includes, serialised as
 * it travels, and a valid body.
 *
 * <p>Parameters travel as strings, so an integer's value is {@code "0"}. Only a primitive
 * schema with the default {@code style} for its location -- {@code simple} for path and
 * header, {@code form} for query -- is supported; anything else is reported as not
 * supported yet and given no value. Header parameters named {@code Accept},
 * {@code Content-Type} or {@code Authorization} are ignored, as OpenAPI says they are.
 */
final class ValidRequests {

    /** Which valid request. */
    enum Kind {
        /** Required parameters, and the minimal body when the operation declares one. */
        REQUIRED,
        /** Every supported parameter, and the full body when the operation declares one. */
        FULL,
        /** Required parameters, and no body: for an operation whose body is optional. */
        NO_BODY
    }

    /** One name and value, as a query parameter or header travels. */
    record Pair(String name, String value) {
    }

    /**
     * A request.
     *
     * @param method       the HTTP method
     * @param pathTemplate the path as the contract writes it
     * @param pathValues   the value of each placeholder, in the order the path names them
     * @param query        the query parameters, in declaration order
     * @param headers      the header parameters, in declaration order
     * @param contentType  the body's content type, or null without a body
     * @param body         the body as JSON text, or null without one
     */
    record Request(String method, String pathTemplate, List<String> pathValues, List<Pair> query, List<Pair> headers,
                   String contentType, Object body) {
    }

    /** A parameter no value is generated for yet. */
    record Unsupported(String location, String name, String reason) {
    }

    /** A parameter, and where it is written. */
    private record Located(Parameter parameter, String location) {
    }

    private static final Set<String> IGNORED_HEADERS = Set.of("accept", "content-type", "authorization");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");
    private static final Map<String, String> DEFAULT_STYLE = Map.of("path", "simple", "header", "simple",
            "query", "form");

    private final ContractModel model;
    private final Shapes shapes;
    private final ValidValues values;

    ValidRequests(ContractModel model, Shapes shapes, ValidValues values) {
        this.model = model;
        this.shapes = shapes;
        this.values = values;
    }

    /** Whether an operation has {@link Kind#NO_BODY}: it declares a body, and does not require it. */
    static boolean bodyOptional(Operation operation) {
        RequestBody body = operation.requestBody();
        return body != null && body.content() != null && !body.content().isEmpty()
                && !Boolean.TRUE.equals(body.required());
    }

    /** Every parameter of an operation no value is generated for, and why. */
    List<Unsupported> unsupported(Operation operation) {
        List<Unsupported> out = new ArrayList<>();
        for (Located p : parameters(operation)) {
            String reason = unsupported(p.parameter());
            if (reason != null) out.add(new Unsupported(p.location(), p.parameter().name(), reason));
        }
        return out;
    }

    /**
     * A valid request.
     *
     * @throws ValidValues.Unsatisfiable when a parameter or the body has no valid value, or
     *                                   a parameter the request needs is not supported yet
     * @throws Shapes.Unrepresentable    when a value reaches a construct no rule represents
     */
    Request request(Operation operation, Kind kind) {
        ValidValues.Variant variant = kind == Kind.FULL ? ValidValues.Variant.FULL : ValidValues.Variant.REQUIRED;
        Map<String, String> path = new java.util.HashMap<>();
        List<Pair> query = new ArrayList<>();
        List<Pair> headers = new ArrayList<>();
        for (Located located : parameters(operation)) {
            Parameter p = located.parameter();
            boolean needed = "path".equals(p.in()) || Boolean.TRUE.equals(p.required());
            if (!needed && kind != Kind.FULL) continue;
            String reason = unsupported(p);
            if (reason != null) {
                if (needed) {
                    throw new ValidValues.Unsatisfiable(located.location(), "required parameter " + p.name() + ": "
                            + reason);
                }
                continue;
            }
            String value = wire(values.value(p.schema(), located.location() + "/schema", variant), located);
            switch (p.in()) {
                case "path" -> path.put(p.name(), value);
                case "query" -> query.add(new Pair(p.name(), value));
                default -> headers.add(new Pair(p.name(), value));
            }
        }
        List<String> pathValues = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(operation.path());
        while (m.find()) {
            String value = path.get(m.group(1));
            if (value == null) {
                throw new ValidValues.Unsatisfiable(CoreClassNames.operationLocation(operation), "the path names {"
                        + m.group(1) + "}, but the operation declares no path parameter of that name");
            }
            pathValues.add(value);
        }
        String contentType = null;
        Object body = null;
        if (kind != Kind.NO_BODY && operation.requestBody() != null && operation.requestBody().content() != null
                && !operation.requestBody().content().isEmpty()) {
            RequestBody requestBody = operation.requestBody();
            String at = requestBody.reference() != null
                    ? "/components/requestBodies/" + Shapes.escape(requestBody.reference().name())
                    : CoreClassNames.operationLocation(operation) + "/requestBody";
            MediaType json = requestBody.content().stream().filter(t -> isJson(t.contentType())).findFirst()
                    .orElseThrow(() -> new ValidValues.Unsatisfiable(at, "no JSON content type among "
                            + requestBody.content().stream().map(MediaType::contentType).toList()
                            + "; this generator writes JSON bodies only"));
            contentType = json.contentType();
            String schemaAt = at + "/content/" + Shapes.escape(json.contentType()) + "/schema";
            body = json.schema() == null ? ValueJson.NULL : values.value(json.schema(), schemaAt, variant);
        }
        return new Request(operation.method().name(), operation.path(), List.copyOf(pathValues), List.copyOf(query),
                List.copyOf(headers), contentType, body);
    }

    /** {@code application/json}, or any {@code +json} type, parameters aside. */
    static boolean isJson(String contentType) {
        String base = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return base.equals("application/json") || base.endsWith("+json");
    }

    /**
     * The operation's parameters: its path item's, each replaced where the operation
     * declares one of the same name and location, then the operation's own; the
     * ignored headers left out.
     */
    private List<Located> parameters(Operation operation) {
        List<Located> out = new ArrayList<>();
        String itemAt = "/paths/" + Shapes.escape(operation.path());
        PathItem item = model.paths().stream().filter(i -> i.operations().contains(operation)).findFirst().orElse(null);
        List<Located> own = new ArrayList<>();
        if (operation.parameters() != null) {
            for (int i = 0; i < operation.parameters().size(); i++) {
                Parameter p = operation.parameters().get(i);
                own.add(new Located(p, location(p, CoreClassNames.operationLocation(operation) + "/parameters/" + i)));
            }
        }
        if (item != null && item.parameters() != null) {
            for (int i = 0; i < item.parameters().size(); i++) {
                Parameter p = item.parameters().get(i);
                Located override = own.stream().filter(o -> same(o.parameter(), p)).findFirst().orElse(null);
                if (override != null) {
                    own.remove(override);
                    out.add(override);
                } else {
                    out.add(new Located(p, location(p, itemAt + "/parameters/" + i)));
                }
            }
        }
        out.addAll(own);
        out.removeIf(l -> "header".equals(l.parameter().in()) && l.parameter().name() != null
                && IGNORED_HEADERS.contains(l.parameter().name().toLowerCase(Locale.ROOT)));
        return out;
    }

    private static String location(Parameter p, String written) {
        return p.reference() != null ? "/components/parameters/" + Shapes.escape(p.reference().name()) : written;
    }

    private static boolean same(Parameter a, Parameter b) {
        return a.name() != null && a.name().equals(b.name()) && a.in() != null && a.in().equals(b.in());
    }

    /** Why no value is generated for a parameter, or null when one is. */
    private String unsupported(Parameter p) {
        if (p.in() == null || !DEFAULT_STYLE.containsKey(p.in())) {
            return "a parameter in " + p.in() + " is not supported yet";
        }
        if (p.schema() == null) {
            return "a parameter described by content rather than by a schema is not supported yet";
        }
        Object style = p.other().get("style");
        if (style != null && !style.equals(DEFAULT_STYLE.get(p.in()))) {
            return "style " + style + " is not supported yet; only " + DEFAULT_STYLE.get(p.in()) + " is, the default "
                    + "for a " + p.in() + " parameter";
        }
        Object explode = p.other().get("explode");
        boolean defaultExplode = p.in().equals("query");
        if (explode != null && !explode.equals(defaultExplode)) {
            return "explode: " + explode + " is not supported yet; only the default, " + defaultExplode + ", is";
        }
        Schema s = p.schema();
        if (shapes.isArray(s)) return "a parameter whose schema is an array is not supported yet";
        if (shapes.isObject(s)) return "a parameter whose schema is an object is not supported yet";
        return null;
    }

    /** A value as it travels: a string as it is, a number and a boolean as JSON writes them, null as nothing. */
    private static String wire(Object value, Located located) {
        if (value instanceof String s) return s;
        if (value instanceof BigDecimal n) return ValueJson.number(n);
        if (value instanceof Boolean b) return b.toString();
        if (value == ValueJson.NULL) return "";
        throw new ValidValues.Unsatisfiable(located.location(), "parameter " + located.parameter().name()
                + " has no primitive value");
    }
}
