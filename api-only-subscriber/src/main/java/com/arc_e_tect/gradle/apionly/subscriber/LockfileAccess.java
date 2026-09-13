package com.arc_e_tect.gradle.apionly.subscriber;

import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

/**
 * Serialises access to one project's {@code apionly.lock}.
 *
 * <p>Every fetch and every verification in a project reads or writes the same
 * lockfile, and Gradle may run a project's tasks in parallel, as it does with the
 * configuration cache. Two fetches recording their entries at the same moment
 * would each write back what they read, and one entry would be lost. The plugin
 * registers one of these per project, permitting a single usage at a time, and
 * every fetch and verify task in that project uses it.</p>
 */
public abstract class LockfileAccess implements BuildService<BuildServiceParameters.None> {

    /** Creates the service. Gradle instantiates it when a task that uses it runs. */
    public LockfileAccess() {
        // Holds no state: it exists so that one task at a time uses the lockfile.
    }
}
