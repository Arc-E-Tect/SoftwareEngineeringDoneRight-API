package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Descriptions a project owns: resolved through a {@link java.util.ResourceBundle} at the
 * point they are needed, falling back to what generation produced.
 */
class DescriptionBundleTest {

    private static final String BUNDLE = "apionly.descriptions.bundle";
    private static final String LOCALE = "apionly.descriptions.locale";

    @AfterEach
    void clearOverrides() {
        System.clearProperty(BUNDLE);
        System.clearProperty(LOCALE);
    }

    /** A contract with one schema, described or not, and one field. */
    private static final String CONTRACT = """
            openapi: 3.1.0
            info: {title: T, version: 1.0.0}
            paths:
              /users:
                get:
                  operationId: listUsers
                  responses:
                    '200':
                      description: The users.
                      content:
                        application/json:
                          schema: {$ref: '#/components/schemas/UserV1'}
            components:
              schemas:
                UserV1:
                  x-fragment-path: openapi/UserV1.yaml
                  description: A user, as the contract describes it.
                  type: object
                  required: [username]
                  properties:
                    username:
                      type: string
                      description: The username, as the contract describes it.
                    address:
                      type: object
                      properties:
                        street: {type: string}
                    tags:
                      type: array
                      items:
                        type: object
                        properties:
                          name: {type: string}
            """;

    private GeneratedSources generate(Path into, String bundle, boolean generateDocs) throws IOException {
        Path contract = Files.createDirectories(into).resolve("openapi.yaml");
        Files.writeString(contract, CONTRACT);
        return GeneratedSources.generate(contract, "1.0.0", into,
                new Settings("orders", GeneratedSources.PACKAGE, generateDocs, "PLACEHOLDER", 2, bundle),
                List.of());
    }

