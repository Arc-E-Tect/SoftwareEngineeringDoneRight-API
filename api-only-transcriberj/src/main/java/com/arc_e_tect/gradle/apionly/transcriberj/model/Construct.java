package com.arc_e_tect.gradle.apionly.transcriberj.model;

/**
 * A construct the model classifies wherever it occurs, so that its treatment in
 * generated code is reported rather than discovered.
 */
public enum Construct {
    /** {@code oneOf} whose branches are all references to components. */
    ONE_OF_REF_BRANCHES(Treatment.REPRESENTED, null),
    /** {@code oneOf} with at least one anonymous, inline branch. */
    ONE_OF_INLINE_BRANCHES(Treatment.DEGRADED, Construct.NAME_THE_BRANCHES),
    /** {@code anyOf} whose branches are all references to components. */
    ANY_OF_REF_BRANCHES(Treatment.REPRESENTED, null),
    /** {@code anyOf} with at least one anonymous, inline branch. */
    ANY_OF_INLINE_BRANCHES(Treatment.DEGRADED, Construct.NAME_THE_BRANCHES),
    /** {@code discriminator}. */
    DISCRIMINATOR(Treatment.REPRESENTED, null),
    /** {@code additionalProperties}, in any of its forms. */
    ADDITIONAL_PROPERTIES(Treatment.REPRESENTED, null),
    /** {@code patternProperties}. */
    PATTERN_PROPERTIES(Treatment.REPRESENTED, null),
    /** A {@code $ref} that closes a cycle of components. */
    RECURSIVE_REF(Treatment.REPRESENTED, null),
    /** {@code type} naming more than one type, such as {@code [string, 'null']}. */
    MULTIPLE_TYPES(Treatment.UNDECIDED, null),
    /** A schema written as the literal {@code true} or {@code false}. */
    BOOLEAN_SCHEMA(Treatment.UNDECIDED, null),
    /** A schema keyword the model does not type, kept as a raw value. */
    UNMODELLED_KEYWORD(Treatment.UNDECIDED, null),
    /**
     * A component of a type the model does not type -- anything but schemas,
     * responses, parameters and request bodies -- kept as a raw value.
     */
    UNMODELLED_COMPONENT_TYPE(Treatment.UNDECIDED, null),

    /**
     * One fragment bundled into the contract's two documents as different bytes, so
     * that the class generated from it can only match one of them.
     */
    FRAGMENT_BUNDLED_DIFFERENTLY(Treatment.UNDECIDED, null);

    private static final String NAME_THE_BRANCHES =
            "name the branches in the specification so that each becomes a component";

    private final Treatment treatment;
    private final String remedy;

    Construct(Treatment treatment, String remedy) {
        this.treatment = treatment;
        this.remedy = remedy;
    }

    /**
     * How generated code treats this construct.
     *
     * @return the treatment
     */
    public Treatment treatment() {
        return treatment;
    }

    /**
     * What a specification author can do so the construct is represented in full.
     *
     * @return the remedy, or {@code null} when there is nothing to remedy
     */
    public String remedy() {
        return remedy;
    }
}
