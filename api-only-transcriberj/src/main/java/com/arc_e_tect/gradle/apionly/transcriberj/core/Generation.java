package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.model.ContractModel;
import com.arc_e_tect.gradle.apionly.transcriberj.model.ContractParser;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Finding;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.ClassNames;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Emitter;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.EmitterContext;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.InvalidRequestCase;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
    private static final Pattern STATUS = Pattern.compile("[1-5][0-9][0-9]");

    private Generation() {
    }

    /**
     * Generates one contract's sources, replacing whatever the output directory held.
     *
     * @param contract        the fetched contract document
     * @param contractVersion the version the build locked the contract at
     * @param contractSha256  the SHA-256 the build locked the document at
     * @param settings          how the project asked for the classes
     * @param outputDirectory   where the sources go
     * @param resourceDirectory where a resource an emitter writes goes
     * @param emitters          the emitters to run after the core emitter
     * @param endpointIndex     where to write the path of every operation class and inline
     *                          schema class, keyed {@code ClassName.PATH}, for tools that read
     *                          test sources without a classpath; {@code null} to write none
     * @return what could not be generated in full
     * @throws GenerationException when no sources can be generated from the contract
     */
    public static GenerationReport run(Path contract, String contractVersion, String contractSha256,
                                       Settings settings, Path outputDirectory, Path resourceDirectory,
                                       List<Emitter> emitters, Path endpointIndex) {
        return run(contract, null, contractVersion, contractSha256, settings, outputDirectory, resourceDirectory,
                emitters, endpointIndex);
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
     * @param settings          how the project asked for the classes
     * @param outputDirectory   where the sources go
     * @param resourceDirectory where a resource an emitter writes goes
     * @param emitters          the emitters to run after the core emitter
     * @param endpointIndex     where to write the path of every operation class and inline
     *                          schema class, keyed {@code ClassName.PATH}, for tools that read
     *                          test sources without a classpath; {@code null} to write none
     * @return what could not be generated in full
     * @throws GenerationException when no sources can be generated from the contract
     */
    public static GenerationReport run(Path contract, Path asyncContract, String contractVersion,
                                       String contractSha256, Settings settings, Path outputDirectory,
                                       Path resourceDirectory, List<Emitter> emitters, Path endpointIndex) {
        validate(settings);
        List<String> unknown = settings.emitterOptions().keySet().stream()
                .filter(id -> emitters.stream().noneMatch(e -> e.id().equals(id))).toList();
        if (!unknown.isEmpty()) {
            throw new GenerationException("Contract " + settings.contract() + ": emitterOptions names "
                    + String.join(", ", unknown) + ", but no emitter with that id is on the transcriberjEmitters "
                    + "classpath; the emitters there are " + (emitters.isEmpty() ? "none"
                    : String.join(", ", emitters.stream().map(Emitter::id).toList())) + ".");
        }
        Derivation derivation = derive(contract, asyncContract, contractVersion, contractSha256, settings);
        writeCore(derivation, outputDirectory, resourceDirectory, endpointIndex);
        Sink sink = new DirectorySink(outputDirectory, resourceDirectory, null);
        for (Emitter emitter : emitters) {
            emitter.emit(derivation.context(emitter.id(), sink, derivation.report));
        }
        return derivation.report;
    }

    /**
     * Derives everything the core generates from a contract -- the model, the class names, the
     * invalid-request cases, the report -- and the core's own sources, held in memory. Nothing is
     * written: {@link #writeCore} writes the core's output, and {@link #runEmitter} runs one
     * emitter over the same derivation.
     *
     * <p>Every step is deterministic, so two derivations of one contract with one set of
     * settings are the same, whichever task makes them.
     *
     * @param contract        the fetched OpenAPI document
     * @param asyncContract   the fetched AsyncAPI document, or {@code null} when the contract has none
     * @param contractVersion the version the build locked the contract at
     * @param contractSha256  the SHA-256 the build locked the document at
     * @param settings        how the project asked for the classes
     * @return the derivation
     * @throws GenerationException when no sources can be generated from the contract
     */
    public static Derivation derive(Path contract, Path asyncContract, String contractVersion,
                                    String contractSha256, Settings settings) {
        validate(settings);
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
        GenerationReport report = new GenerationReport();
        report.findings(model.findings());
        Shapes shapes = new Shapes(model);
        CoreClassNames names = new CoreClassNames(model, shapes, report);
        DesignWarnings.check(model, settings, shapes, names, report);

        CoreEmitter core = new CoreEmitter(shapes, names, contractSha256, report);
        BufferSink buffer = new BufferSink();
        core.emit(new Context(model, settings, names, buffer, report, core.id(), Map.of()));
        return new Derivation(model, settings, names, report, core.invalidRequestCases(), buffer);
    }

    /** Settings no generation can start from: a package that is none, a status that is none. */
    private static void validate(Settings settings) {
        if (settings.basePackage() == null || !PACKAGE.matcher(settings.basePackage()).matches()) {
            throw new GenerationException("Contract " + settings.contract() + ": basePackage "
                    + settings.basePackage() + " is not a Java package name.");
        }
        if (!STATUS.matcher(settings.invalidRequestStatus()).matches()) {
            throw new GenerationException("Contract " + settings.contract() + ": invalidRequestStatus "
                    + settings.invalidRequestStatus() + " is not a status code; give one such as 400 or 422.");
        }
    }

    /**
     * Writes the core's sources and resources, replacing whatever the two directories held, and
     * the endpoint index.
     *
     * @param derivation        the derivation
     * @param outputDirectory   where the core's sources go
     * @param resourceDirectory where the core's resources go
     * @param endpointIndex     where the endpoint index goes; {@code null} to write none
     * @return the core's report
     */
    public static GenerationReport writeCore(Derivation derivation, Path outputDirectory, Path resourceDirectory,
                                             Path endpointIndex) {
        clean(outputDirectory);
        clean(resourceDirectory);
        derivation.buffer.flush(new DirectorySink(outputDirectory, resourceDirectory, null));
        if (endpointIndex != null) {
            writeEndpointIndex(endpointIndex, derivation.settings, derivation.model, derivation.names);
        }
        return derivation.report;
    }

    /**
     * Runs one emitter over a derivation, replacing whatever its three directories held.
     *
     * @param derivation        the derivation
     * @param emitter           the emitter
     * @param javaDirectory     where its Java sources go
     * @param resourceDirectory where its classpath resources go
     * @param filesDirectory    where its files go
     * @return what this emitter reported: the methods it degraded
     */
    public static GenerationReport runEmitter(Derivation derivation, Emitter emitter, Path javaDirectory,
                                              Path resourceDirectory, Path filesDirectory) {
        clean(javaDirectory);
        clean(resourceDirectory);
        clean(filesDirectory);
        GenerationReport report = new GenerationReport();
        emitter.emit(derivation.context(emitter.id(),
                new DirectorySink(javaDirectory, resourceDirectory, filesDirectory), report));
        return report;
    }

    /**
     * What {@link #derive} works out once: the model, the class names, the cases and the core's
     * report, and the core's output, not yet written.
     */
    public static final class Derivation {

        private final ContractModel model;
        private final Settings settings;
        private final CoreClassNames names;
        private final GenerationReport report;
        private final Map<String, List<InvalidRequestCase>> cases;
        private final BufferSink buffer;

        private Derivation(ContractModel model, Settings settings, CoreClassNames names, GenerationReport report,
                           Map<String, List<InvalidRequestCase>> cases, BufferSink buffer) {
            this.model = model;
            this.settings = settings;
            this.names = names;
            this.report = report;
            this.cases = cases;
            this.buffer = buffer;
        }

        /**
         * The core's report.
         *
         * @return the report
         */
        public GenerationReport report() {
            return report;
        }

        private EmitterContext context(String emitter, Sink sink, GenerationReport into) {
            return new Context(model, settings, names, sink, into, emitter, cases);
        }
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

    /** Where an emitter's output goes: directories on disk, or a buffer. */
    private interface Sink {

        void java(String packageName, String simpleName, String source);

        void resource(String path, String content);

        void file(String path, byte[] content);
    }

    /** Writes to directories; a {@code null} files directory refuses files. */
    private record DirectorySink(Path javaDirectory, Path resourceDirectory, Path filesDirectory) implements Sink {

        @Override
        public void java(String packageName, String simpleName, String source) {
            write(javaDirectory.resolve(packageName.replace('.', '/')).resolve(simpleName + ".java"),
                    source.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void resource(String path, String content) {
            write(resourceDirectory.resolve(path), content.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void file(String path, byte[] content) {
            if (filesDirectory == null) {
                throw new UnsupportedOperationException("This generation run has no files directory, so emitter "
                        + "output " + path + " cannot be written.");
            }
            write(filesDirectory.resolve(path), content);
        }

        private static void write(Path file, byte[] content) {
            try {
                Files.createDirectories(file.getParent());
                Files.write(file, content);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Holds output in memory, in the order it was written, until it is flushed to a sink. */
    private static final class BufferSink implements Sink {

        private final List<Runnable> writes = new java.util.ArrayList<>();
        private Sink target;

        @Override
        public void java(String packageName, String simpleName, String source) {
            writes.add(() -> target.java(packageName, simpleName, source));
        }

        @Override
        public void resource(String path, String content) {
            writes.add(() -> target.resource(path, content));
        }

        @Override
        public void file(String path, byte[] content) {
            writes.add(() -> target.file(path, content));
        }

        void flush(Sink sink) {
            target = sink;
            writes.forEach(Runnable::run);
        }
    }

    /** What one emitter is given: the core emitter, which derives the cases, is given none. */
    private record Context(ContractModel model, Settings settings, ClassNames names, Sink sink,
                           GenerationReport report, String emitter,
                           Map<String, List<InvalidRequestCase>> cases)
            implements EmitterContext {

        @Override
        public List<InvalidRequestCase> invalidRequestCases(String location) {
            return cases.getOrDefault(location, List.of());
        }

        @Override
        public void writeJava(String packageName, String simpleName, String source) {
            if (!PACKAGE.matcher(packageName).matches() || !PACKAGE.matcher(simpleName).matches()
                    || simpleName.contains(".")) {
                throw new GenerationException("Emitter " + emitter + " wrote a class with an invalid name: "
                        + packageName + "." + simpleName);
            }
            sink.java(packageName, simpleName, source);
        }

        @Override
        public void writeResource(String path, String content) {
            if (!isValidPath(path)) {
                throw new GenerationException("Emitter " + emitter + " wrote a resource with an invalid path: "
                        + path);
            }
            sink.resource(path, content);
        }

        @Override
        public void writeFile(String path, byte[] content) {
            if (!isValidPath(path)) {
                throw new GenerationException("Emitter " + emitter + " wrote a file with an invalid path: " + path);
            }
            sink.file(path, content);
        }

        private static boolean isValidPath(String path) {
            if (path.isEmpty() || path.startsWith("/") || path.endsWith("/") || path.contains("\\")) {
                return false;
            }
            return Arrays.stream(path.split("/", -1))
                    .noneMatch(segment -> segment.isEmpty() || segment.equals(".") || segment.equals(".."));
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
