package com.arc_e_tect.gradle.apionly.subscriber;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

/**
 * The {@code apiOnlySubscriber} block: which contracts this project builds
 * against, and where they come from.
 *
 * <pre>{@code
 * apiOnlySubscriber {
 *     channel {
 *         type = 'maven'
 *         groupId = 'com.example.contracts'
 *     }
 *     subscribe('customer-orders') {
 *         version = '2.1.0'
 *     }
 * }
 * }</pre>
 *
 * <p>One channel serves every subscription in a project. Each subscription can
 * carry its own version, because contracts are versioned independently; one that
 * does not takes {@link #getVersion()}.</p>
 *
 * @see ApiOnlySubscriberPlugin
 * @see Subscription
 * @see ChannelSpec
 */
public abstract class ApiOnlySubscriberExtension {

    /** The name used to register this extension in a consumer build. */
    public static final String NAME = "apiOnlySubscriber";

    private final ChannelSpec channel;
    private final NamedDomainObjectContainer<Subscription> subscriptions;

    /**
     * Creates the extension.
     *
     * <p>Gradle instantiates this when the plugin is applied; a build script
     * configures the instance registered as {@code apiOnlySubscriber}.</p>
     *
     * @param objects Gradle's object factory, supplied by injection
     */
    @Inject
    public ApiOnlySubscriberExtension(ObjectFactory objects) {
        this.channel = objects.newInstance(ChannelSpec.class);
        this.subscriptions = objects.domainObjectContainer(
            Subscription.class, name -> objects.newInstance(Subscription.class, name));
    }

    /**
     * The channel every subscription in this project resolves through.
     *
     * @return the channel specification
     */
    public ChannelSpec getChannel() {
        return channel;
    }

    /**
     * Configures the channel.
     *
     * <pre>{@code
     * channel {
     *     type = 'file'
     *     directory = "$rootDir/build/publish"
     * }
     * }</pre>
     *
     * @param action configuration applied to the channel specification
     */
    public void channel(Action<? super ChannelSpec> action) {
        action.execute(channel);
    }

    /**
     * Every declared subscription, keyed by target name.
     *
     * @return the container of subscriptions
     */
    public NamedDomainObjectContainer<Subscription> getSubscriptions() {
        return subscriptions;
    }

    /**
     * Declares a subscription and configures it.
     *
     * <pre>{@code
     * subscribe('customer-orders') {
     *     version = '2.1.0'
     * }
     * }</pre>
     *
     * <p>Subscribing to the same target twice configures the existing
     * subscription rather than creating a second one.</p>
     *
     * @param target the contract to subscribe to
     * @param action configuration applied to the subscription
     * @return the subscription, so it can be referenced immediately
     */
    public Subscription subscribe(String target, Action<? super Subscription> action) {
        Subscription subscription = subscriptions.maybeCreate(target);
        action.execute(subscription);
        return subscription;
    }

    /**
     * Declares a subscription without configuring it.
     *
     * <p>Only useful when the version is set later, since a subscription with no
     * version fails the build when it is resolved.</p>
     *
     * @param target the contract to subscribe to
     * @return the subscription
     */
    public Subscription subscribe(String target) {
        return subscriptions.maybeCreate(target);
    }

    /**
     * Looks up a declared subscription, so a build file can wire its documents
     * into whatever consumes them.
     *
     * <pre>{@code
     * openApiGenerate {
     *     inputSpec = apiOnlySubscriber.subscription('customer-orders').openapi.map { it.asFile.path }
     * }
     * }</pre>
     *
     * @param target the subscribed contract to look up
     * @return the subscription for that target
     * @throws IllegalArgumentException if no such subscription was declared; the
     *         message lists the targets that were
     */
    public Subscription subscription(String target) {
        Subscription subscription = subscriptions.findByName(target);
        if (subscription == null) {
            throw new IllegalArgumentException(
                "no subscription for '" + target + "'; declared subscriptions are " + subscriptions.getNames());
        }
        return subscription;
    }

    /**
     * Where the resolved versions and file hashes are recorded.
     *
     * <p>Defaults to {@code apionly.lock} beside the build file. It is shared by
     * every subscription in the project and is meant to be committed: it is what
     * {@link VerifyApiSpecTask} checks the fetched documents against, and what
     * makes "which contract is this project actually building against?" a
     * question answerable by reading the repository.</p>
     *
     * @return the lockfile location
     */
    public abstract RegularFileProperty getLockfile();

    /**
     * The contract version every subscription in this project resolves, unless it
     * sets its own.
     *
     * <p>Defaults to the {@code apiContractVersion} project property, so the version
     * can live in {@code gradle.properties} -- in a multi-project build, the
     * subproject's own -- or be given with {@code -PapiContractVersion=...} or
     * {@code ORG_GRADLE_PROJECT_apiContractVersion}.</p>
     *
     * <pre>{@code
     * apiOnlySubscriber {
     *     version = '2.1.0'
     *     subscribe('customer-orders')
     * }
     * }</pre>
     *
     * @return the default contract version; unset when the property is not defined
     */
    public abstract Property<String> getVersion();
}
