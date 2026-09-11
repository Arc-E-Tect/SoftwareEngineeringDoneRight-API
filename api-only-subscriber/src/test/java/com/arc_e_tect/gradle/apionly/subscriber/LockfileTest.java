package com.arc_e_tect.gradle.apionly.subscriber;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LockfileTest {

    private static Map<String, String> files(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void roundTripsAnEntry(@TempDir Path dir) {
        File file = dir.resolve("apionly.lock").toFile();
        Lockfile written = new Lockfile();
        written.put(new Lockfile.Entry("user-account", "2.1.0", "maven",
            files("openapi.yaml", "a".repeat(64), "asyncapi.yaml", "b".repeat(64))));
        written.write(file);

        Lockfile.Entry read = Lockfile.read(file).get("user-account");
        assertNotNull(read);
        assertEquals("2.1.0", read.version());
        assertEquals("maven", read.channel());
        assertEquals("a".repeat(64), read.files().get("openapi.yaml"));
        assertEquals("b".repeat(64), read.files().get("asyncapi.yaml"));
    }

    @Test
    void keepsTargetsSortedSoADiffMeansSomethingChanged(@TempDir Path dir) throws IOException {
        File file = dir.resolve("apionly.lock").toFile();
        Lockfile lock = new Lockfile();
        lock.put(new Lockfile.Entry("zebra", "1.0.0", "maven", files("openapi.yaml", "c".repeat(64))));
        lock.put(new Lockfile.Entry("alpha", "1.0.0", "maven", files("openapi.yaml", "d".repeat(64))));
        lock.write(file);

        String text = Files.readString(file.toPath());
        assertTrue(text.indexOf("target alpha") < text.indexOf("target zebra"),
            "entries must be ordered so that a reordering is never mistaken for a change");
    }

    @Test
    void readingAMissingFileGivesAnEmptyLock(@TempDir Path dir) {
        Lockfile lock = Lockfile.read(dir.resolve("absent.lock").toFile());
        assertTrue(lock.targets().isEmpty());
        assertNull(lock.get("anything"));
    }

    @Test
    void updatingOneTargetLeavesTheOthersAlone(@TempDir Path dir) {
        File file = dir.resolve("apionly.lock").toFile();
        Lockfile lock = new Lockfile();
        lock.put(new Lockfile.Entry("a", "1.0.0", "maven", files("openapi.yaml", "1".repeat(64))));
        lock.put(new Lockfile.Entry("b", "1.0.0", "maven", files("openapi.yaml", "2".repeat(64))));
        lock.write(file);

        Lockfile reread = Lockfile.read(file);
        reread.put(new Lockfile.Entry("a", "2.0.0", "maven", files("openapi.yaml", "3".repeat(64))));
        reread.write(file);

        Lockfile after = Lockfile.read(file);
        assertEquals("2.0.0", after.get("a").version());
        assertEquals("1.0.0", after.get("b").version());
        assertEquals("2".repeat(64), after.get("b").files().get("openapi.yaml"));
    }

    @Test
    void commentsAndBlankLinesAreIgnored(@TempDir Path dir) throws IOException {
        File file = dir.resolve("apionly.lock").toFile();
        Files.writeString(file.toPath(), """
            # a comment
            format 1

            target svc
            version 1.2.3
            channel file
            sha256 %s openapi.yaml
            """.formatted("e".repeat(64)));

        Lockfile.Entry entry = Lockfile.read(file).get("svc");
        assertEquals("1.2.3", entry.version());
        assertEquals("file", entry.channel());
        assertEquals(1, entry.files().size());
    }

    @Test
    void hashingTracksContent(@TempDir Path dir) throws IOException {
        File file = dir.resolve("doc.yaml").toFile();
        Files.writeString(file.toPath(), "openapi: 3.1.1\n");
        String before = Lockfile.sha256(file);
        assertEquals(64, before.length());

        Files.writeString(file.toPath(), "openapi: 3.1.1\n# edited\n");
        assertNotEquals(before, Lockfile.sha256(file));
    }
}
