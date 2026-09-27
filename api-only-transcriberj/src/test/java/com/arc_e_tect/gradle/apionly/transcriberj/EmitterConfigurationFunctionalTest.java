package com.arc_e_tect.gradle.apionly.transcriberj;

import com.arc_e_tect.gradle.apionly.subscriber.Lockfile;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/** T18: each emitter configured on its own, in real builds. */
@DisplayName("T18 Emitter configuration")
class EmitterConfigurationFunctionalTest {

    @TempDir
    Path projectDir;

    EmitterBuild build;

    /** Prints where each source set's sources and resources come from, and its runtime classpath. */
    static final String PLACEMENT = """
            def placement = [:]
            afterEvaluate {
                sourceSets.each { set ->
                    placement[set.name] = [java: set.java.srcDirs.collect { it.path },
                                           resources: set.resources.srcDirs.collect { it.path }]
                }
            }
            tasks.register('placement') {
                def runtime = [:]
                doFirst {
                    placement.each { name, dirs ->
                        println "JAVA ${name} ${dirs.java}"
                        println "RESOURCES ${name} ${dirs.resources}"
                    }
                }
            }
            """;

    /** The configuration the tests start from: the three purposes of the README. */
    static final String THREE_PURPOSES = """
            sourceSets = ['test', 'contractTest', 'systemTest']
            schemaClasses = findProperty('mode') ?: 'perSourceSet'
            emitter('counting') {
                sourceSets = ['contractTest']
                if (findProperty('countingOption')) {
                    options = [option: findProperty('countingOption')]
                }
            }
            emitter('modal') {
                options = [mode: 'files', flavour: findProperty('flavour') ?: 'plain']
            }
            """;

    @BeforeEach
    void setUp() {
        build = new EmitterBuild(projectDir);
    }

    private String line(String output, String prefix) {
        return output.lines().filter(l -> l.startsWith(prefix)).findFirst().orElseThrow(() ->
                new AssertionError("no line starting with " + prefix));
    }

