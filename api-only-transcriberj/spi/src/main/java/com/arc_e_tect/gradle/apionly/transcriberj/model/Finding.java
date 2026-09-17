package com.arc_e_tect.gradle.apionly.transcriberj.model;

/**
 * One occurrence of a classified construct.
 *
 * @param location  the JSON pointer, into the bundled contract, of the schema or
 *                  object the construct occurs in
 * @param construct what occurs there
 * @param treatment how generated code treats it
 * @param detail    what exactly was found, for a human reading the report
 */
public record Finding(String location, Construct construct, Treatment treatment, String detail) {

    /**
     * What a specification author can do so the construct is represented in full.
     *
     * @return the remedy, or {@code null} when there is nothing to remedy
     */
    public String remedy() {
        return construct.remedy();
    }
}
