package com.arc_e_tect.gradle.apionly.transcriberj.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T12.11: every existing generated member is byte for byte what it was before valid values
 * were generated. Each source generated from the reference contracts -- with and without
 * generateDocs and a description bundle -- and from the corpus, with the new members
 * stripped, hashes to what main generated, recorded in
 * {@code fixtures/valid-values/baseline-sources.sha256}.
 */
@DisplayName("T12.11 No regression")
class BaselineSourcesTest {

    @TempDir
    Path directory;

    @Test
    void everyExistingMemberIsByteForByteWhatItWas() {
        Map<String, GeneratedSources> trees = Baselines.generateAll(directory);
        Map<String, String> committed = Baselines.committedHashes();
        assertThat(committed).hasSizeGreaterThan(250);
        assertThat(Baselines.hashes(trees)).containsExactlyInAnyOrderEntriesOf(committed);
        trees.values().forEach(tree -> assertThat(tree.file(GeneratedSources.PACKAGE, "ContractRequest")).exists());
    }

    @Test
    void onlyTheValidValueMembersAreStripped() {
        String source = """
                final class A {

                    /**
                     * The fields.
                     */
                    public static String body() {
                        return "{}";
                    }

                    /**
                     * The smallest body.
                     */
                    public static String requiredBody() {
                        return "{}\\n";
                    }

                    /**
                     * The full body.
                     */
                    public static String fullBody() {
                        return "{}\\n";
                    }

                }
                """;
        assertThat(Baselines.withoutValidValueMembers(source)).isEqualTo("""
                final class A {

                    /**
                     * The fields.
                     */
                    public static String body() {
                        return "{}";
                    }

                }
                """);
        assertThat(Baselines.withoutValidValueMembers("final class B {\n}\n")).isEqualTo("final class B {\n}\n");
    }

    @Test
    void theNewMembersComeAfterEveryExistingOne() {
        Map<String, GeneratedSources> trees = Baselines.generateAll(directory.resolve("order"));
        GeneratedSources userAccount = trees.get("plain/user-account");
        String user = userAccount.source("UserV1");
        assertThat(user.indexOf("public static String requiredBody()")).isGreaterThan(user.lastIndexOf("fields("));
        String operation = userAccount.source("GetUserOperation");
        assertThat(operation.indexOf("requiredRequest()")).isGreaterThan(operation.indexOf("public static String path("));
        assertThat(Files.exists(userAccount.sources)).isTrue();
    }
}
