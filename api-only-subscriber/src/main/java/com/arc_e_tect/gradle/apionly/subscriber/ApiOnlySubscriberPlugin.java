package com.arc_e_tect.gradle.apionly.subscriber;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

import java.io.File;
import java.util.Locale;

/**
 * The consumer half of the API-Only pair.
 *
 * An implementation project declares which contracts it builds against and at
 * which versions. The plugin fetches them, verifies them, and puts them on the
 * classpath -- without the project holding a copy of anything the specification
 * library owns.
 *
 * The question an implementation's build answers changes as a result. It stops being
 * "does this implementation match this file that happens to sit in src/?" and
 * becomes "does this implementation match the organisation's published contract,
 * at version X?"
 */
public class ApiOnlySubscriberPlugin implements Plugin<Project> {

    /** The name this plugin's extension is registered under: {@value}. */
    public static final String EXTENSION_NAME = "apiOnlySubscriber";

    /** The aggregate task that fetches every subscribed contract: {@value}. */
    public static final String FETCH_TASK = "fetchApiSpec";

    /** The aggregate task that checks every fetched contract: {@value}. */
    public static final String VERIFY_TASK = "verifyApiSpec";

    /** The DSL-updating task registered by this plugin. */
    public static final String UPDATE_DSL_TASK = "updateApiOnlySubscriberDSL";

    /** The project property {@code apiOnlySubscriber.version} defaults to: {@value}. */
    public static final String CONTRACT_VERSION_PROPERTY = "apiContractVersion";

    /** Creates the plugin. Gradle instantiates this when the plugin is applied. */
    public ApiOnlySubscriberPlugin() {
        // Nothing to do: all configuration happens in apply(Project).
    }

    /**
     * Registers the {@code apiOnlySubscriber} extension and the tasks that act on
     * it.
     *
     * @param project the project the plugin is applied to
     */
    @Override
    public void apply(Project project) {
        ApiOnlySubscriberExtension extension =
            project.getExtensions().create(EXTENSION_NAME, ApiOnlySubscriberExtension.class);

        extension.getLockfile().convention(project.getLayout().getProjectDirectory().file("apionly.lock"));

        // Read through the project, not providers.gradleProperty(...): that does not
        // see a subproject's own gradle.properties, which is exactly where a service
        // in a multi-project build keeps its version.
        extension.getVersion().convention(project.provider(() -> {
            Object version = project.findProperty(CONTRACT_VERSION_PROPERTY);
            return version == null ? null : version.toString();
        }));

        project.getTasks().register(UPDATE_DSL_TASK, UpdateApiOnlySubscriberDslTask.class, task ->
            task.getBuildFile().set(project.getLayout().file(project.provider(project::getBuildFile))));

        TaskProvider<?> fetchAll = project.getTasks().register(FETCH_TASK, task -> {
            task.setGroup("api-only");
            task.setDescription("Fetches every subscribed API description document into this build.");
        });
        TaskProvider<?> verifyAll = project.getTasks().register(VERIFY_TASK, task -> {
            task.setGroup("verification");
            task.setDescription("Fails when a fetched API description has drifted from apionly.lock.");
        });

        extension.getSubscriptions().all(subscription ->
            configureSubscription(project, extension, subscription, fetchAll, verifyAll));

        // Drift is a build failure, not something to remember to check.
        project.getPlugins().withType(LifecycleBasePlugin.class, plugin ->
            project.getTasks().named(LifecycleBasePlugin.CHECK_TASK_NAME, task -> task.dependsOn(verifyAll)));
    }