    @Test
    @DisplayName("T18.1 Placement")
    void eachEmittersOutputGoesWhereItsBlockSays() throws Exception {
        build.write(THREE_PURPOSES, PLACEMENT);
        build.source("contractTest", "UsesCounting.java", """
                public class UsesCounting {
                    int count = com.example.contract.counting.UserV1Count.count();
                }
                """);

        BuildResult result = build.runner("classes", "testClasses", "contractTestClasses", "systemTestClasses",
                "useTest", "useContractTest", "useSystemTest", "packageUserAccountModal", "placement").build();

        assertThat(result.getOutput()).contains("TEST VERSION 1.0.0", "CONTRACTTEST VERSION 1.0.0",
                "SYSTEMTEST VERSION 1.0.0");
        String core = projectDir.resolve("build/generated/sources/transcriberj/user-account/core").toString();
        String counting = projectDir.resolve("build/generated/sources/transcriberj/user-account/counting").toString();
        String files = projectDir.resolve("build/generated/files").toString();
        for (String set : List.of("test", "contractTest", "systemTest")) {
            assertThat(line(result.getOutput(), "JAVA " + set + " ")).contains(core);
        }
        assertThat(line(result.getOutput(), "JAVA contractTest ")).contains(counting);
        assertThat(line(result.getOutput(), "JAVA test ")).doesNotContain(counting);
        assertThat(line(result.getOutput(), "JAVA systemTest ")).doesNotContain(counting);
        assertThat(line(result.getOutput(), "JAVA main ")).doesNotContain(core);
        assertThat(result.getOutput().lines().filter(l -> l.startsWith("JAVA ") || l.startsWith("RESOURCES ")))
                .noneMatch(l -> l.contains(files));
        for (String set : List.of("test", "contractTest", "systemTest")) {
            try (Stream<Path> classes = Files.walk(projectDir.resolve("build/classes/java/" + set))) {
                assertThat(classes.map(Path::toString)).noneMatch(p -> p.contains("modal"));
            }
        }
        assertThat(build.file("build/generated/files/transcriberj/user-account/modal/mappings/modal.json"))
                .content().isEqualTo("{\"contract\":\"user-account\"}\n");
        assertThat(build.file("build/classes/java/test/com/example/contract/counting")).doesNotExist();
        assertThat(build.file("build/classes/java/contractTest/com/example/contract/counting/UserV1Count.class"))
                .exists();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "emitter('wiremock') {}|configures emitter('wiremock'), but no emitter with that id is on the transcriberjEmitters classpath; the emitters there are counting, modal",
            "emitter('counting') { sourceSets = ['main'] }|emitter('counting') compiles in source set 'main', which does not see the schema classes",
            "emitter('modal') { options = [mode: 'files']; sourceSets = ['test'] }|emitter('modal') writes only files with the options {mode=files}, and files belong on no source set, but its sourceSets names test",
            "emitter('counting') { sourceSets = [] }|emitter('counting') writes Java sources with the options {}, which need a source set, but its sourceSets is empty",
            "emitterOptions = [counting: [a: 'b']]; emitter('counting') { options = [c: 'd'] }|sets the options of emitter 'counting' twice",
            "schemaClasses = 'once'|sets schemaClasses = 'once', which is not a mode"
    })
    @DisplayName("T18.2 Validation at configuration")
    void aBrokenRuleFailsWhileTheBuildIsConfigured(String row) throws Exception {
        String[] parts = row.split("\\|");
        build.write("sourceSets = ['test', 'contractTest']\n" + parts[0]);

        BuildResult result = build.runner("help").buildAndFail();

        assertThat(result.getOutput()).contains(parts[1]);
        assertThat(result.getOutput()).doesNotContain("Task :generateContractSources");
    }

    @Test
    @DisplayName("T18.3 Defaults and compatibility")
    void todaysDslGeneratesTheSameSourcesInTheSameSourceSetsAndWarnsOnce() throws Exception {
        build.write("""
                emitterOptions = [counting: [option: 'on']]
                """, PLACEMENT);

        BuildResult result = build.runner("testClasses", "placement").build();

        // The oracle: one run of the core and every emitter, as the TranscriberJ generated before.
        Path oracle = Files.createDirectories(projectDir.resolve("oracle"));
        String sha = Lockfile.read(build.file("apionly.lock").toFile()).get("user-account").files().get("openapi.yaml");
        com.arc_e_tect.gradle.apionly.transcriberj.core.Generation.run(build.file("build/api-spec/user-account/openapi.yaml"),
                "1.0.0", sha, new com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings("user-account",
                        "com.example.contract", false, TranscriberJSubscription.DEFAULT_DESCRIPTION_PLACEHOLDER, 3, null,
                        "400", true, List.of(), Map.of("counting", Map.of("option", "on"))),
                oracle.resolve("java"), oracle.resolve("resources"),
                List.of(new com.arc_e_tect.gradle.apionly.transcriberj.core.TestEmitter()), null);
        Map<String, String> generated = new TreeMap<>(files(build.file("build/generated/sources/transcriberj/user-account/core")));
        generated.putAll(files(build.file("build/generated/sources/transcriberj/user-account/counting")));
        assertThat(generated).isEqualTo(files(oracle.resolve("java")));
        assertThat(files(build.file("build/generated/resources/transcriberj/user-account/counting")))
                .isEqualTo(files(oracle.resolve("resources")));

        String test = line(result.getOutput(), "JAVA test ");
        assertThat(test).contains("user-account/core").contains("user-account/counting");
        assertThat(result.getOutput().split("sets emitterOptions, which is deprecated", -1)).hasSize(2);
        assertThat(result.getOutput()).contains("emitter 'counting' is on transcriberjEmitters but not configured");
    }

    @Test
    @DisplayName("T18.4 Up to date per emitter")
    void changingOneEmittersOptionsRerunsThatEmitterAlone() throws Exception {
        build.write(THREE_PURPOSES);
        String[] all = {"contractTestClasses", "packageUserAccountModal"};
        build.runner(all).build();

        BuildResult modal = build.runner(with(all, "-Pflavour=spicy")).build();
        assertThat(modal.task(":generateContractSourcesUserAccountModal").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(modal.task(":packageUserAccountModal").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(modal.task(":generateContractSourcesUserAccount").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);
        assertThat(modal.task(":generateContractSourcesUserAccountCounting").getOutcome())
                .isEqualTo(TaskOutcome.UP_TO_DATE);

        BuildResult counting = build.runner(with(all, "-Pflavour=spicy", "-PcountingOption=on")).build();
        assertThat(counting.task(":generateContractSourcesUserAccountCounting").getOutcome())
                .isEqualTo(TaskOutcome.SUCCESS);
        assertThat(counting.task(":generateContractSourcesUserAccountModal").getOutcome())
                .isEqualTo(TaskOutcome.UP_TO_DATE);
        assertThat(counting.task(":packageUserAccountModal").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);
        assertThat(counting.task(":generateContractSourcesUserAccount").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);

        build.publish("1.0.1");
        BuildResult contract = build.runner(with(all, "-Pflavour=spicy", "-PcountingOption=on",
                "-PcontractVersion=1.0.1")).build();
        for (String task : List.of(":generateContractSourcesUserAccount", ":generateContractSourcesUserAccountCounting",
                ":generateContractSourcesUserAccountModal", ":packageUserAccountModal")) {
            assertThat(contract.task(task).getOutcome()).as(task).isEqualTo(TaskOutcome.SUCCESS);
        }
    }

    @Test
    @DisplayName("T18.5 Build cache and configuration cache")
    void everyTaskRestoresFromTheBuildCacheInAnotherDirectoryAndTheConfigurationCacheIsReused() throws Exception {
        Path cache = Files.createDirectories(projectDir.resolve("cache"));
        Path first = Files.createDirectories(projectDir.resolve("first"));
        Path second = Files.createDirectories(projectDir.resolve("second"));
        for (Path dir : List.of(first, second)) {
            new EmitterBuild(dir).write(THREE_PURPOSES);
            Files.writeString(dir.resolve("settings.gradle"), """
                    rootProject.name = 'consumer'
                    buildCache { local { directory = file('%s') } }
                    """.formatted(cache.toString().replace('\\', '/')));
        }
        String[] tasks = {"contractTestClasses", "packageUserAccountModal", "--build-cache", "--configuration-cache"};
        BuildResult stored = new EmitterBuild(first).runner(tasks).build();
        assertThat(stored.task(":generateContractSourcesUserAccount").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        BuildResult reused = new EmitterBuild(first).runner(tasks).build();
        assertThat(reused.getOutput()).contains("Configuration cache entry reused");

        BuildResult restored = new EmitterBuild(second).runner(tasks).build();
        for (String task : List.of(":generateContractSourcesUserAccount", ":generateContractSourcesUserAccountCounting",
                ":generateContractSourcesUserAccountModal", ":reportContractSourcesUserAccount",
                ":packageUserAccountModal")) {
            assertThat(restored.task(task).getOutcome()).as(task).isEqualTo(TaskOutcome.FROM_CACHE);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"shared", "perSourceSet"})
    @DisplayName("T18.6, T18.7 Both modes")
    void theSchemaClassesReachEveryListedSourceSet(String mode) throws Exception {
        build.write(THREE_PURPOSES).source("contractTest", "UsesCounting.java", """
                public class UsesCounting {
                    int count = com.example.contract.counting.UserV1Count.count();
                }
                """);

        BuildResult result = build.runner("useTest", "useContractTest", "useSystemTest", "contractTestClasses",
                "-Pmode=" + mode).build();

        assertThat(result.getOutput()).contains("TEST VERSION 1.0.0", "CONTRACTTEST VERSION 1.0.0",
                "SYSTEMTEST VERSION 1.0.0");
        List<String> compiledIn = new java.util.ArrayList<>();
        try (Stream<Path> classes = Files.walk(projectDir.resolve("build/classes/java"))) {
            classes.filter(p -> p.getFileName().toString().equals("UserV1.class"))
                    .forEach(p -> compiledIn.add(projectDir.resolve("build/classes/java").relativize(p).getName(0)
                            .toString()));
        }
        if (mode.equals("shared")) {
            assertThat(compiledIn).containsExactly("transcriberjUserAccount");
        } else {
            assertThat(compiledIn).containsExactlyInAnyOrder("test", "contractTest", "systemTest");
        }
        assertThat(build.file("build/classes/java/contractTest/com/example/contract/counting/UserV1Count.class"))
                .exists();
    }

    @Test
    @DisplayName("T18.8 Both modes, one outcome")
    void bothModesGenerateTheSameSourcesAndPassTheSameTests() throws Exception {
        build.write(THREE_PURPOSES);
        build.runner("useTest", "useContractTest", "-Pmode=perSourceSet").build();
        Map<String, String> perSourceSet = files(build.file("build/generated/sources/transcriberj/user-account"));

        BuildResult shared = build.runner("useTest", "useContractTest", "-Pmode=shared").build();

        assertThat(shared.getOutput()).contains("TEST VERSION 1.0.0", "CONTRACTTEST VERSION 1.0.0");
        assertThat(files(build.file("build/generated/sources/transcriberj/user-account"))).isEqualTo(perSourceSet);
    }

    @Test
    @DisplayName("T18.9 Package-private access across source sets")
    void aHandWrittenClassInTheGeneratedPackageUsesAPackagePrivateMember() throws Exception {
        build.write(THREE_PURPOSES).source("contractTest", "com/example/contract/Handwritten.java", """
                package com.example.contract;

                public class Handwritten {
                    public static void main(String[] args) {
                        StringBuilder json = new StringBuilder("{");
                        ContractJson.member(json, "name", ContractJson.string("x"));
                        ContractJson.close(json);
                        System.out.println("HANDWRITTEN " + json);
                    }
                }
                """);

        BuildResult result = build.runner("contractTestClasses", "runHandwritten", "-Pmode=shared").build();

        assertThat(result.getOutput()).contains("HANDWRITTEN {\"name\":\"x\"}");
        assertThat(build.file("build/classes/java/contractTest/com/example/contract/ContractJson.class")).doesNotExist();
    }

    @Test
    @DisplayName("T18.10 Archives")
    void theArchiveIsReproducibleHoldsTheProvenanceAndIsPublished() throws Exception {
        build.write(THREE_PURPOSES, """
                apply plugin: 'maven-publish'
                group = 'com.example'
                version = '1.0'
                publishing {
                    publications {
                        stubs(MavenPublication) {
                            from components.transcriberjUserAccountModal
                            artifactId = 'user-account-modal'
                        }
                    }
                    repositories {
                        maven { url = uri(layout.projectDirectory.dir('repo')) }
                    }
                }
                """);

        build.runner("packageUserAccountModal", "publish").build();

        Path zip = build.file("build/distributions/user-account-modal-1.0.0.zip");
        byte[] once = Files.readAllBytes(zip);
        Map<String, String> entries = new TreeMap<>();
        try (ZipFile file = new ZipFile(zip.toFile())) {
            for (ZipEntry entry : java.util.Collections.list(file.entries())) {
                if (!entry.isDirectory()) entries.put(entry.getName(), new String(file.getInputStream(entry).readAllBytes()));
            }
        }
        String sha = Lockfile.read(build.file("apionly.lock").toFile()).get("user-account").files().get("openapi.yaml");
        assertThat(entries).containsOnlyKeys("mappings/modal.json", "apionly-provenance.json");
        assertThat(entries.get("apionly-provenance.json")).isEqualTo("{\"contract\":\"user-account\",\"contractSha256\":\""
                + sha + "\",\"contractVersion\":\"1.0.0\",\"emitter\":\"modal\",\"emitterVersion\":\"unspecified\","
                + "\"options\":{\"flavour\":\"plain\",\"mode\":\"files\"},\"transcriberj\":\""
                + ApiOnlyTranscriberJPlugin.transcriberJVersion() + "\"}\n");

        // Another umask on the generated files, and another time zone, give the same bytes.
        try (Stream<Path> generated = Files.walk(build.file("build/generated/files"))) {
            for (Path p : generated.filter(Files::isRegularFile).toList()) {
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxrwxrwx"));
            }
        }
        Files.writeString(build.file("gradle.properties"), "org.gradle.jvmargs=-Duser.timezone=Pacific/Kiritimati\n");
        build.runner("packageUserAccountModal", "--rerun").build();
        assertThat(Files.readAllBytes(zip)).isEqualTo(once);

        assertThat(build.file("repo/com/example/user-account-modal/1.0/user-account-modal-1.0.zip")).exists();
        Path consumer = Files.createDirectories(projectDir.resolve("consumer"));
        Files.writeString(consumer.resolve("settings.gradle"), "rootProject.name = 'client'\n");
        Files.writeString(consumer.resolve("build.gradle"), """
                repositories { maven { url = uri('%s') } }
                configurations { stubs }
                dependencies { stubs 'com.example:user-account-modal:1.0@zip' }
                tasks.register('stubs', Copy) {
                    from(configurations.stubs.elements.map { it.collect { zipTree(it) } })
                    into layout.buildDirectory.dir('stubs')
                }
                """.formatted(build.file("repo").toUri()));
        org.gradle.testkit.runner.GradleRunner.create().withProjectDir(consumer.toFile()).withArguments("stubs")
                .forwardOutput().build();
        assertThat(consumer.resolve("build/stubs/mappings/modal.json")).exists();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"shared", "perSourceSet"})
    @DisplayName("T18.11 IDE")
    void theIdeModelsMarkEveryGeneratedDirectoryAndNoFilesDirectory(String mode) throws Exception {
        build.write(THREE_PURPOSES, """
                apply plugin: 'idea'
                apply plugin: 'eclipse'
                def ide = new StringBuilder()
                gradle.projectsEvaluated {
                    ide.append("GENERATED ").append(idea.module.generatedSourceDirs.collect { it.path }.sort())
                    ide.append("\\nSYNCHRONISED ").append(eclipse.synchronizationTasks.getDependencies(null)
                            .collect { it.name }.sort())
                }
                tasks.register('ideModel') { doLast { println ide } }
                """);

        BuildResult result = build.runner("ideModel", "-Pmode=" + mode).build();

        String generated = line(result.getOutput(), "GENERATED ");
        for (String dir : List.of("sources/transcriberj/user-account/core", "resources/transcriberj/user-account/core",
                "sources/transcriberj/user-account/counting", "resources/transcriberj/user-account/counting")) {
            assertThat(generated).contains(build.file("build/generated/" + dir).toString());
        }
        assertThat(generated).doesNotContain("generated/files").doesNotContain("user-account/modal");
        assertThat(line(result.getOutput(), "SYNCHRONISED ")).contains("generateContractSourcesUserAccount",
                "generateContractSourcesUserAccountCounting", "generateContractSourcesUserAccountModal");
    }

    @Test
    @DisplayName("T18.13 Verification")
    void verificationNamesAnEmitterWhoseOutputIsFromAnotherVersion() throws Exception {
        build.write(THREE_PURPOSES);
        build.runner("contractTestClasses", "packageUserAccountModal", "verifyContractSourcesUserAccount").build();
        build.publish("1.0.1");

        BuildResult result = build.runner("generateContractSourcesUserAccount", "verifyContractSourcesUserAccount",
                "-PcontractVersion=1.0.1", "-x", "generateContractSourcesUserAccountCounting",
                "-x", "generateContractSourcesUserAccountModal").buildAndFail();

        assertThat(result.getOutput()).contains("The output of emitter counting for contract user-account was "
                + "generated from version 1.0.0").contains("but the lockfile names version 1.0.1")
                .contains("Regenerate it with generateContractSourcesUserAccountCounting");
    }

    @ParameterizedTest(name = "mode {0}")
    @ValueSource(strings = {"java", "resources", "files", "java,files"})
    @DisplayName("T18.14 What an emitter declares decides where its output goes")
    void anEmittersDeclaredOutputIsPlacedAsDeclared(String mode) throws Exception {
        build.write("""
                sourceSets = ['test']
                emitter('counting') {}
                emitter('modal') { options = [mode: '%s'] }
                """.formatted(mode), PLACEMENT);

        BuildResult result = build.runner("testClasses", "generateContractSourcesUserAccountModal", "placement").build();

        String java = line(result.getOutput(), "JAVA test ");
        String resources = line(result.getOutput(), "RESOURCES test ");
        assertThat(java.contains("user-account/modal")).isEqualTo(mode.contains("java"));
        assertThat(resources.contains("user-account/modal")).isEqualTo(mode.contains("resources"));
        assertThat(build.file("build/generated/files/transcriberj/user-account/modal/mappings/modal.json").toFile()
                .exists()).isEqualTo(mode.contains("files"));
        // Written against the previous interface: Java and resources.
        assertThat(java).contains("user-account/counting");
        assertThat(resources).contains("user-account/counting");
        BuildResult tasks = build.runner("tasks", "--all").build();
        assertThat(tasks.getOutput().contains("packageUserAccountModal")).isEqualTo(mode.contains("files"));
    }

    @Test
    @DisplayName("T18 The report is one per subscription, as text and as AsciiDoc")
    void theReportMergesEveryTasksPart() throws Exception {
        build.write(THREE_PURPOSES);

        BuildResult result = build.runner("contractTestClasses", "generateContractSourcesUserAccountModal").build();

        assertThat(result.getOutput()).contains("API-Only TranscriberJ: user-account 1.0.0: ");
        String text = Files.readString(build.file("build/reports/transcriberj/user-account.txt"));
        assertThat(text).contains("[counting]");
        assertThat(build.file("build/reports/transcriberj/user-account.adoc")).content()
                .contains("include::user-account/core.adoc[leveloffset=+1]")
                .contains("include::user-account/counting.adoc[leveloffset=+1]")
                .contains("include::user-account/modal.adoc[leveloffset=+1]");
        assertThat(build.file("build/reports/transcriberj/user-account/counting.adoc")).content()
                .startsWith("= Emitter counting\n");
    }

    private static String[] with(String[] tasks, String... more) {
        return Stream.concat(Stream.of(tasks), Stream.of(more)).toArray(String[]::new);
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
}
