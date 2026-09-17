package com.arc_e_tect.gradle.apionly.transcriberj.fixtures;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The strict half of the safety net: the hand-written classes still produce, byte for
 * byte, the {@code body(...)} output that was recorded from them.
 */
class HandwrittenBodyFixturesTest {

    private static final Path HANDWRITTEN_SOURCES =
            Path.of("src/handwritten/java/com/arc_e_tect/book/sedr/schema");

    private final List<BodyFixtures.Case> cases = BodyFixtures.cases(BodyFixtures.read());

    @TestFactory
    Stream<DynamicTest> everyRecordedBodyIsReproducedByteForByte() {
        return cases.stream().map(c -> DynamicTest.dynamicTest(c.id(), () -> {
            assertThat(c.expected()).as("%s has no recorded expectation", c.id()).isNotNull();
            assertThat(c.replay(BodyFixtures.HANDWRITTEN_PACKAGE)).isEqualTo(c.expected());
        }));
    }

    @Test
    void everyComparisonIsOneTheDecisionsAllow() {
        assertThat(cases).extracting(BodyFixtures.Case::comparison)
                .allMatch(comparison -> comparison.equals("strict-bytes") || comparison.equals("json-equal"));
        // Only UserV1 was granted JSON equality: its hand-written body is pretty-printed.
        assertThat(cases).filteredOn(c -> c.comparison().equals("json-equal"))
                .extracting(BodyFixtures.Case::className)
                .containsOnly("UserV1");
    }

    @Test
    void everyBodyMethodOfEveryHandwrittenClassHasAtLeastOneCase() throws IOException, ClassNotFoundException {
        Set<String> declared = new TreeSet<>();
        try (Stream<Path> files = Files.list(HANDWRITTEN_SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String simpleName = file.getFileName().toString().replaceFirst("\\.java$", "");
                Class<?> type = Class.forName(BodyFixtures.HANDWRITTEN_PACKAGE + "." + simpleName);
                for (Method m : type.getDeclaredMethods()) {
                    if (m.getName().equals("body") && Modifier.isStatic(m.getModifiers())) {
                        declared.add(simpleName + "/" + m.getParameterCount());
                    }
                }
            }
        }
        Set<String> covered = cases.stream()
                .map(c -> c.className() + "/" + c.args().size())
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(declared).isNotEmpty();
        assertThat(covered).containsExactlyElementsOf(declared);
    }
}
