package com.arc_e_tect.gradle.apionly.transcriberj.model;

/**
 * A document that is not a bundled OpenAPI 3 contract the model can be built from.
 *
 * <p>Distinct from a construct the model classifies: those degrade, per method, in
 * what is generated from them; this stops parsing, because there is nothing sound
 * to generate from at all.
 */
public class ContractModelException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what is wrong, and where
     * @param cause   the underlying failure, or {@code null}
     */
    public ContractModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
