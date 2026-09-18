package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.model.Finding;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Treatment;

import java.util.ArrayList;
import java.util.List;

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

    private final List<Degraded> degraded = new ArrayList<>();
    private final List<Recommendation> recommendations = new ArrayList<>();
    private final List<Finding> undecided = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    /** Creates an empty report. */
    public GenerationReport() {
    }

    void degraded(Degraded entry) {
        degraded.add(entry);
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
                .append(warnings.size()).append(" warning(s)\n");
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
        return out.toString();
    }
}
