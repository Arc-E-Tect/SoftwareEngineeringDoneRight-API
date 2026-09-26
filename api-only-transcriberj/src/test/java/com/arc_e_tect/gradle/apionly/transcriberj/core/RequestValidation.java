package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.networknt.schema.Error;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A whole request, as the machine-readable report records one, validated by the independent
 * oracle the way a server would: each parameter converted from the string it travels as to
 * what its schema says, and the body against the strictified document when strictness is on.
 * Shares no code with the generator.
 */
final class RequestValidation {

    /**
     * One error.
     *
     * @param in       {@code path}, {@code query}, {@code header} or {@code body}
     * @param name     the parameter's name, or null in the body
     * @param keyword  the keyword the validator reports
     * @param pointer  the instance location, as a JSON pointer into the parameter's value or the body
     * @param property the property a {@code required} or unevaluated member error names, or null
     */
    record Problem(String in, String name, String keyword, String pointer, String property) {
    }

    private static final Set<String> IGNORED_HEADERS = Set.of("accept", "content-type", "authorization");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");
    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

    private final Oracle oracle;
    private final Oracle bodies;

    RequestValidation(ValidValueFixtures.Fixture f, boolean strict) {
        this.oracle = f.oracle();
        this.bodies = strict ? new Oracle(Strictified.of(f.oracle().document)) : f.oracle();
    }

    /** Every problem with a request. */
    List<Problem> problems(JsonNode request) {
        List<Problem> out = new ArrayList<>();
        String path = request.get("pathTemplate").stringValue();
        String method = request.get("method").stringValue().toLowerCase(Locale.ROOT);
        String itemAt = "/paths/" + escape(path);
        String operationAt = itemAt + "/" + method;
        JsonNode operation = oracle.document.at(operationAt);
        List<String> placeholders = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(path);
        while (m.find()) placeholders.add(m.group(1));

        for (String[] p : parameters(itemAt, operationAt)) {
            JsonNode parameter = oracle.document.at(p[0]);
            String name = parameter.get("name").stringValue();
            String in = parameter.get("in").stringValue();
            String value = switch (in) {
                case "path" -> request.get("pathParameters").get(placeholders.indexOf(name)).stringValue();
                case "query" -> pair(request.get("query"), name);
                case "header" -> pair(request.get("headers"), name);
                default -> null;
            };
            if (value == null) {
                if (parameter.path("required").asBoolean(false)) out.add(new Problem(in, name, "required", "", null));
                continue;
            }
            for (Error e : oracle.validate(p[0] + "/schema", convert(value, oracle.resolve(p[0] + "/schema")))) {
                out.add(problem(in, name, e));
            }
        }

        String bodyAt = operationAt + "/requestBody";
        JsonNode body = operation.path("requestBody");
        if (body.has("$ref")) {
            bodyAt = body.get("$ref").stringValue().substring(1);
            body = oracle.document.at(bodyAt);
        }
        JsonNode contentType = request.get("contentType");
        if (contentType.isNull()) {
            if (body.path("required").asBoolean(false)) out.add(new Problem("body", null, "required", "", null));
        } else {
            String schemaAt = bodyAt + "/content/" + escape(contentType.stringValue()) + "/schema";
            for (Error e : bodies.validate(schemaAt, request.get("body"))) out.add(problem("body", null, e));
        }
        return out;
    }

    /** The pointers of an operation's parameters: its path item's, each replaced by the operation's own of that name. */
    private List<String[]> parameters(String itemAt, String operationAt) {
        List<String[]> own = new ArrayList<>();
        JsonNode ops = oracle.document.at(operationAt + "/parameters");
        for (int i = 0; i < ops.size(); i++) own.add(new String[]{located(operationAt + "/parameters/" + i)});
        List<String[]> out = new ArrayList<>();
        JsonNode items = oracle.document.at(itemAt + "/parameters");
        for (int i = 0; i < items.size(); i++) {
            String at = located(itemAt + "/parameters/" + i);
            String[] override = own.stream().filter(o -> same(o[0], at)).findFirst().orElse(null);
            if (override != null) {
                own.remove(override);
                out.add(override);
            } else {
                out.add(new String[]{at});
            }
        }
        out.addAll(own);
        out.removeIf(p -> {
            JsonNode parameter = oracle.document.at(p[0]);
            return parameter.path("in").stringValue("").equals("header")
                    && IGNORED_HEADERS.contains(parameter.path("name").stringValue("").toLowerCase(Locale.ROOT));
        });
        return out;
    }

    private String located(String at) {
        JsonNode node = oracle.document.at(at);
        return node.has("$ref") ? node.get("$ref").stringValue().substring(1) : at;
    }

    private boolean same(String a, String b) {
        JsonNode x = oracle.document.at(a);
        JsonNode y = oracle.document.at(b);
        return x.path("name").equals(y.path("name")) && x.path("in").equals(y.path("in"));
    }

    private static String pair(JsonNode pairs, String name) {
        for (JsonNode p : pairs) {
            if (p.get("name").stringValue().equals(name)) return p.get("value").stringValue();
        }
        return null;
    }

    /** A parameter's value as a server converts it: by the type its schema, or the first of its allOf, names. */
    private JsonNode convert(String value, JsonNode schema) {
        String type = type(schema, 0);
        if (("integer".equals(type) || "number".equals(type)) && NUMBER.matcher(value).matches()) {
            return JsonNodeFactory.instance.numberNode(new BigDecimal(value));
        }
        if ("boolean".equals(type) && (value.equals("true") || value.equals("false"))) {
            return JsonNodeFactory.instance.booleanNode(Boolean.parseBoolean(value));
        }
        return JsonNodeFactory.instance.stringNode(value);
    }

    private String type(JsonNode schema, int depth) {
        if (depth > 16) return null;
        if (schema.path("type").isString()) return schema.get("type").stringValue();
        if (schema.has("$ref")) return type(oracle.document.at(schema.get("$ref").stringValue().substring(1)), depth + 1);
        for (JsonNode branch : schema.path("allOf")) {
            String t = type(branch, depth + 1);
            if (t != null) return t;
        }
        return null;
    }

    private static Problem problem(String in, String name, Error e) {
        return new Problem(in, name, e.getKeyword(), pointer(e.getInstanceLocation()), e.getProperty());
    }

    /** An instance location as a JSON pointer, whatever form the validator prints it in. */
    static String pointer(com.networknt.schema.path.NodePath path) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < path.getNameCount(); i++) {
            out.append('/').append(escape(String.valueOf(path.getElement(i))));
        }
        return out.toString();
    }

    static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }
}
