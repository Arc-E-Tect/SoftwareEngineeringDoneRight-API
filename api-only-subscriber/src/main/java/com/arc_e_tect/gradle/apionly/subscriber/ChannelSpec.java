package com.arc_e_tect.gradle.apionly.subscriber;

import org.gradle.api.provider.Property;

/**
 * Where subscribed contracts are fetched from.
 *
 * `maven` is the default because it costs the plugin almost nothing: the
 * artifact is declared in a detached configuration and Gradle's own dependency
 * resolution does the fetching, caching and up-to-date checking, instead of this
 * plugin carrying a hand-rolled HTTP client, a cache and a retry policy.
 *
 * `file` is the local-iteration escape hatch, and the only channel that reads
 * the filesystem directly.
 */
public abstract class ChannelSpec {

    public ChannelSpec() {
        getType().convention("maven");
    }

    /** "maven" or "file". */
    public abstract Property<String> getType();

    /** maven: the group the contracts are published under. */
    public abstract Property<String> getGroupId();

    /** maven: the artifact extension. Defaults to tgz. */
    public abstract Property<String> getExtension();

    /** file: the directory published to. */
    public abstract Property<String> getDirectory();
}
