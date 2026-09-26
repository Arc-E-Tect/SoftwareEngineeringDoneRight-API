package com.arc_e_tect.gradle.apionly.transcriberj;

import com.arc_e_tect.gradle.apionly.subscriber.ApiOnlySubscriberExtension;
import com.arc_e_tect.gradle.apionly.subscriber.Lockfile;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T12.12, as a build sees it: the machine-readable report is written beside the text
 * report, wherever that is configured to go, and the count line the task logs counts
 * what the reports list.
 */
@DisplayName("T12.12 Report files")
class ValidValueReportFileTest {

    @TempDir
    Path projectDir;

    private Project project;

    @BeforeEach
    void setUp() throws java.io.IOException {
        projectDir = projectDir.toRealPath();
        project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build();
    }

    @Test
    void theMachineReadableReportGoesBesideTheReport() {
        project.getPluginManager().apply(ApiOnlyTranscriberJPlugin.class);
        project.getExtensions().getByType(ApiOnlySubscriberExtension.class).subscribe("user-account");
        TranscriberJSubscription subscription = project.getExtensions().getByType(ApiOnlyTranscriberJExtension.class)
                .subscription("user-account", s -> s.getBasePackage().set("com.example.contract"));
        GenerateContractSourcesTask generate = (GenerateContractSourcesTask)
                project.getTasks().getByName("generateContractSourcesUserAccount");

        assertThat(generate.getValidValuesReport().get().getAsFile()).isEqualTo(
                projectDir.resolve("build/reports/transcriberj/user-account.valid-values.json").toFile());
        subscription.getReportFile().set(projectDir.resolve("reports/orders.txt").toFile());
        assertThat(generate.getValidValuesReport().get().getAsFile())
                .isEqualTo(projectDir.resolve("reports/orders.valid-values.json").toFile());
        subscription.getReportFile().set(projectDir.resolve("reports/orders").toFile());
        assertThat(generate.getValidValuesReport().get().getAsFile())
                .isEqualTo(projectDir.resolve("reports/orders.valid-values.json").toFile());
    }

    @Test
    void theTaskWritesBothReportsAndCountsWhatTheyList() throws Exception {
        project.getPluginManager().apply(ApiOnlyTranscriberJPlugin.class);
        Path contract = Path.of(System.getProperty("transcriberj.fixtures"), "valid-values/corpus/parameters.yaml");
        Lockfile lock = new Lockfile();
        lock.put(new Lockfile.Entry("parameters", "1.0.0", "file", Map.of("openapi.yaml", "abc")));
        File lockfile = projectDir.resolve("apionly.lock").toFile();
        lock.write(lockfile);
        GenerateContractSourcesTask task = project.getTasks().create("generate",
                ApiOnlyTranscriberJPluginTest.InProcessGenerateTask.class);
        task.getContract().set(contract.toFile());
        task.getLockfile().set(lockfile);
        task.getContractName().set("parameters");
        task.getBasePackage().set("com.example.contract");
        task.getRecursionDepth().set(3);
        task.getGenerateDocs().set(false);
        task.getDescriptionPlaceholder().set("P");
        task.getOutputDirectory().set(projectDir.resolve("out").toFile());
        task.getResourceDirectory().set(projectDir.resolve("out-resources").toFile());
        task.getReportFile().set(projectDir.resolve("report.txt").toFile());
        task.getEndpointIndex().set(projectDir.resolve("index.properties").toFile());
        task.getValidValuesReport().set(projectDir.resolve("report.valid-values.json").toFile());

        task.generate();

        List<String> text = Files.readAllLines(projectDir.resolve("report.txt"));
        assertThat(text.get(1)).endsWith(", 4 method(s) without a valid value, 6 parameter(s) not supported yet");
        String json = Files.readString(projectDir.resolve("report.valid-values.json"));
        assertThat(json).startsWith("{\"bodies\":[").contains("\"contract\":\"parameters\"")
                .contains("\"class\":\"GetItemOperation\"").endsWith("}\n");
        assertThat(json.split("\"unsatisfiable\":", -1)).hasSize(4 + 1);
        assertThat(json.split("\"reason\":", -1)).hasSize(4 + 6 + 1);
        assertThat(projectDir.resolve("out/com/example/contract/ContractRequest.java")).exists();
    }

    @Test
    void withoutAPlaceForTheMachineReadableReportOnlyTheTextOneIsWritten() throws Exception {
        project.getPluginManager().apply(ApiOnlyTranscriberJPlugin.class);
        Path contract = Path.of(System.getProperty("transcriberj.fixtures"), "valid-values/corpus/exclusive-3.0.yaml");
        Lockfile lock = new Lockfile();
        lock.put(new Lockfile.Entry("bounds", "1.0.0", "file", Map.of("openapi.yaml", "abc")));
        File lockfile = projectDir.resolve("apionly.lock").toFile();
        lock.write(lockfile);
        GenerateContractSourcesTask task = project.getTasks().create("generate",
                ApiOnlyTranscriberJPluginTest.InProcessGenerateTask.class);
        task.getContract().set(contract.toFile());
        task.getLockfile().set(lockfile);
        task.getContractName().set("bounds");
        task.getBasePackage().set("com.example.contract");
        task.getRecursionDepth().set(3);
        task.getGenerateDocs().set(false);
        task.getDescriptionPlaceholder().set("P");
        task.getOutputDirectory().set(projectDir.resolve("out").toFile());
        task.getResourceDirectory().set(projectDir.resolve("out-resources").toFile());
        task.getReportFile().set(projectDir.resolve("report.txt").toFile());
        task.getEndpointIndex().set(projectDir.resolve("index.properties").toFile());

        task.generate();

        assertThat(projectDir.resolve("report.txt")).exists();
        try (var files = Files.list(projectDir)) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".json"));
        }
    }
}
