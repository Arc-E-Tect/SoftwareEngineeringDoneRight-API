package com.arc_e_tect.gradle.apionly.subscriber;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ManifestTest {

    private static final String VALID = """
        {
          "schemaVersion": 1,
          "target": "user-account",
          "version": "2.1.0",
          "producedAt": "2026-09-11T12:00:00Z",
          "closureSha256": "%s",
          "source": { "repository": "git@example:specs.git", "commit": "a1b2c3d4" },
          "files": [
            { "path": "openapi.yaml",  "sha256": "%s" },
            { "path": "asyncapi.yaml", "sha256": "%s" }
          ]
        }
        """.formatted("c".repeat(64), "a".repeat(64), "b".repeat(64));

    private static File write(Path dir, String json) throws IOException {
        File file = dir.resolve("manifest.json").toFile();
        Files.writeString(file.toPath(), json);
        return file;
    }

    @Test
    void readsTheFieldsTheSubscriberActsOn(@TempDir Path dir) throws IOException {
        Manifest manifest = Manifest.read(write(dir, VALID));
        assertEquals("user-account", manifest.target());
        assertEquals("2.1.0", manifest.version());
        assertEquals("c".repeat(64), manifest.closureSha256());
        assertEquals(2, manifest.files().size());
        assertEquals("a".repeat(64), manifest.files().get("openapi.yaml"));
    }

    @Test
    void anAbsentManifestSaysWhatIsWrong(@TempDir Path dir) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> Manifest.read(dir.resolve("manifest.json").toFile()));
        assertTrue(e.getMessage().contains("api-only-publisher"),
            "the message should say what kind of archive was expected");
    }

    @Test
    void anUnknownSchemaVersionIsRefusedRatherThanGuessedAt(@TempDir Path dir) throws IOException {
        File file = write(dir, VALID.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Manifest.read(file));
        assertTrue(e.getMessage().contains("schemaVersion 2"));
    }

    @Test
    void aManifestListingNoFilesIsRefused(@TempDir Path dir) throws IOException {
        File file = write(dir, """
            { "schemaVersion": 1, "target": "x", "version": "1.0.0", "files": [] }
            """);
        assertThrows(IllegalStateException.class, () -> Manifest.read(file));
    }
}