    /** Writes a bundle onto the generated tree's own classpath, as a project's resources would be. */
    private static void bundle(GeneratedSources generated, String baseName, String locale, String... lines)
            throws IOException {
        String file = baseName.replace('.', '/') + (locale.isEmpty() ? "" : "_" + locale) + ".properties";
        Path target = generated.classesDirectory().resolve(file);
        Files.createDirectories(target.getParent());
        Files.writeString(target, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    @Test
    void aBundleDescribesTheClassAndItsFields(@TempDir Path into) throws Throwable {
        GeneratedSources generated = generate(into, "docs.Descriptions", true);
        bundle(generated, "docs.Descriptions", "",
                "UserV1=A user, as this project documents it.",
                "UserV1.username=The username, as this project documents it.",
                "UserV1.address.street=The street.",
                "UserV1.tags[].name=The tag's name.");

        assertThat(generated.call("UserV1", "description", new Class<?>[]{}))
                .isEqualTo("A user, as this project documents it.");
        assertThat(descriptions(generated)).contains(
                "username=The username, as this project documents it.",
                "address.street=The street.",
                "tags[].name=The tag's name.");
    }

    @Test
    void aKeyTheBundleDoesNotCarryFallsBackToWhatGenerationProduced(@TempDir Path into) throws Throwable {
        GeneratedSources generated = generate(into, "docs.Descriptions", true);
        bundle(generated, "docs.Descriptions", "", "UserV1=A user, as this project documents it.");

        // The class comes from the bundle; the field falls back to the contract's text.
        assertThat(generated.call("UserV1", "description", new Class<?>[]{}))
                .isEqualTo("A user, as this project documents it.");
        assertThat(descriptions(generated)).contains("username=The username, as the contract describes it.");
        // A field the contract does not describe either falls back to the placeholder.
        assertThat(descriptions(generated)).contains("address.street=PLACEHOLDER");
    }

    @Test
    void withoutGenerateDocsTheBundleStillWinsAndThePlaceholderIsTheFallback(@TempDir Path into) throws Throwable {
        GeneratedSources generated = generate(into, "docs.Descriptions", false);
        bundle(generated, "docs.Descriptions", "", "UserV1.username=The username, as this project documents it.");

        assertThat(descriptions(generated)).contains("username=The username, as this project documents it.");
        assertThat(generated.call("UserV1", "description", new Class<?>[]{})).isEqualTo("PLACEHOLDER");
    }

    @Test
    void noBundleOnTheClasspathIsNotAnError(@TempDir Path into) throws Throwable {
        GeneratedSources generated = generate(into, "docs.Missing", true);

        assertThat(generated.call("UserV1", "description", new Class<?>[]{}))
                .isEqualTo("A user, as the contract describes it.");
        assertThat(descriptions(generated)).contains("username=The username, as the contract describes it.");
    }

    @Test
    void aBlankBundleEntryIsNotADescription(@TempDir Path into) throws Throwable {
        GeneratedSources generated = generate(into, "docs.Descriptions", true);
        bundle(generated, "docs.Descriptions", "", "UserV1=", "UserV1.username=   ");

        assertThat(generated.call("UserV1", "description", new Class<?>[]{}))
                .isEqualTo("A user, as the contract describes it.");
        assertThat(descriptions(generated)).contains("username=The username, as the contract describes it.");
    }

    @Test
    void twoLocalesRenderFromOneGeneratedTree(@TempDir Path into) throws Throwable {
        GeneratedSources generated = generate(into, "docs.Descriptions", true);
        bundle(generated, "docs.Descriptions", "", "UserV1=A user.");
        bundle(generated, "docs.Descriptions", "nl", "UserV1=Een gebruiker.");
        bundle(generated, "docs.Descriptions", "de", "UserV1=Ein Benutzer.");

        assertThat(generated.call("UserV1", "description", new Class<?>[]{Locale.class}, Locale.of("nl")))
                .isEqualTo("Een gebruiker.");
        assertThat(generated.call("UserV1", "description", new Class<?>[]{Locale.class}, Locale.of("de")))
                .isEqualTo("Ein Benutzer.");
        assertThat(generated.call("UserV1", "description", new Class<?>[]{Locale.class}, Locale.of("fr")))
                .isEqualTo("A user.");
    }

    @Test
    void theJvmsOwnLocaleNeverAnswersForAnotherOne(@TempDir Path into) throws Throwable {
        GeneratedSources generated = generate(into, "docs.Descriptions", true);
        bundle(generated, "docs.Descriptions", "", "UserV1=A user.");
        bundle(generated, "docs.Descriptions", "nl", "UserV1=Een gebruiker.");
        Locale jvm = Locale.getDefault();
        try {
            // A Dutch developer's machine, asking for the English text of the contract.
            Locale.setDefault(Locale.of("nl", "NL"));

            assertThat(generated.call("UserV1", "description", new Class<?>[]{Locale.class}, Locale.ENGLISH))
                    .isEqualTo("A user.");
            assertThat(strings(generated.call("ContractDescriptions", "missing",
                    new Class<?>[]{Locale.class}, Locale.ENGLISH))).doesNotContain("UserV1");
            // And Dutch is still Dutch.
            assertThat(generated.call("UserV1", "description", new Class<?>[]{Locale.class}, Locale.of("nl")))
                    .isEqualTo("Een gebruiker.");
        } finally {
            Locale.setDefault(jvm);
        }
    }

    @Test
    void aSystemPropertyNamesAnotherBundleAndLocale(@TempDir Path into) throws Throwable {
        GeneratedSources generated = generate(into, "docs.Descriptions", true);
        bundle(generated, "docs.Descriptions", "", "UserV1=A user.");
        bundle(generated, "docs.Other", "", "UserV1=Another user.");
        bundle(generated, "docs.Other", "nl", "UserV1=Een andere gebruiker.");

        System.setProperty(BUNDLE, "docs.Other");
        assertThat(generated.call("UserV1", "description", new Class<?>[]{})).isEqualTo("Another user.");

        System.setProperty(LOCALE, "nl");
        assertThat(generated.call("UserV1", "description", new Class<?>[]{}))
                .isEqualTo("Een andere gebruiker.");
    }

    @Test
    void everyKeyABundleCouldCarryIsKnownAndTheMissingOnesCanBeListed(@TempDir Path into) throws Throwable {
        GeneratedSources generated = generate(into, "docs.Descriptions", true);
        bundle(generated, "docs.Descriptions", "", "UserV1=A user.", "UserV1.username=The username.");

        assertThat(strings(generated.call("ContractDescriptions", "keys", new Class<?>[]{})))
                // A class's own name, then a field per path, array segments and all.
                .containsExactly("UserV1", "UserV1.username", "UserV1.address", "UserV1.address.street",
                        "UserV1.tags", "UserV1.tags[].name");
        assertThat(strings(generated.call("ContractDescriptions", "missing", new Class<?>[]{})))
                .contains("UserV1.address.street", "UserV1.tags[].name")
                .doesNotContain("UserV1", "UserV1.username");
        // A reader in Dutch inherits the base bundle's text, so nothing more is missing.
        assertThat(strings(generated.call("ContractDescriptions", "missing",
                new Class<?>[]{Locale.class}, Locale.of("nl"))))
                .containsExactlyElementsOf(strings(generated.call("ContractDescriptions", "missing",
                        new Class<?>[]{})));
        // A translator working in Dutch has written none of it.
        assertThat(strings(generated.call("ContractDescriptions", "untranslated",
                new Class<?>[]{Locale.class}, Locale.of("nl"))))
                .contains("UserV1", "UserV1.username");
    }

    @Test
    void withoutABundleTheGeneratedSourcesAreTheOnesGeneratedToday(@TempDir Path into) throws Throwable {
        GeneratedSources withBundle = generate(into.resolve("with"), "docs.Descriptions", true);
        GeneratedSources without = generate(into.resolve("without"), null, true);

        assertThat(without.names()).doesNotContain("ContractDescriptions");
        assertThat(without.source("UserV1"))
                .contains("return \"A user, as the contract describes it.\";")
                .doesNotContain("ContractDescriptions");
        assertThat(withBundle.source("UserV1")).contains("ContractDescriptions.of(");
        // The class still answers, without any resolver.
        assertThat(without.call("UserV1", "description", new Class<?>[]{}))
                .isEqualTo("A user, as the contract describes it.");
    }

    /** A generated list, as the strings it holds. */
    private static List<String> strings(Object list) {
        return ((List<?>) list).stream().map(String::valueOf).toList();
    }

    /** Every field of {@code UserV1}, as {@code path=description}. */
    private static List<String> descriptions(GeneratedSources generated) throws Throwable {
        List<?> fields = (List<?>) generated.call("UserV1", "fields", new Class<?>[]{String.class}, "");
        return fields.stream().map(Object::toString)
                .map(f -> f.substring(f.indexOf("path=") + 5, f.indexOf(", type="))
                        + "=" + f.substring(f.indexOf("description=") + 12, f.indexOf(", subsection=")))
                .toList();
    }
}
