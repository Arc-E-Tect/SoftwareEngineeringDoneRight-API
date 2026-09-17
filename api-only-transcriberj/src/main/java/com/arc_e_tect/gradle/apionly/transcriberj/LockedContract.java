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
}
