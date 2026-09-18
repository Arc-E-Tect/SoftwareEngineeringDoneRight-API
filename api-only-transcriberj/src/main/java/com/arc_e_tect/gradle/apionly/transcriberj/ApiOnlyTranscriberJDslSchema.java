package com.arc_e_tect.gradle.apionly.transcriberj;

import com.arc_e_tect.gradle.dslupdater.DslExtensionSchema;
import com.arc_e_tect.gradle.dslupdater.DslPropertySpec;

import java.util.List;

/**
 * The {@code apiOnlyTranscriberJ {}} block's DSL property schema for
 * {@code updateApiOnlyTranscriberJDSL}.
 *
 * <p>The only top-level property with a default is {@code strictDependencies}. Every
 * other setting belongs to one contract's subscription, which names that contract and
 * requires a {@code basePackage}, so the updater does not invent one: a generated block
 * carries an example of a subscription, every line of it a comment.</p>
 */
final class ApiOnlyTranscriberJDslSchema {

    private static final String SUBSCRIPTION_EXAMPLE = String.join("\n",
            "// One block per contract, named as apiOnlySubscriber subscribes to it:",
            "// orders {",
            "//     // The package the core classes are generated into. Required.",
            "//     basePackage = 'com.example.orders.contract'",
            "//     // The source sets that compile the generated classes. Default: ['test']",
            "//     // sourceSets = ['test']",
            "//     // How many times a recursive $ref is followed in field descriptions. Default: 3",
            "//     // recursionDepth = 3",
            "//     // Whether descriptions come from the contract. Default: false, and every",
            "//     // description is then the empty string",
            "//     // generateDocs = false",
            "//     // With generateDocs on, the description of what the contract does not describe.",
            "//     // descriptionPlaceholder = '" + TranscriberJSubscription.DEFAULT_DESCRIPTION_PLACEHOLDER + "'",
            "//     // A ResourceBundle the descriptions resolve through first. Default: none",
            "//     // descriptionBundle = 'docs.Descriptions'",
            "//     // Where the sources are generated. Default: build/generated/sources/transcriberj/orders",
            "//     // into = layout.buildDirectory.dir('generated/sources/transcriberj/orders')",
            "// }");

    static final DslExtensionSchema SCHEMA = new DslExtensionSchema(
            ApiOnlyTranscriberJExtension.NAME,
            List.of(
                    DslPropertySpec.scalar("strictDependencies", "false",
                            "Whether a dependency version an emitter was not tested with fails the build instead of being warned about."),
                    DslPropertySpec.container("subscriptions",
                            "The contracts classes are generated from, one block each.",
                            SUBSCRIPTION_EXAMPLE)
            ));

    private ApiOnlyTranscriberJDslSchema() {
    }
}
