package com.arc_e_tect.gradle.apionly.transcriberj.fixtures;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The committed copies under {@code src/handwritten} are exactly the reference
 * implementation's {@code testSchemaCommon} at the recorded commit.
 *
 * <p>Needs a checkout of that repository, named by the
 * {@code transcriberj.referenceRepository} system property; skipped without one.
 */
class HandwrittenSourceFixturesTest {

    private static final Path COPIES = Path.of("src/handwritten");

    @Test
    void copiesMatchTheReferenceImplementationAtTheRecordedCommit() throws Exception {
        String repository = System.getProperty("transcriberj.referenceRepository", "");
        assumeFalse(repository.isBlank(), "no -PreferenceRepository given; source comparison skipped");

        Properties source = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("src/test/resources/fixtures/handwritten/SOURCE.properties"))) {
            source.load(in);
        }
        String commit = source.getProperty("commit");
        String root = source.getProperty("path");

        Set<String> upstream = new TreeSet<>(git(repository, "ls-tree", "-r", "--name-only", commit, root + "/")
                .lines().map(line -> line.substring(root.length() + 1)).toList());
        Set<String> copies = new TreeSet<>();
        try (Stream<Path> files = Files.walk(COPIES)) {
            files.filter(Files::isRegularFile).forEach(f -> copies.add(COPIES.relativize(f).toString()));
        }
        assertThat(copies).containsExactlyElementsOf(upstream);

        for (String file : upstream) {
            assertThat(Files.readString(COPIES.resolve(file)))
                    .as(file)
                    .isEqualTo(git(repository, "show", commit + ":" + root + "/" + file));
        }
    }

    private static String git(String repository, String... arguments) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", repository));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as("%s%n%s", command, output).isZero();
        return output;
    }
}
