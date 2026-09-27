package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.model.Construct;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Emitter;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.EmitterContext;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.ManagedDependency;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Output;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T18.14, in-process: the core and each emitter run as separate phases over one derivation,
 * each into directories of its own, and an emitter declares what it writes for its options.
 */
@DisplayName("T18.14 Emitter phases and the SPI")
class EmitterPhasesTest {

    static final Path CONTRACT = Path.of(System.getProperty("transcriberj.referenceApi"), "user-account/openapi.yaml");

    @TempDir
    Path dir;

    static Settings settings(Map<String, Map<String, String>> options) {
        return new Settings("user-account", "com.example.contract", false, "P", 3, null, "400", true, List.of(),
                options);
    }

    static String version() throws IOException {
        return com.arc_e_tect.gradle.apionly.transcriberj.model.ContractParser.parse(CONTRACT, null).version();
    }

    static Map<String, String> files(Path root) throws IOException {
        Map<String, String> out = new TreeMap<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                out.put(root.relativize(p).toString().replace('\\', '/'), Files.readString(p));
            }
        }
        return out;
    }

    @Test
    void theCoreWrittenOnItsOwnIsWhatAWholeRunWritesForIt() throws IOException {
        Generation.run(CONTRACT, null, version(), "a".repeat(64), settings(Map.of()), dir.resolve("whole/java"),
                dir.resolve("whole/resources"), List.of(), dir.resolve("whole/index.properties"));
        Generation.Derivation derivation = Generation.derive(CONTRACT, null, version(), "a".repeat(64),
                settings(Map.of()));
        Generation.writeCore(derivation, dir.resolve("core/java"), dir.resolve("core/resources"),
                dir.resolve("core/index.properties"));

        assertThat(files(dir.resolve("core/java"))).isNotEmpty().isEqualTo(files(dir.resolve("whole/java")));
        assertThat(files(dir.resolve("core/resources"))).isEqualTo(files(dir.resolve("whole/resources")));
        assertThat(dir.resolve("core/index.properties")).hasSameTextualContentAs(dir.resolve("whole/index.properties"));
    }

    @Test
    void anEmitterWritesEachKindOfOutputIntoItsOwnDirectory() throws IOException {
        Map<String, Map<String, String>> options = Map.of("modal", Map.of("mode", "java,resources,files"));
        Generation.Derivation derivation = Generation.derive(CONTRACT, null, version(), "a".repeat(64),
                settings(options));
        GenerationReport report = Generation.runEmitter(derivation, new ModalEmitter(), dir.resolve("java"),
                dir.resolve("resources"), dir.resolve("files"));

        assertThat(files(dir.resolve("java"))).containsOnlyKeys("com/example/contract/modal/Modal.java");
        assertThat(files(dir.resolve("resources"))).containsOnlyKeys("modal/modal.properties");
        assertThat(files(dir.resolve("files"))).containsExactlyEntriesOf(
                Map.of("mappings/modal.json", "{\"contract\":\"user-account\"}\n"));
        assertThat(report.degraded()).isEmpty();
    }

    @Test
    void anEmitterRunReplacesWhatItsDirectoriesHeldAndReportsOnlyItsOwnDegradedMethods() throws IOException {
        Files.createDirectories(dir.resolve("java/stale"));
        Files.writeString(dir.resolve("java/stale/Old.java"), "class Old {}");
        Files.createDirectories(dir.resolve("files"));
        Files.writeString(dir.resolve("files/old.json"), "{}");
        Generation.Derivation derivation = Generation.derive(CONTRACT, null, version(), "a".repeat(64),
                settings(Map.of()));
        int coreDegraded = derivation.report().degraded().size();

        GenerationReport report = Generation.runEmitter(derivation, new TestEmitter(), dir.resolve("java"),
                dir.resolve("resources"), dir.resolve("files"));

        assertThat(dir.resolve("java/stale/Old.java")).doesNotExist();
        assertThat(dir.resolve("files/old.json")).doesNotExist();
        assertThat(report.degraded()).isNotEmpty().allMatch(d -> d.emitter().equals("counting"));
        assertThat(derivation.report().degraded()).hasSize(coreDegraded);
    }

    @Test
    void theModeAnEmitterDeclaresFollowsItsOptions() {
        ModalEmitter modal = new ModalEmitter();
        assertThat(modal.produces(Map.of())).containsExactlyInAnyOrder(Output.JAVA, Output.RESOURCES);
        assertThat(modal.produces(Map.of("mode", "files"))).containsExactly(Output.FILES);
        assertThat(modal.produces(Map.of("mode", ""))).isEmpty();
    }

    @Test
    void anEmitterWrittenAgainstThePreviousInterfaceIsTakenToWriteJavaAndResources() {
        assertThat(new TestEmitter().produces(Map.of("anything", "at all")))
                .containsExactlyInAnyOrder(Output.JAVA, Output.RESOURCES);
    }

    @Test
    void aWholeRunHasNowhereToWriteFiles() throws IOException {
        assertThatThrownBy(() -> Generation.run(CONTRACT, null, version(), "a".repeat(64),
                settings(Map.of("modal", Map.of("mode", "files"))), dir.resolve("java"), dir.resolve("resources"),
                List.of(new ModalEmitter()), null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("mappings/modal.json");
    }

    @Test
    void aFileAtAPathThatLeavesItsDirectoryIsRefused() throws IOException {
        Emitter escaping = new Emitter() {
            @Override
            public String id() {
                return "escaping";
            }

            @Override
            public List<ManagedDependency> dependencies() {
                return List.of();
            }

            @Override
            public Set<Construct> represents() {
                return Set.of();
            }

            @Override
            public void emit(EmitterContext context) {
                context.writeFile("../outside.json", new byte[0]);
            }
        };
        Generation.Derivation derivation = Generation.derive(CONTRACT, null, version(), "a".repeat(64),
                settings(Map.of()));

        for (String path : List.of("../outside.json")) {
            assertThatThrownBy(() -> Generation.runEmitter(derivation, escaping, dir.resolve("java"),
                    dir.resolve("resources"), dir.resolve("files")))
                    .isInstanceOf(GenerationException.class)
                    .hasMessage("Emitter escaping wrote a file with an invalid path: " + path);
        }
    }

    @Test
    void aContextThatHasNoFilesRefusesThemAndTextIsWrittenAsUtf8() {
        byte[][] written = new byte[1][];
        EmitterContext bare = new EmitterContext() {
            @Override
            public com.arc_e_tect.gradle.apionly.transcriberj.model.ContractModel model() {
                return null;
            }

            @Override
            public Settings settings() {
                return null;
            }

            @Override
            public com.arc_e_tect.gradle.apionly.transcriberj.spi.ClassNames names() {
                return null;
            }

            @Override
            public void writeJava(String packageName, String simpleName, String source) {
            }

            @Override
            public void writeResource(String path, String content) {
            }

            @Override
            public String degraded(String className, String method,
                                   com.arc_e_tect.gradle.apionly.transcriberj.model.Finding finding) {
                return null;
            }
        };
        assertThatThrownBy(() -> bare.writeFile("a.json", "{}"))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("a.json");

        EmitterContext capturing = new EmitterContext() {
            @Override
            public com.arc_e_tect.gradle.apionly.transcriberj.model.ContractModel model() {
                return null;
            }

            @Override
            public Settings settings() {
                return null;
            }

            @Override
            public com.arc_e_tect.gradle.apionly.transcriberj.spi.ClassNames names() {
                return null;
            }

            @Override
            public void writeJava(String packageName, String simpleName, String source) {
            }

            @Override
            public void writeResource(String path, String content) {
            }

            @Override
            public void writeFile(String path, byte[] content) {
                written[0] = content;
            }

            @Override
            public String degraded(String className, String method,
                                   com.arc_e_tect.gradle.apionly.transcriberj.model.Finding finding) {
                return null;
            }
        };
        capturing.writeFile("a.txt", "é");
        assertThat(written[0]).containsExactly((byte) 0xc3, (byte) 0xa9);
    }
}
