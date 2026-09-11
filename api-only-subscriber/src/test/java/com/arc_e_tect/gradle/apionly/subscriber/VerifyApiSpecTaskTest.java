package com.arc_e_tect.gradle.apionly.subscriber;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Executes the verification action in this JVM.
 *
 * <p>Every case here is a way a build can come to believe it honours a contract
 * it does not have. They are worth testing one at a time, and worth their
 * messages being checked: a verification failure that does not say what to do
 * about it tends to be resolved by deleting the lockfile.</p>
 */
@DisplayName("VerifyApiSpecTask")
class VerifyApiSpecTaskTest {

    @TempDir Path projectDir;

    private static final String OPENAPI = "openapi: 3.1.1\ninfo:\n  title: X\n  version: 1.0.0\n";

    private Project project;
    private Path destination;

    @BeforeEach
    void setUp() throws IOException {
        project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build();
        destination = projectDir.resolve("build/api-spec/svc");
        Files.createDirectories(destination);
    }

    private VerifyApiSpecTask task() {
        VerifyApiSpecTask task = project.getTasks().create("verify", VerifyApiSpecTask.class);
        task.getTarget().set("svc");
        task.getInto().set(destination.toFile());
        task.getLockfile().set(projectDir.resolve("apionly.lock").toFile());
        return task;
    }

    /** Writes a document and a lockfile that agree about it. */
    private void fetched(String content) throws IOException {
        Files.writeString(destination.resolve("openapi.yaml"), content);
        Lockfile lock = new Lockfile();
        lock.put(new Lockfile.Entry("svc", "1.0.0", "file",
            java.util.Map.of("openapi.yaml", Lockfile.sha256(destination.resolve("openapi.yaml").toFile()))));
        lock.write(projectDir.resolve("apionly.lock").toFile());
    }

    private void withManifest(String version) throws IOException {
        Files.writeString(destination.resolve("manifest.json"),
            "{\"schemaVersion\":1,\"target\":\"svc\",\"version\":\"%s\",\"files\":[{\"path\":\"openapi.yaml\",\"sha256\":\"%s\"}]}"
                .formatted(version, "a".repeat(64)));
    }

    @Test
    @DisplayName("passes when what is on disk matches the lockfile")
    void passesWhenUnchanged() throws Exception {
        fetched(OPENAPI);

        assertThatCode(() -> task().verify()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("fails when a document was edited after it was fetched")
    void failsOnDrift() throws Exception {
        fetched(OPENAPI);
        Files.writeString(destination.resolve("openapi.yaml"), OPENAPI + "# edited\n");

        assertThatThrownBy(() -> task().verify())
            .isInstanceOf(GradleException.class)
            .hasMessageContaining("has drifted from apionly.lock")
            .hasMessageContaining("openapi.yaml has changed since it was locked")
            .hasMessageContaining("locked:")
            .hasMessageContaining("on disk:");
    }

    @Test
    @DisplayName("fails when a locked document is gone")
    void failsOnMissingDocument() throws Exception {
        fetched(OPENAPI);
        Files.delete(destination.resolve("openapi.yaml"));

        assertThatThrownBy(() -> task().verify())
            .isInstanceOf(GradleException.class)
            .hasMessageContaining("openapi.yaml is missing");
    }

    @Test
    @DisplayName("says what to run when nothing has been fetched yet")
    void failsBeforeAnyFetch() throws Exception {
        deleteRecursively(destination);

        assertThatThrownBy(() -> task().verify())
            .isInstanceOf(GradleException.class)
            .hasMessageContaining("nothing has been fetched for 'svc' yet")
            .hasMessageContaining("Run fetchApiSpec first");
    }

    @Test
    @DisplayName("says what to run when there is no lockfile")
    void failsWithoutLockfile() throws Exception {
        Files.writeString(destination.resolve("openapi.yaml"), OPENAPI);

        assertThatThrownBy(() -> task().verify())
            .isInstanceOf(GradleException.class)
            .hasMessageContaining("there is no apionly.lock in this project")
            .hasMessageContaining("commit it");
    }

    @Test
    @DisplayName("says what to run when the lockfile knows nothing about this target")
    void failsWithoutEntry() throws Exception {
        Files.writeString(destination.resolve("openapi.yaml"), OPENAPI);
        Lockfile lock = new Lockfile();
        lock.put(new Lockfile.Entry("other", "1.0.0", "file", java.util.Map.of("openapi.yaml", "a".repeat(64))));
        lock.write(projectDir.resolve("apionly.lock").toFile());

        assertThatThrownBy(() -> task().verify())
            .isInstanceOf(GradleException.class)
            .hasMessageContaining("has no entry for 'svc'")
            .hasMessageContaining("commit the result");
    }

    @Test
    @DisplayName("fails when the fetched archive declares a version the lockfile does not")
    void failsOnManifestVersionMismatch() throws Exception {
        fetched(OPENAPI);
        withManifest("2.0.0");

        assertThatThrownBy(() -> task().verify())
            .isInstanceOf(GradleException.class)
            .hasMessageContaining("declares version 2.0.0")
            .hasMessageContaining("apionly.lock records 1.0.0");
    }

    @Test
    @DisplayName("is content with a manifest that agrees with the lockfile")
    void manifestAgreeing() throws Exception {
        fetched(OPENAPI);
        withManifest("1.0.0");

        assertThatCode(() -> task().verify()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("reports every problem at once rather than the first")
    void reportsAllProblems() throws Exception {
        Files.writeString(destination.resolve("openapi.yaml"), OPENAPI);
        Files.writeString(destination.resolve("asyncapi.yaml"), "asyncapi: 3.1.0\n");
        Lockfile lock = new Lockfile();
        lock.put(new Lockfile.Entry("svc", "1.0.0", "file", new java.util.LinkedHashMap<>(java.util.Map.of(
            "openapi.yaml", "b".repeat(64),
            "asyncapi.yaml", "c".repeat(64)))));
        lock.write(projectDir.resolve("apionly.lock").toFile());

        assertThatThrownBy(() -> task().verify())
            .hasMessageContaining("openapi.yaml has changed")
            .hasMessageContaining("asyncapi.yaml has changed");
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) { }
            });
        }
    }
}
