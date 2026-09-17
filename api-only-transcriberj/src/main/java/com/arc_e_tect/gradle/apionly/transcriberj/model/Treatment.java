package com.arc_e_tect.gradle.apionly.transcriberj.model;

/** What code generation does with a classified construct. */
public enum Treatment {
    /** Generated in full. */
    REPRESENTED,
    /**
     * The class and its constants are generated; the emitter methods that cannot
     * represent the construct throw {@link UnsupportedOperationException} instead.
     */
    DEGRADED,
    /** No generation rule has been decided for it yet. */
    UNDECIDED
}
