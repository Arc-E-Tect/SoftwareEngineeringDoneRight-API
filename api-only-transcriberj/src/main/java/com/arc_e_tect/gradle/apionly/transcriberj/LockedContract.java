package com.arc_e_tect.gradle.apionly.transcriberj;

import com.arc_e_tect.gradle.apionly.subscriber.Lockfile;
import org.gradle.api.GradleException;

import java.io.File;

/**
 * What the Subscriber's lockfile says about one contract's OpenAPI document.
 *
 * @param version the locked version
 * @param sha256  the locked SHA-256 of the document
 */
record LockedContract(String version, String sha256) {

    static final String DOCUMENT = "openapi.yaml";

    static LockedContract read(File lockfile, String contract) {
        Lockfile.Entry entry = lockfile.isFile() ? Lockfile.read(lockfile).get(contract) : null;
        if (entry == null || !entry.files().containsKey(DOCUMENT)) {
            throw new GradleException(lockfile + " has no " + DOCUMENT + " for contract " + contract
                    + ". Run fetchApiSpec first.");
        }
        return new LockedContract(entry.version(), entry.files().get(DOCUMENT));
    }

    /**
     * A contract's locked version, read from the lockfile's text, for what must be known before
     * any task runs: an archive's name.
     *
     * @param lockfile the lockfile's text; empty when there is none yet
     * @param contract the contract's name
     * @return the version, or {@code unspecified} when the lockfile does not name the contract
     */
    static String version(String lockfile, String contract) {
        boolean target = false;
        for (String line : lockfile.split("\\R")) {
            String[] parts = line.strip().split("\\s+", 2);
            if (parts.length < 2) continue;
            if (parts[0].equals("target")) {
                target = parts[1].equals(contract);
            } else if (target && parts[0].equals("version")) {
                return parts[1];
            }
        }
        return "unspecified";
    }
}
