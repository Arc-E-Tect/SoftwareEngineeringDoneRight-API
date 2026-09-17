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
 */
public record Settings(
        String contract,
        String basePackage,
        boolean generateDocs,
        String descriptionPlaceholder,
        int recursionDepth) {
}
