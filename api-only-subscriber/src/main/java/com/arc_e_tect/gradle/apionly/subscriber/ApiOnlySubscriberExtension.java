package com.arc_e_tect.gradle.apionly.subscriber;

import org.gradle.api.Action;
import org.gradle.api.InvalidUserDataException;
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
 * <p>A project implements at most one contract, and may call any number of APIs.
 * {@link #subscribe(String, Action)} declares the contract it implements; a
 * second one is refused, because each contract belongs in a project of its own.
 * {@link #subscribeAsClient(String, Action)} declares an API it calls, which gets
 * every check the implemented contract gets.</p>
 *
 * @see ApiOnlySubscriberPlugin
 * @see Subscription
 * @see ChannelSpec
 */
public abstract class ApiOnlySubscriberExtension {

    /** The name used to register this extension in a consumer build. */
    public static final String NAME = "apiOnlySubscriber";

    // Where a build that subscribes to a second contract is sent to read why.
    private static final String ONE_CONTRACT_PER_PROJECT =
        "https://github.com/Arc-E-Tect/SoftwareEngineeringDoneRight-API/blob/main/api-only-subscriber/README.adoc#one-contract-per-project";

    private final ChannelSpec channel;
    private final NamedDomainObjectContainer<Subscription> subscriptions;
    // Set only while subscribeAsClient creates a subscription, so the container's
    // factory marks it as a client before anything reacts to it being added.
    private boolean creatingClient;

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
        this.subscriptions = objects.domainObjectContainer(Subscription.class, name -> {
            Subscription subscription = objects.newInstance(Subscription.class, name);
            // The role is fixed before the subscription is added, so everything that
            // reacts to a new subscription already knows which kind it is.
            if (creatingClient) {
                subscription.markClient();
            }
            return subscription;
        });
        // subscribe(...) refuses a second implemented contract before creating it. This
        // refuses one added to the container directly, which would otherwise get past that.
        this.subscriptions.whenObjectAdded(added -> {
            if (added.isClient()) {
                return;
            }
            subscriptions.stream()
                .filter(other -> !other.isClient() && !other.getName().equals(added.getName()))
                .findFirst()
                .ifPresent(other -> {
                    throw secondContract(other.getName(), added.getName());
                });
        });
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
     * Declares the contract this project implements, and configures it.
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
     * @throws InvalidUserDataException if this project already implements another
     *         contract, or calls this one; the message says why, and what to do
     */
    public Subscription subscribe(String target, Action<? super Subscription> action) {
        Subscription subscription = subscriptionFor(target);
        action.execute(subscription);
        return subscription;
    }

    /**
     * Declares the contract this project implements, without configuring it.
     *
     * <p>Only useful when the version is set later, since a subscription with no
     * version fails the build when it is resolved.</p>
     *
     * @param target the contract to subscribe to
     * @return the subscription
     * @throws InvalidUserDataException if this project already implements another
     *         contract, or calls this one; the message says why, and what to do
     */
    public Subscription subscribe(String target) {
        return subscriptionFor(target);
    }

    /**
     * Declares an API this project calls, and configures it.
     *
     * <pre>{@code
     * subscribeAsClient('order-payments') {
     *     apiContractVersion = '1.4.0'
     * }
     * }</pre>
     *
     * <p>A project may call any number of APIs, next to the one contract it
     * implements. A client subscription is fetched, checked against its archive's
     * manifest, locked and verified exactly like the implemented contract. It
     * differs in two ways. It sets its own version, because {@link #getApiContractVersion()}
     * and {@code apiContractVersion} are the version of the contract this project
     * implements. And with the {@code java} plugin, its documents reach the
     * classpath under {@code contracts/<target>/}, leaving the root to that
     * contract.</p>
     *
     * <p>Subscribing to the same API twice configures the existing subscription
     * rather than creating a second one.</p>
     *
     * @param target the API to subscribe to
     * @param action configuration applied to the subscription
     * @return the subscription, so it can be referenced immediately
     * @throws InvalidUserDataException if this project implements that contract;
     *         a project either implements a contract or calls it
     */
    public Subscription subscribeAsClient(String target, Action<? super Subscription> action) {
        Subscription subscription = clientSubscriptionFor(target);
        action.execute(subscription);
        return subscription;
    }

    /**
     * Declares an API this project calls, without configuring it.
     *
     * @param target the API to subscribe to
     * @return the subscription
     * @throws InvalidUserDataException if this project implements that contract
     * @see #subscribeAsClient(String, Action)
     */
    public Subscription subscribeAsClient(String target) {
        return clientSubscriptionFor(target);
    }

    private Subscription subscriptionFor(String target) {
        Subscription existing = subscriptions.findByName(target);
        if (existing != null) {
            if (existing.isClient()) {
                throw bothRoles(target, true);
            }
            return existing;
        }
        subscriptions.stream()
            .filter(other -> !other.isClient())
            .findFirst()
            .ifPresent(other -> {
                throw secondContract(other.getName(), target);
            });
        return subscriptions.create(target);
    }

    private Subscription clientSubscriptionFor(String target) {
        Subscription existing = subscriptions.findByName(target);
        if (existing != null) {
            if (!existing.isClient()) {
                throw bothRoles(target, false);
            }
            return existing;
        }
        creatingClient = true;
        try {
            return subscriptions.create(target);
        } finally {
            creatingClient = false;
        }
    }

    // A project implements one contract. The message is the documentation a build
    // author meets first, so it says why, and what to do instead.
    private static InvalidUserDataException secondContract(String existing, String target) {
        return new InvalidUserDataException(
            "apiOnlySubscriber already implements '" + existing + "', so it cannot also implement '"
                + target + "'.\n"
                + "A project implements one API contract: two in one project would share one version, one "
                + "classpath root and one release, so neither could change without the other.\n"
                + "If this project calls '" + target + "' rather than implementing it, declare it with "
                + "subscribeAsClient('" + target + "') instead.\n"
                + "If it implements both, put each contract in a project of its own, in a multi-project build, "
                + "with its own apiOnlySubscriber block and apiContractVersion.\n"
                + "See " + ONE_CONTRACT_PER_PROJECT);
    }

    // The same contract implemented and called would be fetched and locked twice,
    // possibly at two versions.
    private static InvalidUserDataException bothRoles(String target, boolean existingIsClient) {
        return new InvalidUserDataException(existingIsClient
            ? "apiOnlySubscriber calls '" + target + "' as a client, so it cannot also implement '" + target
              + "'. A project either implements a contract or calls it."
            : "apiOnlySubscriber implements '" + target + "', so it cannot also subscribe to it as a client. "
              + "A project either implements a contract or calls it.");
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
     * The version of the contract this project implements, unless its subscription
     * sets its own. An API the project calls is not affected.
     *
     * <p>Defaults to the {@code apiContractVersion} project property, so the version
     * can live in {@code gradle.properties} -- in a multi-project build, the
     * subproject's own -- in the build script's {@code ext}, or in
     * {@code ORG_GRADLE_PROJECT_apiContractVersion}. Given on the command line, with
     * {@code -PapiContractVersion=...}, the property overrides every version the
     * build sets for the implemented contract.</p>
     *
     * <pre>{@code
     * apiOnlySubscriber {
     *     apiContractVersion = '2.1.0'
     *     subscribe('customer-orders')
     * }
     * }</pre>
     *
     * @return the default contract version; unset when the property is not defined
     */
    public abstract Property<String> getApiContractVersion();

    /**
     * Refuses the property's old name. Without it, a build script that still sets
     * {@code version} here would set the project's own version instead, silently.
     *
     * @param version ignored
     * @throws org.gradle.api.GradleException always, naming the new property
     */
    public void setVersion(Object version) {
        throw new org.gradle.api.GradleException("apiOnlySubscriber.version was renamed to apiContractVersion. Set "
            + "apiOnlySubscriber { apiContractVersion = '" + version + "' } instead.");
    }
}
