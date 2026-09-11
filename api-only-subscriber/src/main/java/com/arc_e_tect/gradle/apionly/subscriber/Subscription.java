package com.arc_e_tect.gradle.apionly.subscriber;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFile;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

import javax.inject.Inject;

/**
 * One subscribed contract: which target, at which version, landing where.
 *
 * <p>Created through {@link ApiOnlySubscriberExtension#subscribe(String,
 * org.gradle.api.Action)}:</p>
 *
 * <pre>{@code
 * apiOnlySubscriber {
 *     subscribe('user-account') {
 *         version = '2.1.0'
 *     }
 * }
 * }</pre>
 *
 * <p>The version is declared per target rather than per channel, because each
 * target is versioned independently. A change confined to one service's
 * fragments must not oblige every other service to take a new version.</p>
 *
 * <p>Each subscription registers a {@code fetchApiSpec<Target>} and a
 * {@code verifyApiSpec<Target>} task, and contributes to the aggregate
 * {@code fetchApiSpec} and {@code verifyApiSpec} tasks.</p>
 *
 * @see ChannelSpec
 * @see FetchApiSpecTask
 * @see VerifyApiSpecTask
 */
public abstract class Subscription {

    private final String target;
    private TaskProvider<FetchApiSpecTask> fetch;

    /**
     * Creates a subscription for one target.
     *
     * <p>Gradle instantiates this through its object factory; build scripts call
     * {@link ApiOnlySubscriberExtension#subscribe(String, org.gradle.api.Action)}
     * rather than constructing one directly.</p>
     *
     * @param target  the contract this subscription is for, which is also the
     *                element's name within the container
     * @param objects Gradle's object factory, supplied by injection
     */
    @Inject
    public Subscription(String target, ObjectFactory objects) {
        this.target = target;
        getGroupId().convention((String) null);
        getArtifactId().convention(target);
        getAllowPrerelease().convention(false);
    }

    /**
     * The name this subscription is keyed by in its container.
     *
     * <p>Gradle's {@code NamedDomainObjectContainer} requires it; here it is
     * always the target name, so this and {@link #getTarget()} agree.</p>
     *
     * @return the target name
     */
    public String getName() {
        return target;
    }

    /**
     * The contract this subscription is for.
     *
     * @return the target name, the same value as {@link #getName()}
     */
    public String getTarget() {
        return target;
    }

    /**
     * The version of this target's contract to build against.
     *
     * <p>Required. A pre-release version is refused unless
     * {@link #getAllowPrerelease()} is set.</p>
     *
     * @return the version to resolve, with no default
     */
    public abstract Property<String> getVersion();

    /**
     * Overrides {@link ChannelSpec#getGroupId()} for this one target.
     *
     * <p>Useful when most contracts come from one group but a particular target
     * is published elsewhere.</p>
     *
     * @return the group id for this target; unset, meaning the channel's applies
     */
    public abstract Property<String> getGroupId();

    /**
     * Overrides the artifact name this target is published under.
     *
     * @return the artifact id; defaults to the target name
     */
    public abstract Property<String> getArtifactId();

    /**
     * Whether this subscription may resolve a pre-release contract.
     *
     * <p>False by default, and deliberately. API-Only design means implementation
     * begins against a contract that is not finished, so pre-releases must exist
     * — but a pre-release must never quietly satisfy a build that did not ask for
     * one. Opting in is a visible, reviewable line in a build file:</p>
     *
     * <pre>{@code
     * subscribe('user-account') {
     *     version = '2.1.0-rc.1'
     *     allowPrerelease = true
     * }
     * }</pre>
     *
     * <p>Both the SemVer spelling ({@code -rc.1}, {@code -alpha.3}) and Maven's
     * {@code -SNAPSHOT} count as pre-releases.</p>
     *
     * @return whether a pre-release may be resolved; defaults to {@code false}
     */
    public abstract Property<Boolean> getAllowPrerelease();

    /**
     * Where the fetched documents land.
     *
     * <p>Defaults to {@code build/api-spec/<target>/} rather than
     * {@code src/main/resources}. Generated files inside a source tree show up in
     * IDE search, tempt hand-editing, and survive a {@code clean}. From
     * {@code build/} they reach the classpath identically, because the plugin
     * registers this directory as an additional resources source directory.</p>
     *
     * <p>Writing into {@code src/} remains possible for teams whose tooling
     * insists on it; it is simply not the default.</p>
     *
     * @return the directory fetched documents are unpacked into
     */
    public abstract DirectoryProperty getInto();

    /**
     * Records the fetch task that populates this subscription.
     *
     * <p>Called by the plugin once that task is registered. The documents are
     * then exposed as providers derived from it, so anything wired to them
     * carries the task dependency automatically. Handing out a bare path would
     * let a consumer read the directory before anything had populated it, which
     * is the class of bug this plugin exists to remove.</p>
     *
     * @param fetch the task that fetches this subscription's documents
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

    /**
     * The fetched OpenAPI document.
     *
     * <p>Wire this into whatever validates against the contract, and the
     * dependency on the fetch travels with it:</p>
     *
     * <pre>{@code
     * apiOnlySuite {
     *     rootDocument = apiOnlySubscriber.subscription('user-account').openapi
     * }
     * }</pre>
     *
     * @return a provider for {@code openapi.yaml} inside {@link #getInto()},
     *         carrying a dependency on the fetch task
     * @throws IllegalStateException if read before the plugin has registered the
     *         fetch task, which cannot happen from a build script
     */
    public Provider<RegularFile> getOpenapi() {
        return document("openapi.yaml");
    }

    /**
     * The fetched AsyncAPI document.
     *
     * @return a provider for {@code asyncapi.yaml} inside {@link #getInto()},
     *         carrying a dependency on the fetch task
     * @throws IllegalStateException if read before the plugin has registered the
     *         fetch task, which cannot happen from a build script
     * @see #getOpenapi()
     */
    public Provider<RegularFile> getAsyncapi() {
        return document("asyncapi.yaml");
    }
}
