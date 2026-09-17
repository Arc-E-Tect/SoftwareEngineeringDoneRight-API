package com.arc_e_tect.gradle.apionly.transcriberj;

import com.arc_e_tect.gradle.apionly.subscriber.Lockfile;
import com.arc_e_tect.gradle.apionly.transcriberj.core.TestEmitter;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Emitter;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The plugin, applied to a real build, with a real Subscriber fetching a real contract. */
class ApiOnlyTranscriberJPluginFunctionalTest {

    static final Path CONTRACT = Path.of(System.getProperty("transcriberj.fixtures"),
            "contracts/user-account/openapi.yaml");

    @TempDir
    Path projectDir;

    @BeforeEach
    void seedProject() throws Exception {
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'consumer'\n");
        publish("1.0.0", Files.readString(CONTRACT));
        emitterJar(projectDir.resolve("emitter.jar"));
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'com.arc-e-tect.api-only-transcriberj'
                }

                dependencies {
                    transcriberjEmitters files('emitter.jar')
                }

                apiOnlySubscriber {
                    channel {
                        type = 'file'
                        directory = file('channel')
                    }
                    subscribe('user-account') {
                        version = findProperty('contractVersion') ?: '1.0.0'
                    }
                }

                apiOnlyTranscriberJ {
                    subscription('user-account') {
                        basePackage = 'com.example.contract'
                    }
                }

                // The consumer has test sources but no tests; check still runs test.
                tasks.named('test') {
                    failOnNoDiscoveredTests = false
                }

                tasks.register('useContract', JavaExec) {
                    classpath = sourceSets.test.runtimeClasspath
                    mainClass = 'UseContract'
                }
                """);
        Path source = Files.createDirectories(projectDir.resolve("src/test/java"));
        Files.writeString(source.resolve("UseContract.java"), """
                public class UseContract {
                    public static void main(String[] args) {
                        System.out.print("BODY " + com.example.contract.UserV1.body("alice", "a@example.com"));
                        System.out.println("COUNT " + com.example.contract.counting.UserV1Count.count());
                        System.out.println("VERSION " + com.example.contract.ContractManifest.CONTRACT_VERSION);
                    }
                }
                """);
    }

    /** Publishes a contract to the file channel, shaped as the Publisher ships one. */
    private void publish(String version, String document) throws Exception {
        Path stage = Files.createTempDirectory(projectDir, "stage");
        Files.writeString(stage.resolve("openapi.yaml"), document);
        Files.writeString(stage.resolve("manifest.json"),
                "{\"schemaVersion\":1,\"target\":\"user-account\",\"version\":\"%s\",\"files\":[{\"path\":\"openapi.yaml\",\"sha256\":\"%s\"}]}"
                        .formatted(version, Lockfile.sha256(stage.resolve("openapi.yaml").toFile())));
        Path archives = Files.createDirectories(projectDir.resolve("channel/user-account/" + version));
        Process tar = new ProcessBuilder("tar", "-czf",
                archives.resolve("user-account-" + version + ".tgz").toString(), "manifest.json", "openapi.yaml")
                .directory(stage.toFile()).inheritIO().start();
        assertThat(tar.waitFor()).isZero();
    }

    /** A jar holding the test emitter and nothing else, registered as a service. */
    static void emitterJar(Path jar) throws IOException, URISyntaxException {
        Path classes = Path.of(TestEmitter.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String entry = TestEmitter.class.getName().replace('.', '/') + ".class";
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out)) {
            jarOut.putNextEntry(new JarEntry(entry));
            jarOut.write(Files.readAllBytes(classes.resolve(entry)));
            jarOut.closeEntry();
            jarOut.putNextEntry(new JarEntry("META-INF/services/" + Emitter.class.getName()));
            jarOut.write((TestEmitter.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
        }
    }

    private GradleRunner runner(String... arguments) {
        List<String> args = new ArrayList<>(List.of(arguments));
        args.add("--configuration-cache");
        args.add("--stacktrace");
        return GradleRunner.create().withProjectDir(projectDir.toFile()).withPluginClasspath()
                .withArguments(args).forwardOutput();
    }

    @Test
    void generatesCompilesAndRunsTheClassTreeAndKeepsItCurrent() throws Exception {
        BuildResult first = runner("useContract", "check").build();
        assertThat(first.getOutput())
                .contains("BODY {\"username\":\"alice\",\"emailAddress\":\"a@example.com\"}")
                .contains("COUNT 2")
                .contains("VERSION 1.0.0")
                .contains("API-Only TranscriberJ: user-account 1.0.0: 17 degraded method(s), 2 recommendation(s), "
                        + "2 undecided construct(s)");
        assertThat(first.task(":generateContractSourcesUserAccount").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(first.task(":verifyContractSourcesUserAccount").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(projectDir.resolve("build/reports/transcriberj/user-account.txt")).exists();
        assertThat(projectDir.resolve("build/generated/sources/transcriberj/user-account/com/example/contract/UserV1.java"))
                .exists();

        BuildResult again = runner("useContract", "check").build();
        assertThat(again.getOutput()).contains("Configuration cache entry reused");
        assertThat(again.task(":generateContractSourcesUserAccount").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);

        // A new version of the contract regenerates the tree, whatever its file looks like.
        publish("1.0.1", Files.readString(CONTRACT).replace("  version: 1.0.0", "  version: 1.0.1"));
        BuildResult upgraded = runner("useContract", "-PcontractVersion=1.0.1").build();
        assertThat(upgraded.task(":generateContractSourcesUserAccount").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(upgraded.getOutput()).contains("VERSION 1.0.1");
    }

    @Test
    void verificationFailsWhenTheTreeWasGeneratedFromAnotherContract() throws Exception {
        runner("generateContractSourcesUserAccount").build();
        Path manifest = projectDir.resolve(
                "build/generated/sources/transcriberj/user-account/com/example/contract/ContractManifest.java");
        Files.writeString(manifest, Files.readString(manifest)
                .replace("CONTRACT_VERSION = \"1.0.0\"", "CONTRACT_VERSION = \"0.9.0\""));

        BuildResult result = runner("verifyContractSourcesUserAccount").buildAndFail();
        assertThat(result.getOutput()).contains("were generated from version 0.9.0")
                .contains("but the lockfile names version 1.0.0")
                .contains("Regenerate them with generateContractSourcesUserAccount");
    }

    @Test
    void aSubscriptionWithoutABasePackageFailsByName() throws Exception {
        Files.writeString(projectDir.resolve("build.gradle"), Files.readString(projectDir.resolve("build.gradle"))
                .replace("basePackage = 'com.example.contract'", ""));
        BuildResult result = runner("generateContractSourcesUserAccount").buildAndFail();
        assertThat(result.getOutput()).contains("subscription('user-account') needs a basePackage");
    }

    @Test
    void aContractNoClassesCanBeGeneratedFromFailsWithTheReason() throws Exception {
        publish("2.0.0", Files.readString(CONTRACT).replace("  version: 1.0.0", "  version: 2.0.0")
                .replaceAll("\\n\\s+x-fragment-path: [^\\n]+", ""));
        BuildResult result = runner("generateContractSourcesUserAccount", "-PcontractVersion=2.0.0").buildAndFail();
        assertThat(result.getOutput()).contains("These components have no x-fragment-path");
    }
}
