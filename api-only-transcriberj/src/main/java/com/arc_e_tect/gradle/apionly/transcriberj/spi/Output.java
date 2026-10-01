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
     *
     * <p>This declares the one currently supported external product: the TranscriberJ owns the
     * reproducible ZIP, its provenance, and its {@code apionly-files} variant. An emitter must
     * not create Gradle components or configurations. A publication descriptor is warranted only
     * when an emitter needs a product this one archive cannot express, such as multiple archives,
     * a classifier, a non-ZIP artifact, extra published metadata, or a consumable JVM variant.</p>
     */
    FILES
}
