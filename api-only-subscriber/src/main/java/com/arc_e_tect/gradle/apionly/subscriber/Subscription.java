package com.arc_e_tect.gradle.apionly.subscriber;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFile;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

import javax.inject.Inject;

/**
 * One subscribed contract.
 *
 * The version is declared per target rather than per channel, because each
 * target is versioned independently: a change to one service's context must not
 * bump every other service.
 */
public abstract class Subscription {

    private final String target;
    private TaskProvider<FetchApiSpecTask> fetch;

    @Inject
    public Subscription(String target, ObjectFactory objects) {
        this.target = target;
        getGroupId().convention((String) null);
        getArtifactId().convention(target);
        getAllowPrerelease().convention(false);
    }

    /** Gradle's containers key elements by name; the name is the target. */
    public String getName() {
        return target;
    }

    /** The contract this subscription is for. Same value as {@link #getName()}. */
    public String getTarget() {
        return target;
    }

    /** The version of this target's contract to build against. */
    public abstract Property<String> getVersion();

    /** Overrides the channel's groupId for this one target, if it differs. */
    public abstract Property<String> getGroupId();

    /** Overrides the artifact name, which defaults to the target name. */
    public abstract Property<String> getArtifactId();

    /**
     * Whether this subscription may resolve a pre-release contract.
     *
     * False by default, and deliberately so. API-Only design means implementation
     * starts against a contract that is not finished, so pre-releases have to
     * exist -- but a pre-release must never quietly satisfy a production build.
     * Opting in is a visible, reviewable line in a build file.
     */
    public abstract Property<Boolean> getAllowPrerelease();

    /**
     * Where the fetched documents land.
     *
     * Defaults to build/api-spec/&lt;target&gt;/ rather than src/main/resources.
     * Generated files inside a source tree show up in IDE search, tempt
     * hand-editing, and survive a clean. Writing into src/ stays possible for
     * teams whose tooling insists on it, but it is not the default.
     */
    public abstract DirectoryProperty getInto();

    /**
     * Called by the plugin once the fetch task exists.
     *
     * The documents are exposed as providers derived from that task, so anything
     * wired to them carries the dependency automatically. Handing out a bare path
     * would let a consumer read the directory before it had been populated --
     * which is precisely the class of bug this plugin exists to remove.
     */
    void fetchedBy(TaskProvider<FetchApiSpecTask> fetch) {
        this.fetch = fetch;
    }

    private Provider<RegularFile> document(String name) {
        if (fetch == null) {
            throw new IllegalStateException(
                "subscription '" + target + "' is not wired to a fetch task yet");
        }
        return fetch.flatMap(task -> task.getInto().file(name));
    }

    /** The fetched OpenAPI document, for wiring into whatever consumes it. */
    public Provider<RegularFile> getOpenapi() {
        return document("openapi.yaml");
    }

    /** The fetched AsyncAPI document. */
    public Provider<RegularFile> getAsyncapi() {
        return document("asyncapi.yaml");
    }
}
