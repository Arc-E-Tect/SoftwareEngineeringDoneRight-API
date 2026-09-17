/**
 * The emitter interface: how a library adds a dialect to the classes the
 * API-Only TranscriberJ generates.
 *
 * <p>An emitter is a library that implements {@link com.arc_e_tect.gradle.apionly.transcriberj.spi.Emitter}
 * and lists its implementation in {@code META-INF/services/com.arc_e_tect.gradle.apionly.transcriberj.spi.Emitter}.
 * A project that wants it adds the library to its {@code transcriberjEmitters}
 * configuration; the plugin loads it in a class loader of its own and runs it over
 * the contract model, after the built-in core emitter.
 *
 * <p>The core emitter decides every class name once, in {@link com.arc_e_tect.gradle.apionly.transcriberj.spi.ClassNames},
 * so that a dialect's companion class and the core class it belongs to always agree.
 */
package com.arc_e_tect.gradle.apionly.transcriberj.spi;
