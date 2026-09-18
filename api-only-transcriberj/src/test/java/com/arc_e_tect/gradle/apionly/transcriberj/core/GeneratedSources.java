package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.spi.Emitter;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Generating a contract's sources in a test, compiling them, and calling into them. */
final class GeneratedSources {

    static final String PACKAGE = "com.example.contract";
    static final Path FIXTURES = Path.of(System.getProperty("transcriberj.fixtures"));

    final Path sources;
    final GenerationReport report;
    private ClassLoader loader;

    private GeneratedSources(Path sources, GenerationReport report) {
        this.sources = sources;
        this.report = report;
    }

    static Settings settings(String contract) {
        return new Settings(contract, PACKAGE, false, "PLACEHOLDER", 2);
    }

    static GeneratedSources generate(Path contract, String version, Path into, List<Emitter> emitters) {
        return generate(contract, version, into, settings("test-contract"), emitters);
    }

    static GeneratedSources generate(Path contract, String version, Path into, Settings settings,
                                     List<Emitter> emitters) {
        return generate(contract, version, into, settings, emitters, null);
    }

    static GeneratedSources generate(Path contract, String version, Path into, Settings settings,
                                     List<Emitter> emitters, Path endpointIndex) {
        Path sources = into.resolve("sources");
        GenerationReport report = Generation.run(contract, version, "a".repeat(64), settings, sources, emitters,
                endpointIndex);
        return new GeneratedSources(sources, report);
    }

    /** Both of a contract's documents, generated into one tree. */
    static GeneratedSources generate(Path contract, Path asyncContract, String version, Path into,
                                     Settings settings, List<Emitter> emitters) {
        Path sources = into.resolve("sources");
        GenerationReport report = Generation.run(contract, asyncContract, version, "a".repeat(64), settings,
                sources, emitters, null);
        return new GeneratedSources(sources, report);
    }

    /** The simple name of every class generated, in no particular order. */
    List<String> names() {
        try (Stream<Path> walk = Files.walk(sources)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    .map(p -> p.getFileName().toString().replace(".java", "")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static GeneratedSources generate(String yaml, Path into, List<Emitter> emitters) {
        try {
            Path contract = into.resolve("openapi.yaml");
            Files.writeString(contract, yaml);
            return generate(contract, "1.0.0", into, emitters);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String source(String simpleName) {
        return source(PACKAGE, simpleName);
    }

    String source(String packageName, String simpleName) {
        try {
            return Files.readString(file(packageName, simpleName), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Path file(String packageName, String simpleName) {
        return sources.resolve(packageName.replace('.', '/')).resolve(simpleName + ".java");
    }

    List<String> classNames() {
        try (Stream<Path> files = Files.list(sources.resolve(PACKAGE.replace('.', '/')))) {
            return files.filter(Files::isRegularFile)
                    .map(f -> f.getFileName().toString().replace(".java", "")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Compiles every generated source, failing on any error or warning javac reports. */
    ClassLoader compile() {
        if (loader != null) return loader;
        try {
            Path classes = Files.createDirectories(sources.resolveSibling("classes"));
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            List<Path> files;
            try (Stream<Path> walk = Files.walk(sources)) {
                files = walk.filter(p -> p.toString().endsWith(".java")).toList();
            }
            try (StandardJavaFileManager fileManager =
                         compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
                boolean ok = compiler.getTask(null, fileManager, diagnostics,
                        List.of("-d", classes.toString(), "-Xlint:all", "-Xdoclint:all,-missing", "--release", "21"),
                        null, fileManager.getJavaFileObjectsFromPaths(files)).call();
                List<String> problems = diagnostics.getDiagnostics().stream()
                        .filter(d -> d.getKind() == Diagnostic.Kind.ERROR || d.getKind() == Diagnostic.Kind.WARNING
                                || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
                        .map(Object::toString).toList();
                assertThat(problems).as("javac diagnostics").isEmpty();
                assertThat(ok).isTrue();
            }
            loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, GeneratedSources.class.getClassLoader());
            return loader;
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Class<?> type(String simpleName) {
        try {
            return Class.forName(PACKAGE + "." + simpleName, true, compile());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    Object call(String simpleName, String method, Class<?>[] types, Object... args) throws Throwable {
        Method m = type(simpleName).getDeclaredMethod(method, types);
        m.setAccessible(true);
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    Object constant(String simpleName, String name) {
        try {
            var field = type(simpleName).getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
