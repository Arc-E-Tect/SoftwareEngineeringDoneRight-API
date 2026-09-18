package com.arc_e_tect.gradle.apionly.transcriberj;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Executes the DSL update action in this JVM.
 *
 * <p>The TestKit suite proves the task is registered and rewrites a real build file.
 * That build runs in another process, so it cannot show which outcome was taken.
 * Running the action directly exercises every one of them -- nothing to do, a
 * generated block, an added property, stripped comments, and a build file that
 * cannot be read or written -- in a way the assertions and the coverage report can
 * both see.</p>
 */
@DisplayName("UpdateApiOnlyTranscriberJDslTask")
class UpdateApiOnlyTranscriberJDslTaskTest {

    @TempDir Path projectDir;

    private static final String STRICT_DEFAULT = "strictDependencies = false";

    private Project project;

    @BeforeEach
    void setUp() {
        project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build();
        project.getPluginManager().apply(ApiOnlyTranscriberJPlugin.class);
    }

    // ---------------------------------------------------------------- fixtures

    /** The task as the plugin registers it, pointed at the project's own build file. */
    private UpdateApiOnlyTranscriberJDslTask task() {
        return (UpdateApiOnlyTranscriberJDslTask) project.getTasks().getByName(ApiOnlyTranscriberJPlugin.UPDATE_DSL_TASK);
    }

    private Path buildFile() {
        return projectDir.resolve("build.gradle");
    }

    private Path backup() {
        return projectDir.resolve("build.gradle.bak");
    }

    private String written(String content) throws Exception {
        Files.writeString(buildFile(), content);
        return content;
    }

    // ------------------------------------------------------------------- tests

    @Test
    @DisplayName("adds a missing property with its default, and keeps the original as a backup")
    void addsMissingProperty() throws Exception {
        String original = written("""
            apiOnlyTranscriberJ {
                subscription('orders') {
                    basePackage = 'com.example.contract'
                }
            }
            """);

        task().updateDsl();

        assertThat(Files.readString(buildFile()))
            .contains(STRICT_DEFAULT)
            .contains("// Whether a dependency version an emitter was not tested with fails the build")
            .contains("basePackage = 'com.example.contract'");
        assertThat(Files.readString(backup())).isEqualTo(original);
    }

    @Test
    @DisplayName("leaves a block that is already complete alone, and makes no backup")
    void completeBlockUnchanged() throws Exception {
        String original = written("""
            apiOnlyTranscriberJ {
                strictDependencies = true
                subscription('orders') {
                    basePackage = 'com.example.contract'
                }
            }
            """);

        task().updateDsl();

        assertThat(Files.readString(buildFile())).isEqualTo(original);
        assertThat(backup()).doesNotExist();
    }

    @Test
    @DisplayName("leaves a build without the block alone unless asked to generate one")
    void absentBlockNotGeneratedByDefault() throws Exception {
        String original = written("plugins {\n    id 'java'\n}\n");

        task().updateDsl();

        assertThat(Files.readString(buildFile())).isEqualTo(original);
        assertThat(backup()).doesNotExist();
    }

    @Test
    @DisplayName("generates the whole block when asked to, with a commented example of a subscription")
    void generatesBlock() throws Exception {
        String original = written("plugins {\n    id 'java'\n}\n");
        UpdateApiOnlyTranscriberJDslTask task = task();
        task.applyGenerateDsl(true);

        task.updateDsl();

        assertThat(Files.readString(buildFile()))
            .startsWith(original)
            .contains("apiOnlyTranscriberJ {")
            .contains(STRICT_DEFAULT)
            .contains("subscriptions {")
            .contains("// orders {")
            .contains("//     basePackage = 'com.example.orders.contract'");
        assertThat(Files.readString(backup())).isEqualTo(original);
    }


    @Test
    @DisplayName("strips comments inside the block when asked to, and only there")
    void stripsCommentsInsideTheBlock() throws Exception {
        String original = written("""
            // Kept: outside the block.
            apiOnlyTranscriberJ {
                // Removed: inside the block.
                strictDependencies = true
            }
            """);
        UpdateApiOnlyTranscriberJDslTask task = task();
        task.applyCleanupDsl(true);

        task.updateDsl();

        assertThat(Files.readString(buildFile()))
            .contains("// Kept: outside the block.")
            .doesNotContain("// Removed: inside the block.")
            .contains("strictDependencies = true");
        assertThat(Files.readString(backup())).isEqualTo(original);
    }

    @Test
    @DisplayName("refuses a build file that does not exist, naming it")
    void missingBuildFile() {
        assertThatThrownBy(() -> task().updateDsl())
            .isInstanceOf(GradleException.class)
            .hasMessageContaining("build file not found")
            .hasMessageContaining("build.gradle");
    }

    @Test
    @DisplayName("refuses a build file it cannot read")
    void unreadableBuildFile() throws Exception {
        Files.createDirectory(buildFile());

        assertThatThrownBy(() -> task().updateDsl())
            .isInstanceOf(GradleException.class)
            .hasMessageContaining("failed to read");
    }

    @Test
    @DisplayName("writes nothing when the backup cannot be made")
    void backupFailureLeavesTheFileAlone() throws Exception {
        String original = written("apiOnlyTranscriberJ {\n}\n");
        Files.createDirectories(backup());
        Files.writeString(backup().resolve("occupied"), "a directory, not a file");

        assertThatThrownBy(() -> task().updateDsl())
            .isInstanceOf(GradleException.class)
            .hasMessageContaining("failed to write");
        assertThat(Files.readString(buildFile())).isEqualTo(original);
    }
}
