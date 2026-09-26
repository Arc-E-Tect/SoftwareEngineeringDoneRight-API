package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.model.Finding;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Treatment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a generation run could not do in full, and what the specification could do
 * about it: the end-of-run report D14 asks for.
 */
public final class GenerationReport {

    /**
     * A generated method that throws instead of doing its work.
     *
     * @param emitter   the emitter that wrote it
     * @param className the class it is in
     * @param method    the method
     * @param finding   the construct it cannot represent
     */
    public record Degraded(String emitter, String className, String method, Finding finding) {
    }

    /**
     * Something the specification could do better, and how.
     *
     * @param location the JSON pointer it concerns
     * @param advice   what to do
     */
    public record Recommendation(String location, String advice) {
    }

    /**
     * A generated method that throws because no valid value could be generated for it:
     * none exists, this generator cannot find one, or it reaches a construct no rule
     * represents yet.
     *
     * @param className the class it is in
     * @param method    the method
     * @param location  the JSON pointer of the schema or parameter that has no valid value
     * @param reason    why
     */
    public record NoValidValue(String className, String method, String location, String reason) {
    }

    /**
     * A parameter no valid value is generated for yet.
     *
     * @param className the operation's class
     * @param location  the JSON pointer of the parameter
     * @param name      its name
     * @param reason    what is not supported yet
     */
    public record UnsupportedParameter(String className, String location, String name, String reason) {
    }

    private final List<Degraded> degraded = new ArrayList<>();
    private final List<NoValidValue> noValidValue = new ArrayList<>();
    private final List<UnsupportedParameter> unsupportedParameters = new ArrayList<>();
    private final List<Map<String, Object>> validBodies = new ArrayList<>();
    private final List<Map<String, Object>> validRequests = new ArrayList<>();
    private final List<Recommendation> recommendations = new ArrayList<>();
    private final List<Finding> undecided = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final List<Map<String, Object>> invalidRequests = new ArrayList<>();
    private final List<Map<String, Object>> coverage = new ArrayList<>();
    private final List<Map<String, Object>> gaps = new ArrayList<>();
    private final Map<String, String> formatRecommendations = new java.util.LinkedHashMap<>();

    /** Creates an empty report. */
    public GenerationReport() {
    }

    void degraded(Degraded entry) {
        degraded.add(entry);
    }

    void noValidValue(NoValidValue entry) {
        noValidValue.add(entry);
    }

    void unsupportedParameter(UnsupportedParameter entry) {
        unsupportedParameters.add(entry);
    }

    /** Records the valid bodies of one class: each variant's value, or why it has none. */
    void validBody(Map<String, Object> entry) {
        validBodies.add(entry);
    }

    /** Records the valid requests of one operation's class. */
    void validRequest(Map<String, Object> entry) {
        validRequests.add(entry);
    }

    /**
     * Every generated method that throws because no valid value could be generated for it.
     *
     * @return the entries, in the order they were written
     */
    public List<NoValidValue> noValidValue() {
        return List.copyOf(noValidValue);
    }

    /**
     * Every parameter no valid value is generated for yet.
     *
     * @return the entries, in contract order
     */
    public List<UnsupportedParameter> unsupportedParameters() {
        return List.copyOf(unsupportedParameters);
    }

    /** Records one operation's invalid-request cases, the coverage of its constraints, and its gap if it has one. */
    void invalidRequests(String className, InvalidRequests.Result result, String status) {
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("class", className);
        entry.put("location", result.location());
        entry.put("method", result.operation().method().name());
        entry.put("pathTemplate", result.operation().path());
        entry.put("declaresInvalidRequestStatus", result.declared());
        entry.put("cases", result.cases().stream().map(CoreEmitter::caseJson).toList());
        invalidRequests.add(entry);
        for (InvalidRequests.Constraint c : result.constraints()) {
            Map<String, Object> e = new java.util.LinkedHashMap<>();
            e.put("class", className);
            e.put("operation", result.location());
            e.put("in", c.in);
            e.put("name", c.name);
            e.put("mediaType", c.mediaType);
            e.put("pointer", c.pointer);
            e.put("keyword", c.keyword);
            e.put("schemaLocation", c.schemaLocation);
            if (c.reason == null) {
                e.put("cases", c.cases());
            } else {
                Map<String, Object> why = new java.util.LinkedHashMap<>();
                why.put("code", c.reason.name());
                why.put("label", c.reason.label);
                why.put("detail", c.detail);
                e.put("uncovered", why);
            }
            coverage.add(e);
        }
        if (result.gap()) {
            Map<String, Object> gap = new java.util.LinkedHashMap<>();
            gap.put("class", className);
            gap.put("operation", result.location());
            gap.put("recommendation", "declare a " + status + " response, so that its " + result.constraints().size()
                    + " constraint(s) on request input can be tested with invalid requests");
            gaps.add(gap);
        }
    }

    void formatRecommendation(String location, String format) {
        formatRecommendations.put(location, format);
    }

