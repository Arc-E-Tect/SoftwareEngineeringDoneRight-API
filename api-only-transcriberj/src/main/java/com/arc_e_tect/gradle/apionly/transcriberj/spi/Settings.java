package com.arc_e_tect.gradle.apionly.transcriberj.spi;

/**
 * How a project has asked for one contract's classes to be generated.
 *
 * @param contract               the name of the contract, as the project subscribes to it
 * @param basePackage            the package the core classes go in
 * @param generateDocs           whether descriptions come from the contract; when
 *                               {@code false}, every description is {@code descriptionPlaceholder}
 * @param descriptionPlaceholder the description used when {@code generateDocs} is {@code false}
 * @param recursionDepth         how many times a recursive reference is followed before
 *                               the rest is documented as a subsection
 * @param descriptionBundle      the base name of a {@link java.util.ResourceBundle} the
 *                               generated classes resolve their descriptions through, or
 *                               {@code null} when the project supplies none; what generation
 *                               produced is the fallback
 */
public record Settings(
        String contract,
        String basePackage,
        boolean generateDocs,
        String descriptionPlaceholder,
        int recursionDepth,
        String descriptionBundle) {

    /**
     * Settings without a description bundle, as every contract was generated before
     * projects could supply one.
     *
     * @param contract               the name of the contract
     * @param basePackage            the package the core classes go in
     * @param generateDocs           whether descriptions come from the contract
     * @param descriptionPlaceholder the description used when {@code generateDocs} is false
     * @param recursionDepth         how many times a recursive reference is followed
     */
    public Settings(String contract, String basePackage, boolean generateDocs, String descriptionPlaceholder,
                    int recursionDepth) {
        this(contract, basePackage, generateDocs, descriptionPlaceholder, recursionDepth, null);
    }
}
