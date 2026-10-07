package com.arc_e_tect.gradle.apionly.transcriberj;

import com.arc_e_tect.gradle.apionly.transcriberj.spi.Emitter;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Output;
import org.gradle.api.GradleException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * @param warnings          what the build is told once for this subscription: deprecated settings, and
 *                          generated directories IntelliJ IDEA will not resolve in every source set
 */
record EmitterPlan(String contract, String schemaClasses, List<String> coreSourceSets,
                   Map<String, Placement> emitters, List<String> warnings) {

    /** Each source set compiles the schema classes itself. */
    static final String PER_SOURCE_SET = "perSourceSet";

    /** One source set compiles the schema classes, and every listed source set depends on it. */
    static final String SHARED = "shared";

    /** Where the migration guide for the settings deprecated before 1.0.0 is. */
    static final String MIGRATION_GUIDE = "https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/blob/main/"
            + "docs/migration/to-per-emitter-configuration/overview.adoc";

    /** Where the README says why IntelliJ hides a directory shared with {@code test}, and what to do. */
    static final String IDE_TEST_SOURCE_SET = "https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/blob/main/"
            + "api-only-transcriberj/README.adoc#ide-test-source-set";

    /**
     * The source sets IntelliJ's Gradle import keeps a shared directory in: a directory that one
     * of these shares with any other source set is removed from every other module.
     */
    static final List<String> IDE_OWNING_SOURCE_SETS = List.of("main", "test");

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
        ideHiddenWarning(subscription, schemaClasses, coreSourceSets, placements.values()).ifPresent(warnings::add);
        return new EmitterPlan(contract, schemaClasses, List.copyOf(coreSourceSets), placements, List.copyOf(warnings));
    }

    /**
     * The warning for a build whose generated directories IntelliJ will not resolve everywhere they
     * are compiled: a directory added to {@code main} or {@code test} and to another source set is
     * kept in that one module by IntelliJ's Gradle import, whatever added it. The Gradle build is not
     * affected, so this warns rather than fails.
     *
     * @param subscription   how the subscription is named in messages
     * @param schemaClasses  the {@code schemaClasses} setting
     * @param coreSourceSets the subscription's {@code sourceSets}
     * @param placements     every emitter's part of the plan
     * @return the warning, or empty when no directory is in main or test and in another source set
     */
    private static Optional<String> ideHiddenWarning(String subscription, String schemaClasses,
                                                     List<String> coreSourceSets, Collection<Placement> placements) {
        List<String> parts = new ArrayList<>();
        List<String> fixes = new ArrayList<>();
        if (PER_SOURCE_SET.equals(schemaClasses)) {
            ideOwner(coreSourceSets).ifPresent(owner -> {
                parts.add("the schema classes (" + String.join(", ", coreSourceSets) + ")");
                fixes.add("leave '" + owner + "' out of the subscription's sourceSets, or set schemaClasses = '"
                        + SHARED + "'");
            });
        }
        for (Placement placement : placements) {
            ideOwner(placement.sourceSets()).ifPresent(owner -> {
                String emitterName = "emitter('" + placement.id() + "')";
                parts.add(emitterName + "'s output (" + String.join(", ", placement.sourceSets()) + ")");
                fixes.add("name " + emitterName + "'s sourceSets without '" + owner + "'");
            });
        }
        if (parts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(subscription + ": IntelliJ IDEA will not resolve " + String.join(" or ", parts)
                + " outside the main or test module. Each is compiled in main or test and in another source set, "
                + "and IntelliJ's Gradle import keeps a directory that main or test shares with another source set "
                + "in that one module. The Gradle build is not affected. To fix it, " + String.join("; ", fixes)
                + ". See " + IDE_TEST_SOURCE_SET);
    }

    /**
     * The source set IntelliJ would keep a directory in, when these source sets share it with it.
     *
     * @param sourceSets the source sets a directory is added to
     * @return {@code main} or {@code test}, or empty when the directory is in neither or in it alone
     */
    private static Optional<String> ideOwner(List<String> sourceSets) {
        if (sourceSets.size() < 2) {
            return Optional.empty();
        }
        return IDE_OWNING_SOURCE_SETS.stream().filter(sourceSets::contains).findFirst();
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
