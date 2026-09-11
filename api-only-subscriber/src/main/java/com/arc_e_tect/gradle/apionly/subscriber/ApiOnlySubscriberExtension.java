package com.arc_e_tect.gradle.apionly.subscriber;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;

import javax.inject.Inject;

/**
 * apiOnlySubscriber { ... }
 */
public abstract class ApiOnlySubscriberExtension {

    private final ChannelSpec channel;
    private final NamedDomainObjectContainer<Subscription> subscriptions;

    @Inject
    public ApiOnlySubscriberExtension(ObjectFactory objects) {
        this.channel = objects.newInstance(ChannelSpec.class);
        this.subscriptions = objects.domainObjectContainer(
            Subscription.class, name -> objects.newInstance(Subscription.class, name));
    }

    public ChannelSpec getChannel() {
        return channel;
    }

    public void channel(Action<? super ChannelSpec> action) {
        action.execute(channel);
    }

    public NamedDomainObjectContainer<Subscription> getSubscriptions() {
        return subscriptions;
    }

    /** subscribe('user-account') { version = '2.1.0' } */
    public Subscription subscribe(String target, Action<? super Subscription> action) {
        Subscription subscription = subscriptions.maybeCreate(target);
        action.execute(subscription);
        return subscription;
    }

    public Subscription subscribe(String target) {
        return subscriptions.maybeCreate(target);
    }

    /** Look one up, so a build file can wire the fetched document into something. */
    public Subscription subscription(String target) {
        Subscription subscription = subscriptions.findByName(target);
        if (subscription == null) {
            throw new IllegalArgumentException(
                "no subscription for '" + target + "'; declared subscriptions are " + subscriptions.getNames());
        }
        return subscription;
    }

    /**
     * Where the resolved versions and hashes are recorded.
     *
     * Committed, and shared by every subscription in the project.
     */
    public abstract RegularFileProperty getLockfile();
}
