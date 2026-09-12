package com.arc_e_tect.gradle.apionly.subscriber;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The manifest.json the Publisher ships inside every archive.
 *
 * This is the entire coupling between the two components, which is why it is
 * parsed defensively and why nothing here depends on a JSON library: dragging a
 * dependency into every implementation repository to read six fields would be a
 * poor trade.
 *
 * Gradle verifying that a downloaded artifact matches its coordinates is not the
 * same guarantee. That checks the artifact; this checks that the files actually
 * extracted into the build still match what the producer published, which is the
 * one that catches a hand-edited document in build/.
 */
public final class Manifest {

    private static final Pattern STRING_FIELD =
        Pattern.compile("\"(schemaVersion|target|version|producedAt|closureSha256)\"\\s*:\\s*(?:\"([^\"]*)\"|(\\d+))");
    private static final Pattern FILE_ENTRY =
        Pattern.compile("\\{\\s*\"path\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"sha256\"\\s*:\\s*\"([0-9a-f]{64})\"\\s*\\}");

    private final Map<String, String> fields = new LinkedHashMap<>();
    private final Map<String, String> files = new LinkedHashMap<>();

    private Manifest() {
        // Created only by read(File).
    }

    /**
     * Reads the manifest from a fetched archive.
     *
     * @param file the {@code manifest.json} inside the unpacked archive
     * @return the parsed manifest
     * @throws IllegalStateException if the file is absent, lists no files, or
     *         declares a schema version this class does not understand
     * @throws java.io.UncheckedIOException if it cannot be read
     */
    public static Manifest read(File file) {
        if (!file.exists()) {
            throw new IllegalStateException(
                "no manifest.json in the fetched archive at " + file.getParent()
                + "; the artifact was not produced by api-only-publisher, or is truncated");
        }
        String json;
        try {
            json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }

        Manifest manifest = new Manifest();
        Matcher fieldMatcher = STRING_FIELD.matcher(json);
        while (fieldMatcher.find()) {
            manifest.fields.put(fieldMatcher.group(1),
                fieldMatcher.group(2) != null ? fieldMatcher.group(2) : fieldMatcher.group(3));
        }
        Matcher fileMatcher = FILE_ENTRY.matcher(json);
        while (fileMatcher.find()) {
            manifest.files.put(fileMatcher.group(1), fileMatcher.group(2));
        }

        if (manifest.files.isEmpty()) {
            throw new IllegalStateException(file + " lists no files");
        }
        String schemaVersion = manifest.fields.get("schemaVersion");
        if (schemaVersion != null && !schemaVersion.equals("1")) {
            throw new IllegalStateException(
                file + " declares manifest schemaVersion " + schemaVersion
                + "; this version of the subscriber understands 1");
        }
        return manifest;
    }

    /**
     * The contract this archive holds.
     *
     * @return the target name, or {@code null} if the manifest omits it
     */
    public String target() {
        return fields.get("target");
    }

    /**
     * The version the producer published this archive as.
     *
     * <p>Checked against the version that was resolved, so an archive published
     * under the wrong coordinates is caught rather than unpacked.</p>
     *
     * @return the version, or {@code null} if the manifest omits it
     */
    public String version() {
        return fields.get("version");
    }

    /**
     * The content hash of the producer's dependency closure for this target.
     *
     * <p>Recorded by the publisher so a later commit can tell whether the
     * fragments behind a contract actually changed. The subscriber does not act
     * on it; it is carried so that provenance travels with the artifact.</p>
     *
     * @return the closure digest, or {@code null} if the manifest omits it
     */
    public String closureSha256() {
        return fields.get("closureSha256");
    }

    /**
     * Each declared document mapped to its SHA-256.
     *
     * @return the declared filenames and digests
     */
    public Map<String, String> files() {
        return files;
    }
}
