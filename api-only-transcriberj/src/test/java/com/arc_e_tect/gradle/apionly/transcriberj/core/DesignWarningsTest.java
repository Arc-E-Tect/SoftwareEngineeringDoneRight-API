package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Depth is a design smell: a contract whose recursion or nesting goes three levels
 * deep is warned about, whatever recursionDepth allows.
 */
class DesignWarningsTest {

    @TempDir
    Path directory;

    private GenerationReport generate(String schemas, int recursionDepth) throws Exception {
        Path contract = directory.resolve("openapi.yaml");
        Files.writeString(contract, """
                openapi: 3.1.0
                info: {title: T, version: 1.0.0}
                paths:
                  /a:
                    get:
                      operationId: getA
                      responses:
                        '200':
                          content:
                            application/json:
                              schema: {$ref: '#/components/schemas/Top'}
                components:
                  schemas:
                """ + schemas.indent(4));
        return Generation.run(contract, "1.0.0", "x",
                new Settings("c", "a.b", false, "p", recursionDepth), directory.resolve("out"), List.of(), null);
    }

    private static String schema(String name, String body) {
        return name + ":\n  x-fragment-path: openapi/" + name + ".yaml\n" + body.indent(2);
    }

    private static String object(String name, String property, String target) {
        return schema(name, "type: object\nproperties:\n  " + property + ": {$ref: '#/components/schemas/"
                + target + "'}");
    }

    @Test
    void shallowContractsAndTheDefaultDepthWarnAboutNothing() throws Exception {
        GenerationReport report = generate(object("Top", "b", "B")
                + object("B", "c", "C") + schema("C", "type: object\nproperties:\n  x: {type: string}"), 3);

        assertThat(report.warnings()).isEmpty();
        assertThat(report.render("c", "1.0.0")).contains("0 warning(s)").doesNotContain("Warnings");
    }

    @Test
    void aRecursionDepthAboveThreeIsWarnedAbout() throws Exception {
        GenerationReport report = generate(schema("Top", "type: object"), 4);

        assertThat(report.warnings()).containsExactly(
                "recursionDepth is 4; following a recursion more than 3 times suggests the contract nests "
                        + "deeper than a test should need to");
    }

    @Test
    void aCycleThroughThreeOrMoreComponentsIsWarnedAboutButAShorterOneIsNot() throws Exception {
        GenerationReport report = generate(object("Top", "self", "Top")
                + object("Pair", "other", "Mate") + object("Mate", "back", "Pair")
                + object("A", "b", "Bee") + object("Bee", "c", "Cee") + object("Cee", "a", "A"), 3);

        assertThat(report.warnings()).containsExactly(
                "/components/schemas/Cee/properties/a: a recursive reference through 3 components "
                        + "(A -> Bee -> Cee -> A); a cycle this long is easily not noticed");
    }

    @Test
    void aBodyNestingObjectsThreeLevelsDeepIsWarnedAboutOnceAtItsDeepestPath() throws Exception {
        GenerationReport report = generate(schema("Top", """
                type: object
                properties:
                  b: {$ref: '#/components/schemas/B'}
                  list:
                    type: array
                    items:
                      type: object
                      properties:
                        inner: {type: object, properties: {x: {type: string}}}
                """) + object("B", "c", "C") + object("C", "d", "D")
                + schema("D", "type: object\nproperties:\n  x: {type: string}")
                + object("Loop", "again", "Loop"), 3);

        assertThat(report.warnings()).containsExactly(
                "Top: its body nests objects 3 levels deep, at b.c.d; a body this deep is hard to test "
                        + "and easily designed by accident");
        assertThat(report.render("c", "1.0.0")).contains("1 warning(s)")
                .contains("\nWarnings:\n  Top: its body nests objects 3 levels deep");
    }

    @Test
    void theReferenceContractHasNothingToWarnAbout() {
        GeneratedSources g = GeneratedSources.generate(
                GeneratedSources.FIXTURES.resolve("contracts/user-account/openapi.yaml"), "1.0.0", directory,
                GeneratedSources.settings("user-account"), List.of());
        assertThat(g.report.warnings()).isEmpty();
    }
}
