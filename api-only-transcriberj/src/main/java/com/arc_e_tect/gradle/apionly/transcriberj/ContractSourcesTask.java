package com.arc_e_tect.gradle.apionly.transcriberj;

import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.work.DisableCachingByDefault;

import java.util.List;

/**
 * What every generation task of a contract derives its output from: the fetched documents, the
 * lockfile and the subscription's settings. The core's task and each emitter's derive the same
 * model from them, so each can run on its own.
 *
 * <p>The inputs are the documents' content and the lockfile, not only their paths: a new
 * contract version regenerates the output however alike the two documents' files look.
 */
@DisableCachingByDefault(because = "An abstract base; each generation task declares its own caching")
public abstract class ContractSourcesTask extends DefaultTask {

    /** Creates the task, with the invalid-request settings at their defaults. */
    protected ContractSourcesTask() {
        getInvalidRequestStatus().convention(Settings.DEFAULT_INVALID_REQUEST_STATUS);
        getStrictRequests().convention(true);
        getValidateFormats().convention(List.of());
    }

    /**
     * The fetched contract document.
     *
     * @return the document
     */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getContract();

    /**
     * The contract's AsyncAPI document, when it has one: empty otherwise.
     *
     * @return the document, or nothing
     */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getAsyncContract();

    /**
     * The base name of the bundle descriptions resolve through, or absent when the project
     * supplies none.
     *
     * @return the bundle's base name
     */
    @Input
    @Optional
    public abstract Property<String> getDescriptionBundle();

    /**
     * The Subscriber's lockfile, which says which version was fetched.
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
     * The package the core classes are generated into.
     *
     * @return the package
     */
    @Input
    @Optional
    public abstract Property<String> getBasePackage();

    /**
     * How many times a recursive reference is followed.
     *
     * @return the depth
     */
    @Input
    public abstract Property<Integer> getRecursionDepth();

    /**
     * Whether descriptions come from the contract.
     *
     * @return the setting
     */
    @Input
    public abstract Property<Boolean> getGenerateDocs();

    /**
     * The description of what the contract does not describe.
     *
     * @return the placeholder
     */
    @Input
    public abstract Property<String> getDescriptionPlaceholder();

    /**
     * The status that means "the request is invalid".
     *
     * @return the status
     */
    @Input
    public abstract Property<String> getInvalidRequestStatus();

    /**
     * Whether an undeclared {@code additionalProperties} forbids unknown members.
     *
     * @return the setting
     */
    @Input
    public abstract Property<Boolean> getStrictRequests();

    /**
     * The formats an invalid-request case is derived for.
     *
     * @return the format names
     */
    @Input
    public abstract ListProperty<String> getValidateFormats();

    /**
     * The locked version and hash, after checking the subscription names a package.
     *
     * @return the locked contract
     */
    LockedContract locked() {
        if (!getBasePackage().isPresent()) {
            throw new GradleException("apiOnlyTranscriberJ: subscription('" + getContractName().get()
                    + "') needs a basePackage.");
        }
        return LockedContract.read(getLockfile().get().getAsFile(), getContractName().get());
    }

    /**
     * Hands this task's inputs to a worker.
     *
     * @param parameters the worker's parameters
     * @param locked     the locked contract
     */
    void fill(ContractParameters parameters, LockedContract locked) {
        parameters.getContract().set(getContract());
        parameters.getAsyncContract().setFrom(getAsyncContract());
        parameters.getContractVersion().set(locked.version());
        parameters.getContractSha256().set(locked.sha256());
        parameters.getContractName().set(getContractName());
        parameters.getBasePackage().set(getBasePackage());
        parameters.getRecursionDepth().set(getRecursionDepth());
        parameters.getGenerateDocs().set(getGenerateDocs());
        parameters.getDescriptionPlaceholder().set(getDescriptionPlaceholder());
        parameters.getDescriptionBundle().set(getDescriptionBundle());
        parameters.getInvalidRequestStatus().set(getInvalidRequestStatus());
        parameters.getStrictRequests().set(getStrictRequests());
        parameters.getValidateFormats().set(getValidateFormats());
    }
}
