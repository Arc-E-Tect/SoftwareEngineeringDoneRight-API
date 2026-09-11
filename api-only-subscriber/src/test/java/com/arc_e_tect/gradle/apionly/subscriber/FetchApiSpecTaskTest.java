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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Executes the fetch action in this JVM.
 *
 * <p>The TestKit suite proves the task behaves correctly inside a real build. It
 * cannot show which of these branches were taken, because that build runs in
 * another process. Running the action directly is what exercises the refusals --
 * an archive that disagrees with its own manifest, a released version whose bytes
 * changed -- in a way the assertions and the coverage report can both see.</p>
 */
@DisplayName("FetchApiSpecTask")
class FetchApiSpecTaskTest {

    @TempDir Path projectDir;
    @TempDir Path archives;

    private static final String OPENAPI = "openapi: 3.1.1\ninfo:\n  title: X\n  version: 1.0.0\n";

    private Project project;

    @BeforeEach
    void setUp() {
        project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build();
    }

    // ---------------------------------------------------------------- fixtures

    /** An archive shaped exactly as api-only-publisher produces one. */
    private Path archive(String target, String version, Map<String, String> documents, String manifest)
        throws Exception {
        Path stage = Files.createTempDirectory("fixture");
        for (Map.Entry<String, String> doc : documents.entrySet()) {
            Files.writeString(stage.resolve(doc.getKey()), doc.getValue());
        }
        Files.writeString(stage.resolve("manifest.json"), manifest);

        Path out = archives.resolve(target + "-" + version + ".tgz");
        java.util.List<String> command = new java.util.ArrayList<>(
            java.util.List.of("tar", "-czf", out.toString(), "manifest.json"));
        command.addAll(documents.keySet());
        Process tar = new ProcessBuilder(command).directory(stage.toFile()).inheritIO().start();
        assertThat(tar.waitFor()).isZero();
        return out;
    }

    private String manifestFor(String target, String version, Path stagedNear, Map<String, String> documents)
        throws Exception {
        StringBuilder files = new StringBuilder();
        for (Map.Entry<String, String> doc : documents.entrySet()) {
            Path tmp = Files.createTempFile("hash", ".yaml");
            Files.writeString(tmp, doc.getValue());
            if (files.length() > 0) files.append(",");
            files.append("{\"path\":\"%s\",\"sha256\":\"%s\"}"
                .formatted(doc.getKey(), Lockfile.sha256(tmp.toFile())));
        }
        return "{\"schemaVersion\":1,\"target\":\"%s\",\"version\":\"%s\",\"files\":[%s]}"
            .formatted(target, version, files);
    }

    private Map<String, String> oneDocument() {
        Map<String, String> documents = new LinkedHashMap<>();
        documents.put("openapi.yaml", OPENAPI);
        return documents;
    }

    private FetchApiSpecTask task(String target, String version, Path archive) {
        FetchApiSpecTask task = project.getTasks().create("fetch" + target, FetchApiSpecTask.class);
        task.getArchive().setFrom(archive.toFile());
        task.getTarget().set(target);
        task.getVersion().set(version);
        task.getChannel().set("file");
        task.getInto().set(projectDir.resolve("build/api-spec/" + target).toFile());
        task.getLockfile().set(projectDir.resolve("apionly.lock").toFile());
        return task;
    }

    private String lockfile() throws IOException {
        return Files.readString(projectDir.resolve("apionly.lock"));
    }

    // ------------------------------------------------------------------- tests

    @Test
    @DisplayName("unpacks the documents and records them in the lockfile")
    void unpacksAndLocks() throws Exception {
        Map<String, String> documents = oneDocument();
        Path tgz = archive("svc", "1.0.0", documents,
            manifestFor("svc", "1.0.0", null, documents));

        task("svc", "1.0.0", tgz).fetch();

        assertThat(projectDir.resolve("build/api-spec/svc/openapi.yaml")).exists();
        assertThat(lockfile())
            .contains("target svc").contains("version 1.0.0").contains("channel file");
    }

    @Test
    @DisplayName("records every document the manifest declares")
    void locksEveryDocument() throws Exception {
        Map<String, String> documents = oneDocument();
        documents.put("asyncapi.yaml", "asyncapi: 3.1.0\ninfo:\n  title: X\n  version: 1.0.0\n");
        Path tgz = archive("svc", "1.0.0", documents, manifestFor("svc", "1.0.0", null, documents));

        task("svc", "1.0.0", tgz).fetch();

        assertThat(lockfile()).contains("openapi.yaml").contains("asyncapi.yaml");
    }

    @Test
    @DisplayName("refuses an archive whose manifest names a different version")
    void versionMismatch() throws Exception {
        Map<String, String> documents = oneDocument();
        Path tgz = archive("svc", "1.0.0", documents, manifestFor("svc", "2.0.0", null, documents));

        assertThatThrownBy(() -> task("svc", "1.0.0", tgz).fetch())
            .isInstanceOf(GradleException.class)
            .hasMessageContaining("declares version 2.0.0")
            .hasMessageContaining("A published version was rebuilt, or a tag was moved");
    }

