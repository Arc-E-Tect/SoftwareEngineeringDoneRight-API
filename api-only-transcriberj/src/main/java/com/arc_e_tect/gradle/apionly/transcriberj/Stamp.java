package com.arc_e_tect.gradle.apionly.transcriberj;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * What an emitter's output was generated from, as {@code verifyContractSources<Contract>} reads
 * it: the emitter's id and the contract's locked version and hash.
 *
 * @param emitter the emitter's id
 * @param version the contract version
 * @param sha256  the document hash
 */
record Stamp(String emitter, String version, String sha256) {

    /**
     * The stamp's text.
     *
     * @param emitter the emitter's id
     * @param version the contract version
     * @param sha256  the document hash
     * @return the text
     */
    static String render(String emitter, String version, String sha256) {
        return "emitter=" + emitter + "\ncontractVersion=" + version + "\ncontractSha256=" + sha256 + "\n";
    }

    /**
     * Reads a stamp.
     *
     * @param text the stamp's text
     * @return the stamp
     */
    static Stamp parse(String text) {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(text));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Stamp(properties.getProperty("emitter"), properties.getProperty("contractVersion"),
                properties.getProperty("contractSha256"));
    }
}
