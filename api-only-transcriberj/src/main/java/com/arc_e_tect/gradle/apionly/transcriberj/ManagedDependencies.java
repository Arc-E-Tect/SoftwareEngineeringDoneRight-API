package com.arc_e_tect.gradle.apionly.transcriberj;

import com.arc_e_tect.gradle.apionly.transcriberj.spi.Emitter;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.ManagedDependency;
import org.gradle.api.GradleException;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The dependencies emitters declare for the code they generate (D18).
 *
 * <p>Each is added to the source sets the code is generated into with a
 * <em>preferred</em> version: Gradle uses it only when nothing else in the build,
 * declared or transitive, asks for a version, so a project's own choice always wins.
 * After resolution, a version the emitter was not tested with is reported.
 */
final class ManagedDependencies {

    private ManagedDependencies() {
    }

    /** Each emitter's dependencies, by emitter id, as the emitter libraries declare them. */
    static Map<String, List<ManagedDependency>> of(Set<File> emitterClasspath) {
        URL[] urls = emitterClasspath.stream().map(ManagedDependencies::url).toArray(URL[]::new);
        try (URLClassLoader loader = new URLClassLoader(urls, ManagedDependencies.class.getClassLoader())) {
            Map<String, List<ManagedDependency>> byEmitter = new LinkedHashMap<>();
            for (Emitter emitter : GenerateContractSourcesAction.emitters(loader)) {
                byEmitter.put(emitter.id(), List.copyOf(emitter.dependencies()));
            }
            return byEmitter;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static URL url(File file) {
        try {
            return file.toURI().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Reports every resolved version an emitter was not tested with.
     *
     * @param configuration the configuration that was resolved
     * @param resolved      {@code group:name} to the version resolved
     * @param byEmitter     each emitter's dependencies
     * @param strict        whether an untested version fails the build
     * @param warn          where a warning goes
     */
    static void check(String configuration, Map<String, String> resolved,
                      Map<String, List<ManagedDependency>> byEmitter, boolean strict, Consumer<String> warn) {
        List<String> problems = new ArrayList<>();
        byEmitter.forEach((emitter, dependencies) -> {
            for (ManagedDependency d : dependencies) {
                String version = resolved.get(d.group() + ":" + d.name());
                if (version != null && d.untestedFrom() != null && atLeast(version, d.untestedFrom())) {
                    problems.add("Emitter " + emitter + ": " + d.group() + ":" + d.name() + " " + version + " in "
                            + configuration + " is not tested with this plugin version; it is tested with versions "
                            + "below " + d.untestedFrom() + ", and adds " + d.pinnedVersion()
                            + " when the project declares none.");
                }
            }
        });
        if (strict && !problems.isEmpty()) {
            throw new GradleException(String.join("\n", problems)
                    + "\nThe build fails on this because apiOnlyTranscriberJ.strictDependencies is set.");
        }
        problems.forEach(warn);
    }

    /** Whether one version is at or above another, comparing their leading numbers. */
    static boolean atLeast(String version, String bound) {
        List<Integer> a = numbers(version);
        List<Integer> b = numbers(bound);
        if (a.isEmpty()) return false;
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            int x = i < a.size() ? a.get(i) : 0;
            int y = i < b.size() ? b.get(i) : 0;
            if (x != y) return x > y;
        }
        return true;
    }

    private static List<Integer> numbers(String version) {
        List<Integer> numbers = new ArrayList<>();
        for (String part : version.split("[.\\-+]")) {
            if (!part.matches("\\d+")) break;
            numbers.add(Integer.parseInt(part));
        }
        return numbers;
    }
}
