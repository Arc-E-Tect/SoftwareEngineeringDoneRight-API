package com.arc_e_tect.gradle.apionly.transcriberj;

import com.arc_e_tect.gradle.apionly.transcriberj.spi.Emitter;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Output;
import org.gradle.api.GradleException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Where one subscription's output goes: the core's schema classes and each emitter's Java,
 * resources and files, checked against what each emitter declares it writes. Every rule a
 * configuration can break fails here, at configuration, naming the emitter, the source set and
 * the fix.
 *
 * @param contract          the subscription's contract
 * @param schemaClasses     how the schema classes reach the source sets: {@value #PER_SOURCE_SET} or
 *                          {@value #SHARED}
 * @param coreSourceSets    the source sets that use the schema classes
 * @param emitters          each emitter that runs, by id, in id order
 * @param warnings          what the build is told once for this subscription: deprecated settings
 */
record EmitterPlan(String contract, String schemaClasses, List<String> coreSourceSets,
                   Map<String, Placement> emitters, List<String> warnings) {

    /** Each source set compiles the schema classes itself. */
    static final String PER_SOURCE_SET = "perSourceSet";

    /** One source set compiles the schema classes, and every listed source set depends on it. */
    static final String SHARED = "shared";

    /** Where the migration guide for the settings deprecated before 1.0.0 is. */
    static final String MIGRATION_GUIDE = "https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/blob/main/"
            + "docs/guides/emitter-configuration/migrating.adoc";

    /**
     * One emitter's part of the plan.
     *
     * @param id         the emitter's id
     * @param options    the options it is given
     * @param produces   what it writes with them
     * @param sourceSets the source sets its Java and resources are compiled and packaged in
     * @param named      whether the build configures it with {@code emitter(...)}
     */
    record Placement(String id, Map<String, String> options, Set<Output> produces, List<String> sourceSets,
                     boolean named) {
    }

    /**
     * One {@code emitter(...)} block, as the build wrote it.
     *
     * @param sourceSets its {@code sourceSets}, or {@code null} when the build does not set them
     * @param options    its {@code options}; empty when the build sets none
     */
    record Configured(List<String> sourceSets, Map<String, String> options) {
    }

    /**
     * Plans one subscription.
     *
     * @param contract       the contract's name
     * @param schemaClasses  the {@code schemaClasses} setting
     * @param coreSourceSets the subscription's {@code sourceSets}
     * @param emitterOptions the deprecated {@code emitterOptions}
     * @param configured     every {@code emitter(...)} block, by id
     * @param loaded         every emitter on the {@code transcriberjEmitters} classpath
     * @return the plan
     * @throws GradleException when the configuration breaks a rule
     */
    static EmitterPlan of(String contract, String schemaClasses, List<String> coreSourceSets,
                          Map<String, Map<String, String>> emitterOptions, Map<String, Configured> configured,
                          List<Emitter> loaded) {
        String subscription = "apiOnlyTranscriberJ: subscription('" + contract + "')";
        if (!PER_SOURCE_SET.equals(schemaClasses) && !SHARED.equals(schemaClasses)) {
            throw new GradleException(subscription + " sets schemaClasses = '" + schemaClasses
                    + "', which is not a mode. Set it to '" + PER_SOURCE_SET + "', for each source set to compile the "
                    + "schema classes, or '" + SHARED + "', for one source set to compile them and the others to "
                    + "depend on it.");
        }
        Map<String, Emitter> byId = new TreeMap<>();
        loaded.forEach(e -> byId.put(e.id(), e));
        String available = byId.isEmpty() ? "none" : String.join(", ", byId.keySet());
        for (String id : configured.keySet()) {
            if (!byId.containsKey(id)) {
                throw new GradleException(subscription + " configures emitter('" + id + "'), but no emitter with "
                        + "that id is on the transcriberjEmitters classpath; the emitters there are " + available
                        + ". Add the emitter's library to transcriberjEmitters, or remove emitter('" + id + "').");
            }
        }
        for (String id : emitterOptions.keySet()) {
            if (!byId.containsKey(id)) {
                throw new GradleException(subscription + ": emitterOptions names " + id + ", but no emitter with "
                        + "that id is on the transcriberjEmitters classpath; the emitters there are " + available
                        + ". Add the emitter's library to transcriberjEmitters, or remove its options.");
            }
            Configured block = configured.get(id);
            if (block != null && !block.options().isEmpty()) {
                throw new GradleException(subscription + " sets the options of emitter '" + id + "' twice: in "
                        + "emitterOptions and in emitter('" + id + "') { options }. Keep emitter('" + id
                        + "') { options = ... } and remove '" + id + "' from emitterOptions, which is deprecated.");
            }
        }

        List<String> warnings = new ArrayList<>();
        if (!emitterOptions.isEmpty()) {
            warnings.add(subscription + " sets emitterOptions, which is deprecated and is removed in 1.0.0. "
                    + "Configure each emitter in its own block instead: emitter('<id>') { options = [...] }. "
                    + "See " + MIGRATION_GUIDE);
        }
        Map<String, Placement> placements = new LinkedHashMap<>();
        for (Emitter emitter : byId.values()) {
            String id = emitter.id();
            Configured block = configured.get(id);
            Map<String, String> options = new TreeMap<>(block != null && !block.options().isEmpty()
                    ? block.options() : emitterOptions.getOrDefault(id, Map.of()));
            Set<Output> produces = Set.copyOf(emitter.produces(Map.copyOf(options)));
            boolean onClasspath = produces.contains(Output.JAVA) || produces.contains(Output.RESOURCES);
            List<String> sourceSets = block != null && block.sourceSets() != null ? List.copyOf(block.sourceSets())
                    : onClasspath ? List.copyOf(coreSourceSets) : List.of();
            String emitterName = "emitter('" + id + "')";
            if (!onClasspath && !sourceSets.isEmpty()) {
                throw new GradleException(subscription + ": " + emitterName + " writes only files with the options "
                        + options + ", and files belong on no source set, but its sourceSets names "
                        + String.join(", ", sourceSets) + ". Remove sourceSets from " + emitterName + ".");
            }
            if (onClasspath && sourceSets.isEmpty()) {
                throw new GradleException(subscription + ": " + emitterName + " writes "
                        + (produces.contains(Output.JAVA) ? "Java sources" : "classpath resources")
                        + " with the options " + options + ", which need a source set, but its sourceSets is empty. "
                        + "Name one or more of the subscription's source sets (" + String.join(", ", coreSourceSets)
                        + "), or remove sourceSets from " + emitterName + " to use them all.");
            }
            for (String set : sourceSets) {
                if (!coreSourceSets.contains(set)) {
                    throw new GradleException(subscription + ": " + emitterName + " compiles in source set '" + set
                            + "', which does not see the schema classes: "
                            + (SHARED.equals(schemaClasses)
                            ? "it does not depend on the shared source set " + sharedSourceSet(contract)
                            : "it does not compile them")
                            + ". Add '" + set + "' to the subscription's sourceSets, or remove it from "
                            + emitterName + "'s.");
                }
            }
            if (block == null) {
                warnings.add(subscription + ": emitter '" + id + "' is on transcriberjEmitters but not configured "
                        + "with emitter('" + id + "'), so it runs with the subscription's source sets and "
                        + (emitterOptions.containsKey(id) ? "its emitterOptions" : "no options")
                        + ". From 1.0.0 only an emitter named with emitter(...) runs: add emitter('" + id
                        + "') {} to keep it. See " + MIGRATION_GUIDE);
            }
            placements.put(id, new Placement(id, Map.copyOf(options), produces, sourceSets, block != null));
        }
        return new EmitterPlan(contract, schemaClasses, List.copyOf(coreSourceSets), placements, List.copyOf(warnings));
    }

    /**
     * The shared source set's name: {@code transcriberj} and the contract's task-name suffix,
     * such as {@code transcriberjUserAccount}.
     *
     * @param contract the contract's name
     * @return the source set's name
     */
    static String sharedSourceSet(String contract) {
        return "transcriberj" + ApiOnlyTranscriberJPlugin.suffix(contract);
    }
}
