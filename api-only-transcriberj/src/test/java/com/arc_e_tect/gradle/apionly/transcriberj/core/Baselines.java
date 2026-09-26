package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The committed baselines the valid-value work is held to: the hash of every source
 * generated before it (T12.11), and the golden valid values of the reference
 * contracts (T12.10). Each can be re-recorded, deliberately, by a Gradle task.
 */
public final class Baselines {

    static final Path SOURCES = GeneratedSources.FIXTURES.resolve("valid-values/baseline-sources.sha256");
    static final Path GOLDEN = GeneratedSources.FIXTURES.resolve("valid-values/golden");

    /** Where the valid-value members start: the Javadoc of the first of them, which come last in a class. */
    private static final Pattern NEW_MEMBERS = Pattern.compile("\n    /\\*\\*\n(?:     \\*.*\n)*?     \\*/\n"
            + "    public static (?:String requiredBody|ContractRequest requiredRequest)\\(\\) \\{\n");

    private Baselines() {
    }

    /** A generated source without the members valid-value generation added to it. */
    static String withoutValidValueMembers(String source) {
        Matcher m = NEW_MEMBERS.matcher(source);
        if (!m.find()) return source;
        return source.substring(0, m.start() + 1) + "}\n";
    }

    /** Every tree the baseline covers, by the name its lines start with, generated into a directory. */
    static Map<String, GeneratedSources> generateAll(Path into) {
        Map<String, GeneratedSources> out = new TreeMap<>();
        for (String contract : ValidValueFixtures.REFERENCE) {
            Path dir = GeneratedSources.FIXTURES.resolve("contracts").resolve(contract);
            Path async = dir.resolve("asyncapi.yaml");
            Path asyncOrNull = Files.exists(async) ? async : null;
            out.put("plain/" + contract, GeneratedSources.generate(dir.resolve("openapi.yaml"), asyncOrNull, "1.0.0",
                    into.resolve("plain/" + contract), GeneratedSources.settings(contract), List.of()));
            out.put("docs/" + contract, GeneratedSources.generate(dir.resolve("openapi.yaml"), asyncOrNull, "1.0.0",
                    into.resolve("docs/" + contract), new Settings(contract, GeneratedSources.PACKAGE, true,
                            "PLACEHOLDER", 3, "docs.Descriptions"), List.of()));
        }
        for (String contract : ValidValueFixtures.CORPUS) {
            out.put("corpus/" + contract, GeneratedSources.generate(
                    ValidValueFixtures.CORPUS_DIRECTORY.resolve(contract + ".yaml"), "1.0.0",
                    into.resolve("corpus/" + contract), GeneratedSources.settings(contract), List.of()));
        }
        return out;
    }

    /** The hash of every source of every tree, the valid-value members stripped, ContractRequest left out. */
    static Map<String, String> hashes(Map<String, GeneratedSources> trees) {
        Map<String, String> out = new TreeMap<>();
        trees.forEach((name, tree) -> {
            try (Stream<Path> files = Files.walk(tree.sources)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    if (file.getFileName().toString().equals("ContractRequest.java")) continue;
                    String source = withoutValidValueMembers(Files.readString(file, StandardCharsets.UTF_8));
                    out.put(name + "/" + tree.sources.relativize(file).toString().replace('\\', '/'), sha256(source));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return out;
    }

    /** The committed hashes, by file. */
    static Map<String, String> committedHashes() {
        Map<String, String> out = new TreeMap<>();
        try {
            for (String line : Files.readAllLines(SOURCES)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                int space = line.lastIndexOf(' ');
                out.put(line.substring(0, space), line.substring(space + 1));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A reference contract's machine-readable report, as its golden file holds it: indented, members sorted. */
    static String golden(ValidValueFixtures.Fixture f) {
        return pretty(f.report(), "") + "\n";
    }

    /** JSON indented by two spaces, one member or item per line, independent of any library's printer. */
    static String pretty(JsonNode node, String indent) {
        String inner = indent + "  ";
        if (node.isObject() && !node.isEmpty()) {
            StringBuilder out = new StringBuilder("{\n");
            var members = node.properties().iterator();
            while (members.hasNext()) {
                var m = members.next();
                out.append(inner).append(Oracle.JSON.writeValueAsString(m.getKey())).append(": ")
                        .append(pretty(m.getValue(), inner)).append(members.hasNext() ? ",\n" : "\n");
            }
            return out.append(indent).append('}').toString();
        }
        if (node.isArray() && !node.isEmpty()) {
            StringBuilder out = new StringBuilder("[\n");
            for (int i = 0; i < node.size(); i++) {
                out.append(inner).append(pretty(node.get(i), inner)).append(i < node.size() - 1 ? ",\n" : "\n");
            }
            return out.append(indent).append(']').toString();
        }
        return Oracle.JSON.writeValueAsString(node);
    }

    /**
     * Re-records a baseline: {@code sources} rewrites the hash file from what is generated
     * now; {@code golden} rewrites the golden valid values. Review the diff.
     *
     * @param args {@code sources} or {@code golden}
     * @throws IOException when a file cannot be written
     */
    public static void main(String[] args) throws IOException {
        Path into = Files.createTempDirectory("transcriberj-baselines");
        if (args.length == 1 && args[0].equals("sources")) {
            List<String> header = Files.readAllLines(SOURCES).stream().filter(l -> l.startsWith("#")).toList();
            StringBuilder out = new StringBuilder(String.join("\n", header)).append('\n');
            hashes(generateAll(into)).forEach((file, hash) -> out.append(file).append(' ').append(hash).append('\n'));
            Files.writeString(SOURCES, out, StandardCharsets.UTF_8);
        } else if (args.length == 1 && args[0].equals("golden")) {
            Files.createDirectories(GOLDEN);
            for (String contract : ValidValueFixtures.REFERENCE) {
                ValidValueFixtures.Fixture f = ValidValueFixtures.reference(contract, into.resolve(contract));
                Files.writeString(GOLDEN.resolve(contract + ".json"), golden(f), StandardCharsets.UTF_8);
            }
        } else {
            throw new IllegalArgumentException("say what to record: sources or golden");
        }
    }
}
