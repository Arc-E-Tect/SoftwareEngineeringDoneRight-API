package com.arc_e_tect.gradle.apionly.transcriberj.spi;

/**
 * A named example a response declares, as an emitter is given it: what a double answers with
 * when it answers with that outcome. The core has checked it against the response's schema.
 *
 * @param name        the example's name, as the media type's {@code examples} gives it
 * @param contentType the content type it is declared under
 * @param text        its value as JSON text, with a final newline; a reference to
 *                    {@code #/components/examples} resolved
 * @param location    the JSON pointer of the example where the media type declares it
 * @param isDefault   whether a double answers with it when no outcome is chosen: the one the media
 *                    type's {@code x-transcriberj-examples} names as {@code default}, else the first
 */
public record ResponseExample(String name, String contentType, String text, String location, boolean isDefault) {
}
