package com.arc_e_tect.gradle.apionly.transcriberj;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fails when the generated class tree was generated from another contract than the
 * one the lockfile names (D17): "these tests test the current contract", checked.
 */
@DisableCachingByDefault(because = "It only reads two small files")
public abstract class VerifyContractSourcesTask extends DefaultTask {

    private static final Pattern VERSION = Pattern.compile("CONTRACT_VERSION = \"([^\"]*)\";");
    private static final Pattern SHA256 = Pattern.compile("CONTRACT_SHA256 = \"([^\"]*)\";");

    /** Creates the task. */
    public VerifyContractSourcesTask() {
    }

    /**
     * The Subscriber's lockfile.
     *
     * @return the lockfile
     */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getLockfile();

    /**
     * The contract's name.
     *
     * @return the name
     */
    @Input
    public abstract Property<String> getContractName();

    /**
     * The package the core classes are in.
     *
     * @return the package
     */
    @Input
    public abstract Property<String> getBasePackage();

    /**
     * Where the sources were generated.
     *
     * @return the directory
     */
    @Internal
    public abstract DirectoryProperty getSourcesDirectory();

    /** Compares the generated manifest with the lockfile. */
    @TaskAction
    public void verify() {
        String contract = getContractName().get();
        Path manifest = getSourcesDirectory().get().getAsFile().toPath()
                .resolve(getBasePackage().get().replace('.', '/')).resolve("ContractManifest.java");
        if (!Files.isRegularFile(manifest)) {
            throw new GradleException("No classes have been generated for contract " + contract + " at "
                    + manifest.getParent() + ". Run generateContractSources" + ApiOnlyTranscriberJPlugin.suffix(contract)
                    + ".");
        }
        LockedContract locked = LockedContract.read(getLockfile().get().getAsFile(), contract);
        String text;
        try {
            text = Files.readString(manifest);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String version = find(VERSION, text);
        String sha256 = find(SHA256, text);
        if (!locked.version().equals(version) || !locked.sha256().equals(sha256)) {
            throw new GradleException("The classes generated for contract " + contract + " at " + manifest.getParent()
                    + " were generated from version " + version + " (" + sha256 + "), but the lockfile names version "
                    + locked.version() + " (" + locked.sha256() + "). Regenerate them with generateContractSources"
                    + ApiOnlyTranscriberJPlugin.suffix(contract) + ".");
        }
    }

    private static String find(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
}