    private static String formatAdvice(String format) {
        return "format " + format + " has no pattern; declare one, since what " + format + " accepts differs between "
                + "validators, and a pattern says exactly";
    }

    /**
     * How many invalid-request cases were derived.
     *
     * @return the count
     */
    public int invalidRequestCases() {
        return invalidRequests.stream().mapToInt(e -> ((List<?>) e.get("cases")).size()).sum();
    }

    /**
     * How many constraints on request input have a case, and how many have none, by reason.
     *
     * @return the counts: {@code covered}, then each reason's label, in order of first use
     */
    public Map<String, Integer> constraintCoverage() {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        out.put("covered", 0);
        for (Map<String, Object> e : coverage) {
            if (e.containsKey("cases")) {
                out.merge("covered", 1, Integer::sum);
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object> why = (Map<String, Object>) e.get("uncovered");
                out.merge((String) why.get("label"), 1, Integer::sum);
            }
        }
        return out;
    }

    /**
     * The operations that constrain their request input but declare no invalid-request status.
     *
     * @return their locations, in contract order
     */
    public List<String> gaps() {
        return gaps.stream().map(g -> (String) g.get("operation")).toList();
    }

    /** The summary line of the invalid-request cases, as the build log prints it. */
    String invalidRequestSummary() {
        Map<String, Integer> counts = constraintCoverage();
        int covered = counts.remove("covered");
        int uncovered = counts.values().stream().mapToInt(Integer::intValue).sum();
        StringBuilder out = new StringBuilder("Invalid requests: ").append(invalidRequestCases())
                .append(" case(s) derived, ").append(covered).append(" constraint(s) covered, ").append(uncovered)
                .append(" uncovered");
        if (!counts.isEmpty()) {
            out.append(" (").append(String.join(", ", counts.entrySet().stream()
                    .map(e -> e.getKey() + ": " + e.getValue()).toList())).append(')');
        }
        return out.append(", ").append(gaps.size()).append(" gap(s)").toString();
    }

    void recommend(String location, String advice) {
        recommendations.add(new Recommendation(location, advice));
    }

    void warn(String warning) {
        warnings.add(warning);
    }

    void findings(List<Finding> findings) {
        findings.stream().filter(f -> f.treatment() == Treatment.UNDECIDED).forEach(undecided::add);
    }

    /**
     * Every degraded method.
     *
     * @return the entries, in the order they were written
     */
    public List<Degraded> degraded() {
        return List.copyOf(degraded);
    }

    /**
     * Every recommendation.
     *
     * @return the entries, in the order they were made
     */
    public List<Recommendation> recommendations() {
        return List.copyOf(recommendations);
    }

    /**
     * Every construct no generation rule has been decided for.
     *
     * @return the findings, in contract order
     */
    public List<Finding> undecided() {
        return List.copyOf(undecided);
    }

    /**
     * Everything about the contract's design worth a second look.
     *
     * @return the warnings, in the order they were found
     */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /**
     * The report as text, for a file and a build log.
     *
     * @param contract the contract's name
     * @param version  the contract's version
     * @return the text
     */
    /**
     * Records something worth saying about this generation that is not a problem.
     *
     * @param note the note
     */
    public void note(String note) {
        notes.add(note);
    }

    /**
     * What this generation had to say about itself.
     *
     * @return the notes, in the order they were made
     */
    public List<String> notes() {
        return List.copyOf(notes);
    }

