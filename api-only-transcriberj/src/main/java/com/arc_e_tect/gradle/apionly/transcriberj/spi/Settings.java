package com.arc_e_tect.gradle.apionly.transcriberj.spi;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * How a project has asked for one contract's classes to be generated.
 *
 * @param contract               the name of the contract, as the project subscribes to it
 * @param basePackage            the package the core classes go in
 * @param generateDocs           whether descriptions come from the contract; when
 *                               {@code false}, every description is the empty string
 * @param descriptionPlaceholder the description of what the contract does not describe, when
 *                               {@code generateDocs} is {@code true}
 * @param recursionDepth         how many times a recursive reference is followed before
 *                               the rest is documented as a subsection
 * @param descriptionBundle      the base name of a {@link java.util.ResourceBundle} the
 *                               generated classes resolve their descriptions through, or
 *                               {@code null} when the project supplies none; what generation
 *                               produced is the fallback
 * @param invalidRequestStatus   the response status that means "the request is invalid", such
 *                               as {@code "400"}; invalid-request cases are derived only for an
 *                               operation that declares exactly this status
 * @param strictRequests         whether a request object that does not declare
 *                               {@code additionalProperties} forbids members it does not
 *                               declare, so that an unknown-member case is derived for it
 * @param validateFormats        the formats, such as {@code email}, an invalid-request case is
 *                               derived for; never {@code null}
 * @param emitterOptions         options for each emitter, by the emitter's {@link Emitter#id()}:
 *                               passed through as the project wrote them, never interpreted;
 *                               never {@code null}
 * @param derive                 the kinds of contract case derived, by their
 *                               {@link CaseKind#setting()} names, in canonical order; never
 *                               {@code null}
 */
public record Settings(
        String contract,
        String basePackage,
        boolean generateDocs,
        String descriptionPlaceholder,
        int recursionDepth,
        String descriptionBundle,
        String invalidRequestStatus,
        boolean strictRequests,
        List<String> validateFormats,
        Map<String, Map<String, String>> emitterOptions,
        List<String> derive) {

    /** The status that means "the request is invalid" when a project does not say otherwise. */
    public static final String DEFAULT_INVALID_REQUEST_STATUS = "400";

    /**
     * Settings as given, with the lists and maps copied so that they cannot change.
     *
     * @param contract               the name of the contract
     * @param basePackage            the package the core classes go in
     * @param generateDocs           whether descriptions come from the contract
     * @param descriptionPlaceholder the description of what the contract does not describe
     * @param recursionDepth         how many times a recursive reference is followed
     * @param descriptionBundle      the bundle descriptions resolve through, or {@code null}
     * @param invalidRequestStatus   the status meaning "the request is invalid"; {@code null}
     *                               for {@value #DEFAULT_INVALID_REQUEST_STATUS}
     * @param strictRequests         whether an undeclared {@code additionalProperties} forbids unknown members
     * @param validateFormats        the formats a case is derived for; {@code null} for none
     * @param emitterOptions         the options of each emitter; {@code null} for none
     * @param derive                 the kinds of case derived; {@code null} for every kind
     * @throws IllegalArgumentException when {@code derive} names something that is not a kind
     */
    public Settings {
        if (invalidRequestStatus == null) invalidRequestStatus = DEFAULT_INVALID_REQUEST_STATUS;
        validateFormats = validateFormats == null ? List.of() : List.copyOf(validateFormats);
        Map<String, Map<String, String>> options = new TreeMap<>();
        if (emitterOptions != null) {
            emitterOptions.forEach((id, values) -> options.put(id, Collections.unmodifiableMap(
                    new TreeMap<>(values == null ? Map.of() : values))));
        }
        emitterOptions = Collections.unmodifiableMap(options);
        if (derive == null) {
            derive = CaseKind.settings();
        } else {
            derive.forEach(CaseKind::ofSetting);
            List<String> given = derive;
            derive = CaseKind.settings().stream().filter(given::contains).toList();
        }
    }

    /**
     * Settings that derive every kind of contract case, as every contract was generated before
     * projects could choose.
     *
     * @param contract               the name of the contract
     * @param basePackage            the package the core classes go in
     * @param generateDocs           whether descriptions come from the contract
     * @param descriptionPlaceholder the description of what the contract does not describe
     * @param recursionDepth         how many times a recursive reference is followed
     * @param descriptionBundle      the bundle descriptions resolve through, or {@code null}
     * @param invalidRequestStatus   the status meaning "the request is invalid"; {@code null}
     *                               for {@value #DEFAULT_INVALID_REQUEST_STATUS}
     * @param strictRequests         whether an undeclared {@code additionalProperties} forbids unknown members
     * @param validateFormats        the formats a case is derived for; {@code null} for none
     * @param emitterOptions         the options of each emitter; {@code null} for none
     */
    public Settings(String contract, String basePackage, boolean generateDocs, String descriptionPlaceholder,
                    int recursionDepth, String descriptionBundle, String invalidRequestStatus,
                    boolean strictRequests, List<String> validateFormats,
                    Map<String, Map<String, String>> emitterOptions) {
        this(contract, basePackage, generateDocs, descriptionPlaceholder, recursionDepth, descriptionBundle,
                invalidRequestStatus, strictRequests, validateFormats, emitterOptions, null);
    }

    /**
     * Whether a kind of contract case is derived.
     *
     * @param kind the kind
     * @return whether {@code derive} names it
     */
    public boolean derives(CaseKind kind) {
        return derive.contains(kind.setting());
    }

    /**
     * Settings without a description bundle, as every contract was generated before
     * projects could supply one.
     *
     * @param contract               the name of the contract
     * @param basePackage            the package the core classes go in
     * @param generateDocs           whether descriptions come from the contract
     * @param descriptionPlaceholder the description of what the contract does not describe,
     *                               when {@code generateDocs} is true
     * @param recursionDepth         how many times a recursive reference is followed
     */
    public Settings(String contract, String basePackage, boolean generateDocs, String descriptionPlaceholder,
                    int recursionDepth) {
        this(contract, basePackage, generateDocs, descriptionPlaceholder, recursionDepth, null);
    }

    /**
     * Settings with the invalid-request defaults: status {@value #DEFAULT_INVALID_REQUEST_STATUS},
     * strict requests, no format validation, and no emitter options.
     *
     * @param contract               the name of the contract
     * @param basePackage            the package the core classes go in
     * @param generateDocs           whether descriptions come from the contract
     * @param descriptionPlaceholder the description of what the contract does not describe,
     *                               when {@code generateDocs} is true
     * @param recursionDepth         how many times a recursive reference is followed
     * @param descriptionBundle      the bundle descriptions resolve through, or {@code null}
     */
    public Settings(String contract, String basePackage, boolean generateDocs, String descriptionPlaceholder,
                    int recursionDepth, String descriptionBundle) {
        this(contract, basePackage, generateDocs, descriptionPlaceholder, recursionDepth, descriptionBundle,
                DEFAULT_INVALID_REQUEST_STATUS, true, List.of(), Map.of(), null);
    }

    /**
     * The options the project gave one emitter.
     *
     * @param emitterId the emitter's {@link Emitter#id()}
     * @return the options, by name; empty when the project gave it none
     */
    public Map<String, String> emitterOptions(String emitterId) {
        return emitterOptions.getOrDefault(emitterId, Map.of());
    }
}
