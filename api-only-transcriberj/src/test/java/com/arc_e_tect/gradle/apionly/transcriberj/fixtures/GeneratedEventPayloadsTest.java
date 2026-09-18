package com.arc_e_tect.gradle.apionly.transcriberj.fixtures;

import com.arc_e_tect.gradle.apionly.transcriberj.core.Generation;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The strict half of the safety net for events: every payload the reference
 * implementation's hand-written classes produce is what the generated classes produce,
 * byte for byte, but for the newline every generated body ends with.
 */
class GeneratedEventPayloadsTest {

    private static final String PACKAGE = "com.example.contract";
    private static final Path FIXTURES = Path.of(System.getProperty("transcriberj.fixtures"));

    @TempDir
    static Path directory;

    private static ClassLoader generated;

    private final List<EventPayloads.Case> cases = EventPayloads.cases();

    @BeforeAll
    static void generate() throws IOException {
        Path sources = directory.resolve("sources");
        Generation.run(FIXTURES.resolve("contracts/user-account/openapi.yaml"),
                FIXTURES.resolve("contracts/user-account/asyncapi.yaml"), "1.0.0", "x",
                new Settings("user-account", PACKAGE, false, "PLACEHOLDER", 3), sources, List.of(), null);

        Path classes = Files.createDirectories(directory.resolve("classes"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(sources)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            boolean ok = compiler.getTask(null, fileManager, diagnostics,
                    List.of("-d", classes.toString(), "--release", "21"), null,
                    fileManager.getJavaFileObjectsFromPaths(files)).call();
            assertThat(diagnostics.getDiagnostics()).isEmpty();
            assertThat(ok).isTrue();
        }
        generated = new URLClassLoader(new URL[]{classes.toUri().toURL()},
                GeneratedEventPayloadsTest.class.getClassLoader());
    }

    @TestFactory
    Stream<DynamicTest> everyEventPayloadIsTheOneTheHandwrittenClassProduces() {
        return cases.stream().map(c -> DynamicTest.dynamicTest(c.id(), () -> {
            assertThat(c.expected()).as("%s has no recorded payload", c.id()).isNotNull();
            // Recorded from the hand-written class, so this is what the reference ships today.
            assertThat(c.handwritten()).isEqualTo(c.expected());
            // The generated body is that payload, plus the newline every generated body ends with.
            assertThat(c.generated(PACKAGE, generated)).isEqualTo(c.expected() + "\n");
        }));
    }

    @Test
    void everyEventOfTheReferenceImplementationIsRecorded() throws IOException {
        Path handwritten = Path.of("src/handwritten/java/com/arc_e_tect/book/sedr/events");
        try (Stream<Path> files = Files.list(handwritten)) {
            List<String> declared = files.map(f -> f.getFileName().toString().replaceFirst("\\.java$", ""))
                    .filter(name -> name.endsWith("EventV1")).sorted().toList();

            assertThat(cases).extracting(EventPayloads.Case::className).containsExactlyElementsOf(declared);
        }
    }

    @Test
    void anEventWhoseAdapterShapedTheJsonItselfWouldBeCaught() {
        // The defect these classes once carried: an adapter publishing
        // "REGISTRATION_INITIATED:" + username where the schema describes an object.
        EventPayloads.Case registrationInitiated = cases.stream()
                .filter(c -> c.className().equals("RegistrationInitiatedEventV1")).findFirst().orElseThrow();

        String body = registrationInitiated.generated(PACKAGE, generated);

        assertThat(body).startsWith("{").contains("\"username\":\"alice\"")
                .isNotEqualTo("REGISTRATION_INITIATED:alice");
    }
}
