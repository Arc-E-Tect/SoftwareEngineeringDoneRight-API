package com.arc_e_tect.gradle.apionly.subscriber;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fails when a fetched contract no longer matches what was locked.
 *
 * This is the verbatim guarantee, and the reason the lockfile is committed. It
 * catches both halves of the problem: a document edited by hand after it was
 * fetched, and a published version that changed underneath a tag that was
 * supposed to be immutable.
 */
@DisableCachingByDefault(
    because = "Verification is the point. A cached 'it matched last time' is precisely the answer "
            + "this task must never give: the file on disk may have been edited since, and skipping "
            + "the check because the inputs look familiar would hide exactly the drift it exists to find."
)
public abstract class VerifyApiSpecTask extends DefaultTask {

    /** Creates the task. Gradle instantiates this when a subscription is declared. */
    public VerifyApiSpecTask() {
        // Nothing to do: every input is configured by the plugin.
    }

    /**
     * The contract being checked.
     *
     * @return the target name, used to find its entry in the lockfile
     */
    @Input
    public abstract Property<String> getTarget();

    /**
     * Where the fetched documents are, and are checked.
     *
     * <p>Deliberately {@code @Internal} rather than {@code @InputDirectory}. This
     * task has no outputs and is not cacheable, so it runs every time regardless
     * and there is no up-to-date check for a declared input to inform. Declaring
     * it would only add Gradle's own existence check, which fires before this task
     * runs and reports a missing <em>property</em> where the reader needs to be
     * told that nothing has been fetched yet.</p>
     *
     * @return the directory holding the fetched documents
     */
    @Internal
    public abstract DirectoryProperty getInto();

    /**
     * The lockfile the fetched documents are checked against.
     *
     * <p>{@code @Internal} for the same reason as {@link #getInto()}. A missing
     * lockfile is an ordinary first-run state, not a misconfiguration: a project
     * that applies the plugin and runs {@code check} before it has ever fetched
     * anything has no lockfile yet.</p>
     *
     * @return the lockfile location
     */
    @Internal
    public abstract RegularFileProperty getLockfile();

    /**
     * Fails when a fetched contract no longer matches what was locked.
     *
     * <p>Every problem found is reported at once rather than the first, since a
     * contract that has drifted has usually drifted in more than one file.</p>
     *
     * @throws org.gradle.api.GradleException if nothing has been fetched, if
     *         there is no lockfile or no entry for this target, or if any
     *         document is missing or no longer hashes to what was locked
     */
    @TaskAction
    public void verify() {
        String target = getTarget().get();
        File lockfile = getLockfile().get().getAsFile();
        File destination = getInto().get().getAsFile();

        if (!destination.isDirectory()) {
            throw new GradleException(
                "nothing has been fetched for '" + target + "' yet, so there is nothing to verify.\n\n"
                + "Run fetchApiSpec first. It resolves the subscribed contract, unpacks it into "
                + destination.getName() + ", and records what it unpacked in apionly.lock.");
        }

        if (!lockfile.isFile()) {
            throw new GradleException(
                "there is no " + lockfile.getName() + " in this project, so there is nothing to verify "
                + "'" + target + "' against.\n\nRun fetchApiSpec to create one, and commit it: it is "
                + "what records which contract this project actually builds against.");
        }

        Lockfile lock = Lockfile.read(lockfile);
        Lockfile.Entry entry = lock.get(target);
        if (entry == null) {
            throw new GradleException(
                "apionly.lock has no entry for '" + target + "'. Run fetchApiSpec to create one, "
                + "and commit the result.");
        }

        List<String> problems = new ArrayList<>();

        for (Map.Entry<String, String> locked : entry.files().entrySet()) {
            File file = new File(destination, locked.getKey());
            if (!file.isFile()) {
                problems.add(locked.getKey() + " is missing");
                continue;
            }
            String actual = Lockfile.sha256(file);
            if (!actual.equals(locked.getValue())) {
                problems.add(locked.getKey() + " has changed since it was locked"
                    + "\n      locked:  " + locked.getValue()
                    + "\n      on disk: " + actual);
            }
        }

        // The manifest travels with the documents, so a mismatch against it means
        // the archive itself was tampered with rather than a file edited locally.
        File manifestFile = new File(destination, "manifest.json");
        if (manifestFile.isFile()) {
            Manifest manifest = Manifest.read(manifestFile);
            if (manifest.version() != null && !manifest.version().equals(entry.version())) {
                problems.add("the fetched archive declares version " + manifest.version()
                    + " but apionly.lock records " + entry.version());
            }
        }

        if (!problems.isEmpty()) {
            throw new GradleException(
                "the contract for '" + target + "' has drifted from apionly.lock:\n  - "
                + String.join("\n  - ", problems)
                + "\n\nThese documents are fetched, not authored here. Change the contract in the "
                + "specification library and subscribe to the new version, or re-run fetchApiSpec "
                + "if the change was intended.");
        }

        getLogger().lifecycle("{} {} matches apionly.lock ({} file(s))",
            target, entry.version(), entry.files().size());
    }
}
