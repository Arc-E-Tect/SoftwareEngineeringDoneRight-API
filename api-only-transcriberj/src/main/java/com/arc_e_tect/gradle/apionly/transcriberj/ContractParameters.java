package com.arc_e_tect.gradle.apionly.transcriberj;

import com.arc_e_tect.gradle.apionly.transcriberj.core.Generation;
import com.arc_e_tect.gradle.apionly.transcriberj.core.GenerationException;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.workers.WorkParameters;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** What a generation worker derives the contract's model from. */
public interface ContractParameters extends WorkParameters {

    /**
     * The fetched contract document.
     *
     * @return the document
     */
    RegularFileProperty getContract();

    /**
     * The contract's AsyncAPI document, or nothing when it has none.
     *
     * @return the document
     */
    ConfigurableFileCollection getAsyncContract();

    /**
     * The locked version.
     *
     * @return the version
     */
    Property<String> getContractVersion();

    /**
     * The locked hash.
     *
     * @return the hash
     */
    Property<String> getContractSha256();

    /**
     * The contract's name.
     *
     * @return the name
     */
    Property<String> getContractName();

    /**
     * The base package.
     *
     * @return the package
     */
    Property<String> getBasePackage();

    /**
     * The recursion depth.
     *
     * @return the depth
     */
    Property<Integer> getRecursionDepth();

    /**
     * Whether descriptions come from the contract.
     *
     * @return the setting
     */
    Property<Boolean> getGenerateDocs();

    /**
     * The description placeholder.
     *
     * @return the placeholder
     */
    Property<String> getDescriptionPlaceholder();

    /**
     * The base name of the bundle descriptions resolve through, or absent when the project
     * supplies none.
     *
     * @return the bundle's base name
     */
    Property<String> getDescriptionBundle();

    /**
     * The status that means "the request is invalid".
     *
     * @return the status
     */
    Property<String> getInvalidRequestStatus();

    /**
     * Whether an undeclared {@code additionalProperties} forbids unknown members.
     *
     * @return the setting
     */
    Property<Boolean> getStrictRequests();

    /**
     * The formats an invalid-request case is derived for.
     *
     * @return the format names
     */
    ListProperty<String> getValidateFormats();

    /**
     * The kinds of contract case derived.
     *
     * @return the kinds, by their setting names
     */
    ListProperty<String> getDerive();

    /**
     * The settings these parameters describe, with the options of the emitters given.
     *
     * @param emitterOptions each emitter's options, by id
     * @return the settings
     */
    default Settings settings(Map<String, Map<String, String>> emitterOptions) {
        return new Settings(getContractName().get(), getBasePackage().get(), getGenerateDocs().get(),
                getDescriptionPlaceholder().get(), getRecursionDepth().get(), getDescriptionBundle().getOrNull(),
                getInvalidRequestStatus().getOrNull(), getStrictRequests().getOrElse(true),
                getValidateFormats().getOrElse(List.of()), emitterOptions, getDerive().getOrNull());
    }

    /**
     * Derives the contract's model, class names and cases.
     *
     * @param settings the settings
     * @return the derivation
     */
    default Generation.Derivation derive(Settings settings) {
        Path async = getAsyncContract().getFiles().stream().filter(java.io.File::isFile)
                .findFirst().map(java.io.File::toPath).orElse(null);
        try {
            return Generation.derive(getContract().get().getAsFile().toPath(), async, getContractVersion().get(),
                    getContractSha256().get(), settings);
        } catch (GenerationException e) {
            throw new GradleException(e.getMessage(), e);
        }
    }

    /**
     * Writes a UTF-8 text file, creating its directory.
     *
     * @param file    the file
     * @param content the text
     */
    static void write(Path file, String content) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