    /**
     * The report as text, for a file and a build log.
     *
     * @param contract the contract's name
     * @param version  the contract's version
     * @return the text
     */
    public String render(String contract, String version) {
        StringBuilder out = new StringBuilder();
        out.append("API-Only TranscriberJ: ").append(contract).append(' ').append(version).append('\n');
        out.append(degraded.size()).append(" degraded method(s), ")
                .append(recommendations.size()).append(" recommendation(s), ")
                .append(undecided.size()).append(" undecided construct(s), ")
                .append(warnings.size()).append(" warning(s), ")
                .append(noValidValue.size()).append(" method(s) without a valid value, ")
                .append(unsupportedParameters.size()).append(" parameter(s) not supported yet\n");
        out.append(invalidRequestSummary()).append('\n');
        if (!warnings.isEmpty()) {
            out.append("\nWarnings:\n");
            warnings.forEach(w -> out.append("  ").append(w).append('\n'));
        }
        if (!notes.isEmpty()) {
            out.append("\nNotes:\n");
            notes.forEach(n -> out.append("  ").append(n).append('\n'));
        }
        if (!degraded.isEmpty()) {
            out.append("\nDegraded methods -- these throw UnsupportedOperationException:\n");
            for (Degraded d : degraded) {
                out.append("  ").append(d.className()).append('.').append(d.method())
                        .append(" [").append(d.emitter()).append("]: ")
                        .append(d.finding().construct()).append(" at ").append(d.finding().location())
                        .append(" (").append(d.finding().detail()).append(')');
                if (d.finding().remedy() != null) out.append("; remedy: ").append(d.finding().remedy());
                out.append('\n');
            }
        }
        if (!recommendations.isEmpty()) {
            out.append("\nRecommendations:\n");
            for (Recommendation r : recommendations) {
                out.append("  ").append(r.location()).append(": ").append(r.advice()).append('\n');
            }
        }
        if (!undecided.isEmpty()) {
            out.append("\nUndecided constructs -- no generation rule exists for these yet:\n");
            for (Finding f : undecided) {
                out.append("  ").append(f.location()).append(": ").append(f.construct())
                        .append(" (").append(f.detail()).append(")\n");
            }
        }
        if (!noValidValue.isEmpty()) {
            out.append("\nNo valid value could be generated -- these methods throw UnsupportedOperationException:\n");
            for (NoValidValue n : noValidValue) {
                out.append("  ").append(n.className()).append('.').append(n.method()).append(" at ")
                        .append(n.location()).append(": ").append(n.reason()).append('\n');
            }
        }
        if (!unsupportedParameters.isEmpty()) {
            out.append("\nParameters not supported yet -- no valid value is generated for these:\n");
            for (UnsupportedParameter u : unsupportedParameters) {
                out.append("  ").append(u.className()).append(' ').append(u.name()).append(" at ")
                        .append(u.location()).append(": ").append(u.reason()).append('\n');
            }
        }
        if (!gaps.isEmpty()) {
            out.append("\nGaps -- these operations constrain their request input but declare no invalid-request status:\n");
            for (Map<String, Object> g : gaps) {
                out.append("  ").append(g.get("operation")).append(" (").append(g.get("class")).append("): ")
                        .append(g.get("recommendation")).append('\n');
            }
        }
        if (!formatRecommendations.isEmpty()) {
            out.append("\nFormat recommendations:\n");
            formatRecommendations.forEach((location, format) -> out.append("  ").append(location).append(": ")
                    .append(formatAdvice(format)).append('\n'));
        }
        if (!coverage.isEmpty()) {
            out.append("\nConstraint coverage -- every constraint on request input, and the cases that cover it:\n");
            for (Map<String, Object> e : coverage) {
                out.append("  ").append(e.get("class")).append(' ').append(e.get("in"));
                if (e.get("name") != null) out.append(' ').append(e.get("name"));
                if (e.get("mediaType") != null) out.append(' ').append(e.get("mediaType"));
                if (!((String) e.get("pointer")).isEmpty()) out.append(' ').append(e.get("pointer"));
                out.append(' ').append(e.get("keyword")).append(" (").append(e.get("schemaLocation")).append("): ");
                if (e.containsKey("cases")) {
                    out.append("covered by ").append(String.join(", ", ((List<?>) e.get("cases")).stream()
                            .map(String::valueOf).toList()));
                } else {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> why = (Map<String, Object>) e.get("uncovered");
                    out.append("uncovered, ").append(why.get("label")).append(" -- ").append(why.get("detail"));
                }
                out.append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Every generated valid value, by class and location, as JSON: members sorted by name,
     * no insignificant whitespace, and a final newline, so the same contract gives the
     * same bytes.
     *
     * @param contract the contract's name
     * @param version  the contract's version
     * @return the JSON text
     */
    public String renderValidValues(String contract, String version) {
        Map<String, Object> out = new java.util.TreeMap<>();
        out.put("schemaVersion", java.math.BigDecimal.ONE);
        out.put("contract", contract);
        out.put("version", version);
        out.put("bodies", validBodies.stream().sorted(java.util.Comparator.comparing(e -> (String) e.get("class")))
                .toList());
        out.put("requests", validRequests.stream()
                .sorted(java.util.Comparator.comparing(e -> (String) e.get("class"))).toList());
        List<Object> unsupported = new ArrayList<>();
        for (UnsupportedParameter u : unsupportedParameters) {
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("class", u.className());
            entry.put("location", u.location());
            entry.put("name", u.name());
            entry.put("reason", u.reason());
            unsupported.add(entry);
        }
        out.put("unsupportedParameters", unsupported);
        out.put("invalidRequests", invalidRequests.stream()
                .sorted(java.util.Comparator.comparing(e -> (String) e.get("class"))).toList());
        out.put("constraintCoverage", coverage);
        out.put("gaps", gaps);
        List<Object> formats = new ArrayList<>();
        formatRecommendations.forEach((location, format) -> {
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("location", location);
            entry.put("format", format);
            entry.put("advice", formatAdvice(format));
            formats.add(entry);
        });
        out.put("formatRecommendations", formats);
        return ValueJson.write(sorted(out)) + "\n";
    }

    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new java.util.TreeMap<>();
            map.forEach((k, v) -> out.put((String) k, sorted(v)));
            return out;
        }
        if (value instanceof List<?> list) return list.stream().map(GenerationReport::sorted).toList();
        return value;
    }
}
