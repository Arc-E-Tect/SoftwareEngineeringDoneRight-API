package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.model.ContractModel;
import com.arc_e_tect.gradle.apionly.transcriberj.model.ContractParser;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Finding;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.ClassNames;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Emitter;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.EmitterContext;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One generation run: a contract in, a tree of Java sources out.
 *
 * <p>The core emitter runs first; then every other emitter, in the order given,
 * over the same model and the same class names.
 */
public final class Generation {

    private static final Pattern PACKAGE =
            Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*");

    private Generation() {
    }

    /**
     * Generates one contract's sources, replacing whatever the output directory held.
     *
     * @param contract        the fetched contract document
     * @param contractVersion the version the build locked the contract at
     * @param contractSha256  the SHA-256 the build locked the document at
     * @param settings        how the project asked for the classes
     * @param outputDirectory where the sources go
     * @param emitters        the emitters to run after the core emitter
     * @param endpointIndex   where to write the path of every operation class and inline
     *                        schema class, keyed {@code ClassName.PATH}, for tools that read
     *                        test sources without a classpath; {@code null} to write none
     * @return what could not be generated in full
     * @throws GenerationException when no sources can be generated from the contract
     */
    public static GenerationReport run(Path contract, String contractVersion, String contractSha256,
                                       Settings settings, Path outputDirectory, List<Emitter> emitters,
                                       Path endpointIndex) {
        return run(contract, null, contractVersion, contractSha256, settings, outputDirectory, emitters,
                endpointIndex);
    }

    /**
     * Generates one contract's sources from both of its documents.
     *
     * <p>They are read into one model, so a fragment both use -- a username in a
     * response and in an event payload -- is one component, and one class.
     *
     * @param contract        the fetched OpenAPI document
     * @param asyncContract   the fetched AsyncAPI document, or {@code null} when the
     *                        contract has none
     * @param contractVersion the version the build locked the contract at
     * @param contractSha256  the SHA-256 the build locked the document at
     * @param settings        how the project asked for the classes
     * @param outputDirectory where the sources go
     * @param emitters        the emitters to run after the core emitter
     * @param endpointIndex   where to write the path of every operation class and inline
     *                        schema class, keyed {@code ClassName.PATH}, for tools that read
     *                        test sources without a classpath; {@code null} to write none
     * @return what could not be generated in full
     * @throws GenerationException when no sources can be generated from the contract
     */
    public static GenerationReport run(Path contract, Path asyncContract, String contractVersion,
                                       String contractSha256, Settings settings, Path outputDirectory,
                                       List<Emitter> emitters, Path endpointIndex) {
        if (settings.basePackage() == null || !PACKAGE.matcher(settings.basePackage()).matches()) {
            throw new GenerationException("Contract " + settings.contract() + ": basePackage "
                    + settings.basePackage() + " is not a Java package name.");
        }
        ContractModel model;
        try {
            model = ContractParser.parse(contract, asyncContract);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!contractVersion.equals(model.version())) {
            throw new GenerationException("Contract " + settings.contract() + " is locked at version "
                    + contractVersion + ", but " + contract + " says it is version " + model.version()
                    + ". Run fetchApiSpec, and check that the document was not edited.");
        }

        clean(outputDirectory);
        GenerationReport report = new GenerationReport();
        report.findings(model.findings());
        Shapes shapes = new Shapes(model);
        CoreClassNames names = new CoreClassNames(model, shapes, report);
        DesignWarnings.check(model, settings, shapes, names, report);

        CoreEmitter core = new CoreEmitter(shapes, names, contractSha256, report);
        core.emit(new Context(model, settings, names, outputDirectory, report, core.id()));
        for (Emitter emitter : emitters) {
            emitter.emit(new Context(model, settings, names, outputDirectory, report, emitter.id()));
        }
        if (endpointIndex != null) {
            writeEndpointIndex(endpointIndex, settings, model, names);
        }
        return report;
    }

    private static void writeEndpointIndex(Path file, Settings settings, ContractModel model, CoreClassNames names) {
        StringBuilder out = new StringBuilder()
                .append("# The path of every class the API-Only TranscriberJ generated from contract ")
                .append(settings.contract()).append(' ').append(model.version()).append(",\n")
                .append("# keyed ClassName.PATH, for tools that read test sources without a classpath.\n");
        for (var generated : names.all()) {
            String path = names.path(generated);
            if (path != null) {
                out.append(generated.simpleName()).append(".PATH=").append(propertyValue(path)).append('\n');
            }
        }
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, out, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A value as a .properties file needs it written, in ISO 8859-1. */
    private static String propertyValue(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 92) {
                out.append(c).append(c);
            } else if (i == 0 && c == ' ') {
                out.append((char) 92).append(c);
            } else if (c < 0x20 || c > 0x7e) {
                out.append((char) 92).append('u').append(String.format("%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static void clean(Path directory) {
        try {
            if (Files.exists(directory)) {
                try (Stream<Path> files = Files.walk(directory)) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(file);
                    }
                }
            }
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What one emitter is given. */
    private record Context(ContractModel model, Settings settings, ClassNames names, Path outputDirectory,
                           GenerationReport report, String emitter) implements EmitterContext {

        @Override
        public void writeJava(String packageName, String simpleName, String source) {
            if (!PACKAGE.matcher(packageName).matches() || !PACKAGE.matcher(simpleName).matches()
                    || simpleName.contains(".")) {
                throw new GenerationException("Emitter " + emitter + " wrote a class with an invalid name: "
                        + packageName + "." + simpleName);
            }
            Path file = outputDirectory.resolve(packageName.replace('.', '/')).resolve(simpleName + ".java");
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, source, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public String degraded(String className, String method, Finding finding) {
            report.degraded(new GenerationReport.Degraded(emitter, className, method, finding));
            String message = className + "." + method + " cannot be generated from contract "
                    + settings.contract() + ": " + finding.construct() + " at " + finding.location()
                    + " (" + finding.detail() + ")"
                    + (finding.remedy() == null ? "" : "; " + finding.remedy())
                    + ". See the API-Only TranscriberJ report.";
            return "throw new UnsupportedOperationException(" + JavaText.literal(message) + ");";
        }
    }
}
