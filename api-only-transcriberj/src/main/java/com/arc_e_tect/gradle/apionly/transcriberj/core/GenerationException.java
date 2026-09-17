package com.arc_e_tect.gradle.apionly.transcriberj.core;

/** A contract no classes can be generated from, with every reason why. */
public class GenerationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what is wrong, and what to do about it
     */
    public GenerationException(String message) {
        super(message);
    }
}
