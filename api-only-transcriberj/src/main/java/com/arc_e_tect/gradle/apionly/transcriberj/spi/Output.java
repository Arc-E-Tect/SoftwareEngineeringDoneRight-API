package com.arc_e_tect.gradle.apionly.transcriberj.spi;

/**
 * What an emitter writes, in the mode its options select: the plugin places each kind where it
 * belongs, and refuses a configuration that would put it anywhere else.
 */
public enum Output {

    /** Java sources, written with {@link EmitterContext#writeJava}, compiled in source sets. */
    JAVA,

    /** Classpath resources, written with {@link EmitterContext#writeResource}. */
    RESOURCES,

    /**
     * Files that belong on no classpath -- mapping files, an archive's contents -- written with
     * {@link EmitterContext#writeFile(String, byte[])}, and packaged rather than compiled.
     */
    FILES
}
