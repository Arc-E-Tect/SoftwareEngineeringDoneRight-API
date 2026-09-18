package com.arc_e_tect.gradle.apionly.subscriber;

import com.arc_e_tect.gradle.dslupdater.DslExtensionSchema;
import com.arc_e_tect.gradle.dslupdater.DslPropertySpec;

import java.util.List;

/**
 * The {@code apiOnlySubscriber {}} block's DSL property schema for
 * {@code updateApiOnlySubscriberDSL}.
 *
 * <p>The schema includes only values with real defaults in the extension model.
 * Subscription declarations are intentionally not generated: they are project-specific,
 * and the updater does not invent contract targets or versions.</p>
 */
final class ApiOnlySubscriberDslSchema {

    private static final String CHANNEL_STUB = String.join("\n",
            "type = 'maven'",
            "extension = 'tgz'",
            "// groupId = 'com.example.contracts'",
            "// directory = \"$rootDir/build/published\"");

    static final DslExtensionSchema SCHEMA = new DslExtensionSchema(
            ApiOnlySubscriberExtension.NAME,
            List.of(
                    DslPropertySpec.scalar("lockfile", "layout.projectDirectory.file('apionly.lock')",
                            "Where resolved contract versions and file hashes are recorded."),
                    DslPropertySpec.scalar("apiContractVersion", "findProperty('apiContractVersion')",
                            "The version of the contract this project implements, unless its subscription sets its own."),
                    DslPropertySpec.container("channel",
                            "The channel used to resolve every subscribed contract.",
                            CHANNEL_STUB)
            ));

    private ApiOnlySubscriberDslSchema() {
    }
}
