package com.arc_e_tect.gradle.apionly.subscriber;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PrereleaseTest {

    @Test
    void plainReleasesAreNotPrereleases() {
        assertFalse(ApiOnlySubscriberPlugin.isPrerelease("1.0.0"));
        assertFalse(ApiOnlySubscriberPlugin.isPrerelease("12.34.56"));
    }

    @Test
    void semverPrereleasesAreRecognised() {
        assertTrue(ApiOnlySubscriberPlugin.isPrerelease("1.1.0-rc.1"));
        assertTrue(ApiOnlySubscriberPlugin.isPrerelease("2.0.0-alpha.3"));
    }

    @Test
    void mavensSnapshotSpellingCounts() {
        // Not semver-legal, but it is what Maven consumers expect, so it is
        // recognised rather than rejected as malformed.
        assertTrue(ApiOnlySubscriberPlugin.isPrerelease("1.0.0-SNAPSHOT"));
    }

    @Test
    void buildMetadataAloneIsNotAPrerelease() {
        // 1.0.0+build.5 is a release with build metadata; only what follows a '-'
        // before any '+' makes it a pre-release.
        assertFalse(ApiOnlySubscriberPlugin.isPrerelease("1.0.0+build.5"));
        assertTrue(ApiOnlySubscriberPlugin.isPrerelease("1.0.0-rc.1+build.5"));
    }
}