    private void configureSubscription(
        Project project,
        ApiOnlySubscriberExtension extension,
        Subscription subscription,
        TaskProvider<?> fetchAll,
        TaskProvider<?> verifyAll
    ) {
        String target = subscription.getTarget();
        String suffix = capitalize(target);

        subscription.getInto().convention(
            project.getLayout().getBuildDirectory().dir("api-spec/" + target));
        subscription.getVersion().convention(extension.getVersion());

        ConfigurableFileCollection archive = project.getObjects().fileCollection();
        archive.from(project.provider(() -> resolveArchive(project, extension, subscription)));

        TaskProvider<FetchApiSpecTask> fetch = project.getTasks().register(
            FETCH_TASK + suffix, FetchApiSpecTask.class, task -> {
                task.setGroup("api-only");
                task.setDescription("Fetches the " + target + " API description into this build.");
                task.getArchive().from(archive);
                task.getTarget().set(target);
                task.getVersion().set(subscription.getVersion());
                task.getChannel().set(extension.getChannel().getType());
                task.getInto().set(subscription.getInto());
                task.getLockfile().set(extension.getLockfile());
            });

        subscription.fetchedBy(fetch);

        TaskProvider<VerifyApiSpecTask> verify = project.getTasks().register(
            VERIFY_TASK + suffix, VerifyApiSpecTask.class, task -> {
                task.setGroup("verification");
                task.setDescription("Checks that the fetched " + target + " API description still matches apionly.lock.");
                // Ordered after a fetch, but deliberately not dependent on one.
                // Depending on it would re-fetch before checking, quietly repairing
                // exactly the drift this task exists to report -- a hand-edited
                // document would be overwritten and the build would go green.
                task.mustRunAfter(fetch);
                task.getTarget().set(target);
                task.getInto().set(subscription.getInto());
                task.getLockfile().set(extension.getLockfile());
            });

        fetchAll.configure(task -> task.dependsOn(fetch));
        verifyAll.configure(task -> task.dependsOn(verify));

        // The fetched directory becomes a resource directory, so the document
        // reaches the classpath exactly as it did when it was generated into
        // src/main/resources -- without anything generated living under src/.
        project.getPlugins().withType(JavaPlugin.class, plugin -> {
            SourceSetContainer sourceSets =
                project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets();
            sourceSets.named(SourceSet.MAIN_SOURCE_SET_NAME, main ->
                main.getResources().srcDir(fetch.map(FetchApiSpecTask::getInto)));
            project.getTasks().named(JavaPlugin.PROCESS_RESOURCES_TASK_NAME,
                task -> task.dependsOn(fetch));
        });
    }

    /**
     * Where the archive comes from.
     *
     * The maven channel declares a dependency and lets Gradle resolve it, which
     * is the whole reason to prefer it: version resolution, caching and
     * up-to-date checking come free, and this plugin carries no HTTP client.
     */
    private File resolveArchive(
        Project project,
        ApiOnlySubscriberExtension extension,
        Subscription subscription
    ) {
        String type = extension.getChannel().getType().getOrElse("maven");
        String target = subscription.getTarget();
        String version = subscription.getVersion().getOrElse(null);
        if (version == null) {
            throw new GradleException("subscription '" + target + "' declares no version. Set version on the "
                + "subscription, set version on apiOnlySubscriber for every subscription, or define the "
                + CONTRACT_VERSION_PROPERTY + " project property.");
        }
        if (isPrerelease(version) && !subscription.getAllowPrerelease().get()) {
            throw new GradleException(
                "subscription '" + target + "' resolves the pre-release version " + version + ".\n"
                + "A contract that is not finished must not quietly satisfy a build. Set "
                + "allowPrerelease = true on this subscription to work against it deliberately, "
                + "and remember to take it off again before release.");
        }

        if ("file".equals(type)) {
            String directory = extension.getChannel().getDirectory().getOrNull();
            if (directory == null) {
                throw new GradleException("the file channel requires channel.directory");
            }
            // Where the archive belongs, not a check that it is there. This runs while
            // the build is still being planned, and the same build may publish the
            // archive in a task that runs before the fetch; FetchApiSpecTask checks for
            // it when it runs.
            String artifactId = subscription.getArtifactId().getOrElse(target);
            return project.file(directory)
                .toPath()
                .resolve(target)
                .resolve(version)
                .resolve(artifactId + "-" + version + ".tgz")
                .toFile();
        }

        if (!"maven".equals(type)) {
            throw new GradleException(
                "unknown channel type '" + type + "'; this version supports 'maven' and 'file'");
        }

        String groupId = subscription.getGroupId().getOrElse(
            extension.getChannel().getGroupId().getOrNull());
        if (groupId == null) {
            throw new GradleException("the maven channel requires channel.groupId");
        }
        String artifactId = subscription.getArtifactId().getOrElse(target);
        String extensionName = extension.getChannel().getExtension().getOrElse("tgz");

        Dependency dependency = project.getDependencies().create(
            groupId + ":" + artifactId + ":" + version + "@" + extensionName);
        Configuration configuration = project.getConfigurations().detachedConfiguration(dependency);
        configuration.setTransitive(false);
        return configuration.getSingleFile();
    }

    /**
     * A pre-release in the semver sense, plus Maven's -SNAPSHOT spelling, which
     * is not semver-legal but is what Maven consumers expect.
     */
    static boolean isPrerelease(String version) {
        if (version.endsWith("-SNAPSHOT")) {
            return true;
        }
        int plus = version.indexOf('+');
        String withoutBuild = plus >= 0 ? version.substring(0, plus) : version;
        return withoutBuild.indexOf('-') >= 0;
    }

    private static String capitalize(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean upper = true;
        for (char c : value.toCharArray()) {
            if (c == '-' || c == '_' || c == '.') {
                upper = true;
                continue;
            }
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return out.toString();
    }
}
