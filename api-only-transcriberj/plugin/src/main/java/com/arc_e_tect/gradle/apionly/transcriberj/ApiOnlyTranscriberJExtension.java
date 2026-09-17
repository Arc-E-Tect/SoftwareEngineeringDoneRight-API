package com.arc_e_tect.gradle.apionly.transcriberj;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

/**
 * The {@code apiOnlyTranscriberJ} block: which subscribed contracts get a generated
 * class tree, and how.
 *
 * <pre>
 * apiOnlyTranscriberJ {
 *     subscription('orders') {
 *         basePackage = 'com.example.orders.contract'
 *     }
 * }
 * </pre>
 */
public abstract class ApiOnlyTranscriberJExtension {

    /** The name the extension is registered under. */
    public static final String NAME = "apiOnlyTranscriberJ";

    private final NamedDomainObjectContainer<TranscriberJSubscription> subscriptions;

    /**
     * Creates the extension.
     *
     * @param objects Gradle's object factory
     */
    @Inject
    public ApiOnlyTranscriberJExtension(ObjectFactory objects) {
        this.subscriptions = objects.domainObjectContainer(TranscriberJSubscription.class,
                name -> objects.newInstance(TranscriberJSubscription.class, name));
    }

    /**
     * Whether a dependency version an emitter was not tested with fails the build,
     * rather than only being warned about.
     *
     * <p>Default: {@code false}.
     *
     * @return the setting
     */
    public abstract Property<Boolean> getStrictDependencies();

    /**
     * Every contract classes are generated for.
     *
     * @return the subscriptions
     */
    public NamedDomainObjectContainer<TranscriberJSubscription> getSubscriptions() {
        return subscriptions;
    }

    /**
     * Generates classes for a contract the {@code apiOnlySubscriber} block subscribes to.
     *
     * @param contract the contract's name, as subscribed
     * @param action   how its classes are generated
     * @return the settings
     */
    public TranscriberJSubscription subscription(String contract, Action<? super TranscriberJSubscription> action) {
        TranscriberJSubscription subscription = subscriptions.maybeCreate(contract);
        action.execute(subscription);
        return subscription;
    }
}