    @Test
    @DisplayName("refuses an archive missing a document its manifest declares")
    void declaredDocumentMissing() throws Exception {
        Map<String, String> declared = oneDocument();
        declared.put("asyncapi.yaml", "asyncapi: 3.1.0\n");
        // Pack only the first, but declare both.
        Path tgz = archive("svc", "1.0.0", oneDocument(), manifestFor("svc", "1.0.0", null, declared));

        assertThatThrownBy(() -> task("svc", "1.0.0", tgz).fetch())
            .isInstanceOf(GradleException.class)
            .hasMessageContaining("declares asyncapi.yaml but does not contain it");
    }

    @Test
    @DisplayName("refuses an archive whose contents disagree with its own manifest")
    void hashMismatch() throws Exception {
        Map<String, String> packed = oneDocument();
        Map<String, String> declared = new LinkedHashMap<>();
        declared.put("openapi.yaml", OPENAPI + "# different\n");
        Path tgz = archive("svc", "1.0.0", packed, manifestFor("svc", "1.0.0", null, declared));

        assertThatThrownBy(() -> task("svc", "1.0.0", tgz).fetch())
            .isInstanceOf(GradleException.class)
            .hasMessageContaining("does not match the hash its own manifest declares");
    }

    @Test
    @DisplayName("refuses a released version that comes back with different bytes")
    void relockRefused() throws Exception {
        Map<String, String> first = oneDocument();
        task("svc", "1.0.0", archive("svc", "1.0.0", first, manifestFor("svc", "1.0.0", null, first))).fetch();

        Map<String, String> changed = new LinkedHashMap<>();
        changed.put("openapi.yaml", OPENAPI + "# republished\n");
        Path tampered = archive("svc", "1.0.0", changed, manifestFor("svc", "1.0.0", null, changed));

        project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build();
        assertThatThrownBy(() -> task("svc", "1.0.0", tampered).fetch())
            .isInstanceOf(GradleException.class)
            .hasMessageContaining("is not the one recorded in apionly.lock")
            .hasMessageContaining("openapi.yaml differs");
    }

    @Test
    @DisplayName("reports a document that appeared in a re-released version")
    void relockReportsNewDocument() throws Exception {
        Map<String, String> first = oneDocument();
        task("svc", "1.0.0", archive("svc", "1.0.0", first, manifestFor("svc", "1.0.0", null, first))).fetch();

        Map<String, String> grown = oneDocument();
        grown.put("asyncapi.yaml", "asyncapi: 3.1.0\n");
        Path tgz = archive("svc", "1.0.0", grown, manifestFor("svc", "1.0.0", null, grown));

        project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build();
        assertThatThrownBy(() -> task("svc", "1.0.0", tgz).fetch())
            .hasMessageContaining("asyncapi.yaml is new in this version");
    }

    @Test
    @DisplayName("reports a document that vanished from a re-released version")
    void relockReportsRemovedDocument() throws Exception {
        Map<String, String> both = oneDocument();
        both.put("asyncapi.yaml", "asyncapi: 3.1.0\n");
        task("svc", "1.0.0", archive("svc", "1.0.0", both, manifestFor("svc", "1.0.0", null, both))).fetch();

        Map<String, String> fewer = oneDocument();
        Path tgz = archive("svc", "1.0.0", fewer, manifestFor("svc", "1.0.0", null, fewer));

        project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build();
        assertThatThrownBy(() -> task("svc", "1.0.0", tgz).fetch())
            .hasMessageContaining("asyncapi.yaml is no longer published");
    }

    @Test
    @DisplayName("a deliberate upgrade re-locks without complaint")
    void upgradeRelocks() throws Exception {
        Map<String, String> first = oneDocument();
        task("svc", "1.0.0", archive("svc", "1.0.0", first, manifestFor("svc", "1.0.0", null, first))).fetch();

        Map<String, String> next = new LinkedHashMap<>();
        next.put("openapi.yaml", OPENAPI + "# genuinely new\n");
        Path tgz = archive("svc", "1.1.0", next, manifestFor("svc", "1.1.0", null, next));

        project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build();
        task("svc", "1.1.0", tgz).fetch();

        assertThat(lockfile()).contains("version 1.1.0");
    }

    @Test
    @DisplayName("replaces whatever was in the destination rather than merging into it")
    void destinationIsReplaced() throws Exception {
        Path destination = projectDir.resolve("build/api-spec/svc");
        Files.createDirectories(destination);
        Files.writeString(destination.resolve("stale.yaml"), "left over from a previous version\n");

        Map<String, String> documents = oneDocument();
        task("svc", "1.0.0", archive("svc", "1.0.0", documents, manifestFor("svc", "1.0.0", null, documents))).fetch();

        assertThat(destination.resolve("stale.yaml")).doesNotExist();
        assertThat(destination.resolve("openapi.yaml")).exists();
    }

    @Test
    @DisplayName("refuses an archive with no manifest at all")
    void noManifest() throws Exception {
        Path stage = Files.createTempDirectory("no-manifest");
        Files.writeString(stage.resolve("openapi.yaml"), OPENAPI);
        Path out = archives.resolve("svc-1.0.0.tgz");
        Process tar = new ProcessBuilder("tar", "-czf", out.toString(), "openapi.yaml")
            .directory(stage.toFile()).inheritIO().start();
        assertThat(tar.waitFor()).isZero();

        assertThatThrownBy(() -> task("svc", "1.0.0", out).fetch())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("api-only-publisher");
    }
}
