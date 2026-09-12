package com.arc_e_tect.gradle.apionly.subscriber;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ArchiveOperations;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.file.ConfigurableFileCollection;

import javax.inject.Inject;
import java.io.File;
import java.util.Map;
import java.util.TreeMap;

/**
 * Unpacks a subscribed contract into the build, and records what it unpacked.
 *
 * Declared inputs and outputs, so it is up-to-date-checked and cacheable, and so
 * the build depends on it rather than relying on somebody remembering to run a
 * script first.
 */
@CacheableTask
public abstract class FetchApiSpecTask extends DefaultTask {

    /** Creates the task. Gradle instantiates this when a subscription is declared. */
    public FetchApiSpecTask() {
        // Nothing to do: every input is configured by the plugin.
    }

    /**
     * Gradle's archive operations, used to unpack the fetched {@code .tgz}.
     *
     * @return the injected archive operations
     */
    @Inject
    protected abstract ArchiveOperations getArchives();

    /**
     * Gradle's filesystem operations, used to clear and repopulate the
     * destination.
     *
     * @return the injected filesystem operations
     */
    @Inject
    protected abstract FileSystemOperations getFiles();

    /**
     * The resolved archive.
     *
     * <p>For the {@code maven} channel this is a resolved dependency, so Gradle
     * has already downloaded, cached and verified the artifact itself before this
     * task runs. For the {@code file} channel it is a path on disk.</p>
     *
     * @return the archive to unpack, as a single-file collection
     */
    @InputFiles
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract ConfigurableFileCollection getArchive();

    /**
     * The contract being fetched.
     *
     * <p>Used to key the lockfile entry and to name the target in any failure.</p>
     *
     * @return the target name
     */
    @Input
    public abstract Property<String> getTarget();

    /**
     * The version being fetched.
     *
     * <p>Checked against the version the archive's own manifest declares, so an
     * archive published under the wrong coordinates is refused rather than
     * unpacked.</p>
     *
     * @return the version to record in the lockfile
     */
    @Input
    public abstract Property<String> getVersion();

    /**
     * Which channel the archive came from.
     *
     * <p>Recorded in the lockfile so that a reader can tell where a contract was
     * resolved from without re-running the build.</p>
     *
     * @return the channel name, {@code "maven"} or {@code "file"}
     */
    @Input
    public abstract Property<String> getChannel();

    /**
     * Where the documents are unpacked to.
     *
     * <p>Declared as an output, so Gradle treats a modified document as making
     * this task out of date and refetches it.</p>
     *
     * @return the destination directory
     */
    @OutputDirectory
    public abstract DirectoryProperty getInto();

    /**
     * The lockfile this task records what it unpacked in.
     *
     * @return the lockfile location
     */
    @OutputFile
    public abstract RegularFileProperty getLockfile();

    /**
     * Unpacks the archive, verifies it against its own manifest, and records the
     * result in the lockfile.
     *
     * <p>Three things are refused rather than unpacked: an archive whose manifest
     * names a different version, an archive missing a document its manifest
     * declares, and an archive whose contents do not hash to what its manifest
     * says. A fourth is refused at the lockfile: a version already locked, coming
     * back with different bytes — a released version rebuilt, or a tag moved.</p>
     *
     * @throws org.gradle.api.GradleException if any of those checks fail
     */
    @TaskAction
    public void fetch() {
        File archive = getArchive().getSingleFile();
        File destination = getInto().get().getAsFile();

        getFiles().delete(spec -> spec.delete(destination));
        getFiles().copy(spec -> {
            spec.from(getArchives().tarTree(getArchives().gzip(archive)));
            spec.into(destination);
        });

        Manifest manifest = Manifest.read(new File(destination, "manifest.json"));

        String declaredVersion = getVersion().get();
        if (manifest.version() != null && !manifest.version().equals(declaredVersion)) {
            throw new GradleException(
                "the archive for '" + getTarget().get() + "' declares version " + manifest.version()
                + " but was resolved as " + declaredVersion
                + ". A published version was rebuilt, or a tag was moved.");
        }

        // Verify on the way in, not only in verifyApiSpec: an archive whose
        // contents do not match its own manifest should never reach a build.
        Map<String, String> hashes = new TreeMap<>();
        for (Map.Entry<String, String> declared : manifest.files().entrySet()) {
            File file = new File(destination, declared.getKey());
            if (!file.isFile()) {
                throw new GradleException(
                    "the archive for '" + getTarget().get() + "' declares " + declared.getKey()
                    + " but does not contain it");
            }
            String actual = Lockfile.sha256(file);
            if (!actual.equals(declared.getValue())) {
                throw new GradleException(
                    declared.getKey() + " in the archive for '" + getTarget().get()
                    + "' does not match the hash its own manifest declares");
            }
            hashes.put(declared.getKey(), actual);
        }

        File lockfile = getLockfile().get().getAsFile();
        Lockfile lock = Lockfile.read(lockfile);
        Lockfile.Entry previous = lock.get(getTarget().get());

        // A lock that is rewritten on every fetch locks nothing. When the same
        // version comes back with different bytes, that is a published version
        // having changed underneath a coordinate that was supposed to be
        // immutable -- a moved tag, or a rebuild republished over itself. Record
        // it silently and the build would go on claiming to honour a contract it
        // no longer has.
        if (previous != null && previous.version().equals(declaredVersion)
            && !previous.files().equals(hashes)) {

            StringBuilder detail = new StringBuilder();
            for (Map.Entry<String, String> entry : hashes.entrySet()) {
                String locked = previous.files().get(entry.getKey());
                if (locked == null) {
                    detail.append("\n  - ").append(entry.getKey()).append(" is new in this version");
                } else if (!locked.equals(entry.getValue())) {
                    detail.append("\n  - ").append(entry.getKey()).append(" differs")
                          .append("\n      locked:    ").append(locked)
                          .append("\n      published: ").append(entry.getValue());
                }
            }
            for (String name : previous.files().keySet()) {
                if (!hashes.containsKey(name)) {
                    detail.append("\n  - ").append(name).append(" is no longer published");
                }
            }

            throw new GradleException(
                "the published contract for '" + getTarget().get() + "' " + declaredVersion
                + " is not the one recorded in apionly.lock:" + detail
                + "\n\nA released version was rebuilt, or a tag was moved. Find out which before "
                + "accepting this: subscribe to a new version if the change was intended, or delete "
                + "the entry from apionly.lock to re-lock deliberately.");
        }

        lock.put(new Lockfile.Entry(getTarget().get(), declaredVersion, getChannel().get(), hashes));
        lock.write(lockfile);

        if (previous == null) {
            getLogger().lifecycle("Subscribed to {} {}", getTarget().get(), declaredVersion);
        } else if (!previous.version().equals(declaredVersion)) {
            getLogger().lifecycle("Updated {} from {} to {}",
                getTarget().get(), previous.version(), declaredVersion);
        }
    }
}
