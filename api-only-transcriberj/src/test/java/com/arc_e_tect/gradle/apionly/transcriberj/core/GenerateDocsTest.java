package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6: with generateDocs on, descriptions come from the contract, and a missing
 * one is reported and documented with the placeholder.
 *
 * <p>The hand-written contract-error-descriptions.properties is the reference where
 * it and the contract agree. Where they disagree -- the wording of errors and
 * errors[].message, and errors[].context, which the contract does not describe at
 * all -- the contract is what is generated, by decision.
 */
class GenerateDocsTest {

    @TempDir
    static Path directory;

    static GeneratedSources generated;

    @BeforeAll
    static void generate() {
        generated = GeneratedSources.generate(
                GeneratedSources.FIXTURES.resolve("contracts/user-account/openapi.yaml"), "1.0.0", directory,
                new Settings("user-account", GeneratedSources.PACKAGE, true, "PLACEHOLDER", 3), List.of());
    }

    private static Map<String, String> descriptions(String className) throws Throwable {
        List<?> fields = (List<?>) generated.call(className, "fields", new Class<?>[]{String.class}, "");
        return fields.stream().map(Object::toString).collect(Collectors.toMap(
                f -> f.substring(f.indexOf("path=") + 5, f.indexOf(", type=")),
                f -> f.substring(f.indexOf("description=") + 12, f.indexOf(", subsection="))));
    }

    @Test
    void problemDescriptionsAreTheOnesTheHandWrittenPropertiesFileHeld() throws Throwable {
        Properties handWritten = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("src/handwritten/resources/contract-error-descriptions.properties"))) {
            handWritten.load(in);
        }
        Map<String, String> problem = descriptions("InvalidRequestProblemV1");
        for (String field : List.of("type", "title", "status", "detail", "instance")) {
            assertThat(problem.get(field)).as(field).isEqualTo(handWritten.getProperty("problem." + field));
        }
    }

    @Test
    void whereTheContractAndThePropertiesFileDisagreeTheContractIsGenerated() throws Throwable {
        Map<String, String> problem = descriptions("InvalidRequestProblemV1");
        assertThat(problem.get("errors"))
                .isEqualTo("The individual validation failures that caused this request to be rejected.");
        assertThat(problem.get("errors[].message")).isEqualTo("The message describing a detail of the error.");
        assertThat(problem.get("errors[].context")).isEqualTo("PLACEHOLDER");
    }

    @Test
    void aReferencedSchemaDescribesItselfAndAnInlinePropertyIsDescribedWhereItIsWritten() throws Throwable {
        assertThat(generated.call("UsernameV1", "description", new Class<?>[]{})).isEqualTo(
                "The unique username of the account.\nMust be 5-12 characters, start with a lowercase letter,\n"
                        + "and contain only lowercase alphanumerics, dashes, or underscores.");
        Map<String, String> user = descriptions("UserV1");
        assertThat(user.get("username")).isEqualTo(generated.call("UsernameV1", "description", new Class<?>[]{}));
        assertThat(user.get("emailAddress")).isEqualTo("The email address associated with the account.");
        assertThat(generated.call("UserNotFoundProblemV1", "description", new Class<?>[]{}))
                .isEqualTo("The requested user account does not exist.");
    }

    @Test
    void everyMissingDescriptionIsReportedAndDocumentedWithThePlaceholder() throws Throwable {
        assertThat(generated.call("UserV1", "description", new Class<?>[]{})).isEqualTo("PLACEHOLDER");
        assertThat(generated.report.recommendations()).extracting(GenerationReport.Recommendation::location)
                .contains("/components/schemas/UserV1",
                        "/components/schemas/ErrorDetailV1/properties/context");
        assertThat(generated.report.recommendations())
                .filteredOn(r -> r.location().equals("/components/schemas/ErrorDetailV1/properties/context"))
                .singleElement().extracting(GenerationReport.Recommendation::advice)
                .isEqualTo("no description; give it one, since generateDocs is on and it is documented with the "
                        + "placeholder until then");
        assertThat(generated.report.degraded()).isEmpty();
    }

    @Test
    void withoutGenerateDocsNothingIsReportedAndEveryDescriptionIsEmpty() {
        GeneratedSources off = GeneratedSources.generate(
                GeneratedSources.FIXTURES.resolve("contracts/user-account/openapi.yaml"), "1.0.0",
                directory.resolve("off"), GeneratedSources.settings("user-account"), List.of());
        assertThat(off.report.recommendations()).extracting(GenerationReport.Recommendation::advice)
                .noneMatch(a -> a.startsWith("no description"));
        // Descriptions exist for documentation; with none asked for, there is nothing to say,
        // and the placeholder -- which promises a description later -- would be untrue.
        assertThat(off.source("UsernameV1")).contains("return \"\";").doesNotContain("PLACEHOLDER;");
    }

    @Test
    void reusableComponentsDescribeThemselvesAndAReferenceMayOverrideTheDescription(@TempDir Path into)
            throws Throwable {
        Path contract = into.resolve("openapi.yaml");
        Files.writeString(contract, """
                openapi: 3.1.0
                info: {title: T, version: 1.0.0}
                paths: {}
                components:
                  schemas:
                    Item:
                      x-fragment-path: openapi/Item.yaml
                      description: '  An item.  '
                      type: object
                      properties:
                        owner:
                          $ref: '#/components/schemas/Owner'
                          description: Who owns the item.
                        tags:
                          type: array
                          items:
                            type: object
                            properties:
                              name: {type: string, description: The tag's name.}
                    Owner:
                      x-fragment-path: openapi/Owner.yaml
                      description: An owner.
                      type: object
                      properties:
                        id: {type: string}
                  responses:
                    Found:
                      x-fragment-path: openapi/responses/Found.yaml
                      description: The item was found.
                      content:
                        application/json:
                          schema: {$ref: '#/components/schemas/Item'}
                  parameters:
                    Page:
                      x-fragment-path: openapi/parameters/Page.yaml
                      name: page
                      in: query
                      schema: {type: integer}
                """);
        GeneratedSources g = GeneratedSources.generate(contract, "1.0.0", into,
                new Settings("c", GeneratedSources.PACKAGE, true, "PLACEHOLDER", 3), List.of());

        assertThat(g.call("Item", "description", new Class<?>[]{})).isEqualTo("An item.");
        List<?> fields = (List<?>) g.call("Item", "fields", new Class<?>[]{String.class}, "");
        assertThat(fields).extracting(Object::toString)
                .anySatisfy(f -> assertThat(f).contains("path=owner,").contains("description=Who owns the item."))
                .anySatisfy(f -> assertThat(f).contains("path=tags[].name,").contains("description=The tag's name."))
                .anySatisfy(f -> assertThat(f).contains("path=owner.id,").contains("description=PLACEHOLDER"));
        assertThat(g.call("Found", "description", new Class<?>[]{})).isEqualTo("The item was found.");
        assertThat(g.call("Page", "description", new Class<?>[]{})).isEqualTo("PLACEHOLDER");
        assertThat(g.report.recommendations()).extracting(GenerationReport.Recommendation::location)
                .contains("/components/parameters/Page", "/components/schemas/Item/properties/tags",
                        "/components/schemas/Owner/properties/id");
    }
}
