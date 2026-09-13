package com.arc_e_tect.gradle.apionly.subscriber;

import org.gradle.api.provider.Property;

/**
 * Where subscribed contracts are fetched from.
 *
 * <p>Configured for the project inside the {@code apiOnlySubscriber} block, and
 * optionally again inside a subscription, whose channel takes every setting it
 * leaves out from the project's:</p>
 *
 * <pre>{@code
 * apiOnlySubscriber {
 *     channel {
 *         type = 'maven'
 *         groupId = 'com.example.contracts'
 *     }
 *     subscribeAsClient('order-payments') {
 *         version = '1.4.0'
 *         channel {
 *             groupId = 'com.example.payments'
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p>{@code maven} is the default because it costs this plugin almost nothing.
 * The artifact is declared in a detached configuration and Gradle's own
 * dependency resolution performs the fetching, caching and up-to-date checking,
 * so the plugin carries no HTTP client, no cache and no retry policy of its
 * own.</p>
 *
 * <p>{@code file} reads a local directory instead. It exists for iterating on a
 * contract before it is published anywhere, and is the only channel that touches
 * the filesystem directly.</p>
 *
 * @see Subscription#channel(org.gradle.api.Action)
 * @see ApiOnlySubscriberExtension#channel(org.gradle.api.Action)
 */
public abstract class ChannelSpec {

    /**
     * Creates the specification with {@code maven} as the channel type.
     *
     * <p>Gradle instantiates this; a build script configures the instance handed
     * to {@link ApiOnlySubscriberExtension#channel(org.gradle.api.Action)} rather
     * than constructing one.</p>
     */
    public ChannelSpec() {
        getType().convention("maven");
    }

    /**
     * Which kind of channel to resolve through.
     *
     * <p>Either {@code "maven"} or {@code "file"}. Anything else fails the build
     * with a message naming the channels that do exist.</p>
     *
     * @return the channel type; defaults to {@code "maven"}
     */
    public abstract Property<String> getType();

    /**
     * The Maven group the contracts are published under.
     *
     * <p>Required by the {@code maven} channel. A single subscription may
     * override it through {@link Subscription#getGroupId()} when one target is
     * published somewhere else.</p>
     *
     * @return the group id, with no default
     */
    public abstract Property<String> getGroupId();

    /**
     * The artifact extension the contracts are published with.
     *
     * <p>Used by the {@code maven} channel to form the dependency notation
     * {@code group:name:version@extension}.</p>
     *
     * @return the extension; defaults to {@code "tgz"}
     */
    public abstract Property<String> getExtension();

    /**
     * The directory the {@code file} channel reads from.
     *
     * <p>Archives are expected at
     * {@code <directory>/<target>/<version>/<target>-<version>.tgz}, which is the
     * layout {@code api-only-publisher publish --channel file} writes.</p>
     *
     * @return the directory, with no default
     */
    public abstract Property<String> getDirectory();
}
