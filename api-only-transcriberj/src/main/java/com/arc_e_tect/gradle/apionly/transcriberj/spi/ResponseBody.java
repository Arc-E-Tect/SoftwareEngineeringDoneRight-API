package com.arc_e_tect.gradle.apionly.transcriberj.spi;

/**
 * The body of a response an operation declares, as an emitter is given it: the generated class
 * its contract cases name, and the valid bodies that class's {@code requiredBody()} and
 * {@code fullBody()} return. What an emitter that writes a response as a file, rather than as
 * Java calling those methods, answers with.
 *
 * @param bodyClass the simple name of the generated class of the body, as a case's
 *                  {@code responseBodyClass} names it
 * @param required  what {@code requiredBody()} returns
 * @param full      what {@code fullBody()} returns
 */
public record ResponseBody(String bodyClass, Body required, Body full) {

    /**
     * One valid body: its text, or, when the core cannot build one, where and why -- what the
     * generated method throws instead.
     *
     * @param text     the body as compact JSON followed by a newline, exactly as the generated
     *                 method returns it; {@code null} when there is none
     * @param location the JSON pointer of the construct that prevents a body, or {@code null}
     *                 when there is a body
     * @param reason   why there is no body, or {@code null} when there is one
     */
    public record Body(String text, String location, String reason) {

        /**
         * Whether the core could build this body.
         *
         * @return whether there is a text
         */
        public boolean available() {
            return text != null;
        }
    }
}
