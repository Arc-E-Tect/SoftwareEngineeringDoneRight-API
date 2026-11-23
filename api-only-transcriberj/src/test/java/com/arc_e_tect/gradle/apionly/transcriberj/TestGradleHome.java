package com.arc_e_tect.gradle.apionly.transcriberj;

import org.gradle.testfixtures.ProjectBuilder;
import org.gradle.testkit.runner.GradleRunner;

import java.io.File;

/**
 * The one Gradle user home every build these tests start uses: each TestKit build and each
 * {@link ProjectBuilder} project.
 *
 * <p>A nested build resolves its own dependencies, such as an emitter's, from Maven Central. With a
 * new Gradle user home per build, which is what TestKit and {@code ProjectBuilder} use by default,
 * every run downloads them again, and a burst of downloads from one runner gets rate-limited. One
 * stable directory keeps them between builds and between runs, and CI caches it.
 *
 * <p>The test task passes the directory as {@value #PROPERTY}; without it, as when a test is run
 * outside Gradle, it is {@code ~/.gradle/test-homes/api-only-transcriberj}.
 */
final class TestGradleHome {

    /** The system property the test task passes the directory in. */
    static final String PROPERTY = "transcriberj.testGradleHome";

    private TestGradleHome() {
    }

    /**
     * The directory.
     *
     * @return the Gradle user home of every nested build
     */
    static File directory() {
        String configured = System.getProperty(PROPERTY);
        return configured != null ? new File(configured)
                : new File(System.getProperty("user.home"), ".gradle/test-homes/api-only-transcriberj");
    }

    /**
     * A TestKit runner on the shared Gradle user home.
     *
     * @return the runner
     */
    static GradleRunner runner() {
        return GradleRunner.create().withTestKitDir(directory());
    }

    /**
     * A project builder on the shared Gradle user home.
     *
     * @return the builder
     */
    static ProjectBuilder projectBuilder() {
        return ProjectBuilder.builder().withGradleUserHomeDir(directory());
    }
}
