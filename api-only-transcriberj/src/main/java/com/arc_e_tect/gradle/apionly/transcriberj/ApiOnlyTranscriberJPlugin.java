package com.arc_e_tect.gradle.apionly.transcriberj;

import com.arc_e_tect.gradle.apionly.subscriber.ApiOnlySubscriberExtension;
import com.arc_e_tect.gradle.apionly.subscriber.ApiOnlySubscriberPlugin;
import com.arc_e_tect.gradle.apionly.subscriber.Subscription;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.ManagedDependency;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The API-Only TranscriberJ: a class tree derived from a subscribed contract, for
 * contract tests and stubs.
 *
 * <p>Applies the API-Only Subscriber, which fetches the contract. For every
 * {@code subscription} in the {@code apiOnlyTranscriberJ} block it registers
 * {@code generateContractSources<Contract>}, adds its output to the configured
 * source sets, and registers {@code verifyContractSources<Contract>}, which
 * {@code check} runs.
 */
public class ApiOnlyTranscriberJPlugin implements Plugin<Project> {

    /** The configuration emitter libraries are added to. */
    public static final String EMITTERS_CONFIGURATION = "transcriberjEmitters";

    /** The prefix of each contract's generation task. */
    public static final String GENERATE_TASK = "generateContractSources";

    /** The prefix of each contract's verification task. */
    public static final String VERIFY_TASK = "verifyContractSources";

    /** The task that adds missing {@code apiOnlyTranscriberJ} properties to the build file. */
    public static final String UPDATE_DSL_TASK = "updateApiOnlyTranscriberJDSL";

    /**
     * What the generated code itself needs: the marker annotation every generated class
     * carries, so that a project measuring coverage does not measure code nobody wrote.
     * Managed like an emitter's dependency -- preferred, never forced -- so a project
     * already using the library keeps its own version.
     */
    static final ManagedDependency ANNOTATION =
            new ManagedDependency("com.arc-e-tect.sedr.utils", "sedr-library", "1.0.0", "2");

    /** Creates the plugin. */
    public ApiOnlyTranscriberJPlugin() {
    }

    @Override
    @SuppressWarnings("unchecked")
    public void apply(Project project) {
        project.getPluginManager().apply(ApiOnlySubscriberPlugin.class);
        ApiOnlySubscriberExtension subscriber = project.getExtensions().getByType(ApiOnlySubscriberExtension.class);

        Configuration emitters = project.getConfigurations().create(EMITTERS_CONFIGURATION, c -> {
            c.setDescription("Emitter libraries for the API-Only TranscriberJ.");
            c.setCanBeConsumed(false);
            c.setCanBeResolved(true);
        });

        ApiOnlyTranscriberJExtension extension = project.getExtensions()
                .create(ApiOnlyTranscriberJExtension.NAME, ApiOnlyTranscriberJExtension.class);
        project.getTasks().register(UPDATE_DSL_TASK, UpdateApiOnlyTranscriberJDslTask.class, task ->
            task.getBuildFile().set(project.getLayout().file(project.provider(project::getBuildFile))));
        extension.getStrictDependencies().convention(false);

        // Read once, when first needed: while a source set's dependencies are resolved.
        Map<String, List<ManagedDependency>>[] managed = new Map[1];
        Supplier<Map<String, List<ManagedDependency>>> managedDependencies = () -> {
            if (managed[0] == null) {
                Map<String, List<ManagedDependency>> all = new LinkedHashMap<>();
                all.put("the generated code", List.of(ANNOTATION));
                all.putAll(ManagedDependencies.of(emitters.getFiles()));
                managed[0] = all;
            }
            return managed[0];
        };

        extension.getSubscriptions().all(subscription -> {
            String contract = subscription.getName();
            String suffix = suffix(contract);
            subscription.getSourceSets().convention(List.of("test"));
            subscription.getRecursionDepth().convention(3);
            subscription.getGenerateDocs().convention(false);
            subscription.getInvalidRequestStatus().convention(Settings.DEFAULT_INVALID_REQUEST_STATUS);
            subscription.getStrictRequests().convention(true);
            subscription.getValidateFormats().convention(List.of());
            subscription.getEmitterOptions().convention(Map.of());
            subscription.getDescriptionPlaceholder()
                    .convention(TranscriberJSubscription.DEFAULT_DESCRIPTION_PLACEHOLDER);
            subscription.getInto().convention(
                    project.getLayout().getBuildDirectory().dir("generated/sources/transcriberj/" + contract));
            subscription.getIntoResources().convention(
                    project.getLayout().getBuildDirectory().dir("generated/resources/transcriberj/" + contract));
            subscription.getReportFile().convention(
                    project.getLayout().getBuildDirectory().file("reports/transcriberj/" + contract + ".txt"));
            subscription.getEndpointIndexFile().convention(project.getLayout().getBuildDirectory()
                    .file("generated/transcriberj-index/" + contract + "/contract-endpoints.properties"));

            Provider<RegularFile> document = project.provider(() -> subscriber.subscription(contract))
                    .flatMap(Subscription::getOpenapi);
            // A contract describes events or it does not; the Subscriber only offers the
            // document when the archive holds one.
            Provider<RegularFile> asyncDocument = project.provider(() -> subscriber.subscription(contract))
                    .flatMap(Subscription::getAsyncapi);

            TaskProvider<GenerateContractSourcesTask> generate = project.getTasks().register(
                    GENERATE_TASK + suffix, GenerateContractSourcesTask.class, task -> {
                        task.setGroup("api-only");
                        task.setDescription("Generates the class tree of the " + contract + " contract.");
                        task.getContract().set(document);
                        task.getAsyncContract().setFrom(asyncDocument);
                        task.getLockfile().set(subscriber.getLockfile());
                        task.getContractName().set(contract);
                        task.getBasePackage().set(subscription.getBasePackage());
                        task.getRecursionDepth().set(subscription.getRecursionDepth());
                        task.getGenerateDocs().set(subscription.getGenerateDocs());
                        task.getDescriptionPlaceholder().set(subscription.getDescriptionPlaceholder());
                        task.getDescriptionBundle().set(subscription.getDescriptionBundle());
                        task.getInvalidRequestStatus().set(subscription.getInvalidRequestStatus());
                        task.getStrictRequests().set(subscription.getStrictRequests());
                        task.getValidateFormats().set(subscription.getValidateFormats());
                        task.getEmitterOptions().set(subscription.getEmitterOptions());
                        task.getEmitterClasspath().from(emitters);
                        task.getOutputDirectory().set(subscription.getInto());
                        task.getResourceDirectory().set(subscription.getIntoResources());
                        task.getReportFile().set(subscription.getReportFile());
                        task.getValidValuesReport().set(project.getLayout().file(
                                subscription.getReportFile().map(ApiOnlyTranscriberJPlugin::validValuesReport)));
                        task.getEndpointIndex().set(subscription.getEndpointIndexFile());
                    });

            // The IDE indexes what is on disk at sync time, so it is told which task
            // produces the sources and which directory holds them. The endpoint index is
            // not offered: it is a properties file read by a Gradle task, not source.
            IdeIntegration.wire(project, generate, subscription.getInto(), subscription.getIntoResources());

            subscription.getEndpointIndex().set(generate.flatMap(GenerateContractSourcesTask::getEndpointIndex));
            subscription.getEndpointIndex().disallowChanges();

            TaskProvider<VerifyContractSourcesTask> verify = project.getTasks().register(
                    VERIFY_TASK + suffix, VerifyContractSourcesTask.class, task -> {
                        task.setGroup("verification");
                        task.setDescription("Checks that the " + contract
                                + " class tree was generated from the locked contract.");
                        task.mustRunAfter(generate);
                        task.getLockfile().set(subscriber.getLockfile());
                        task.getContractName().set(contract);
                        task.getBasePackage().set(subscription.getBasePackage());
                        task.getSourcesDirectory().set(subscription.getInto());
                    });

            project.getPlugins().withType(LifecycleBasePlugin.class, plugin -> project.getTasks()
                    .named(LifecycleBasePlugin.CHECK_TASK_NAME, task -> task.dependsOn(verify)));

            project.getPlugins().withType(JavaPlugin.class, plugin -> project.afterEvaluate(p -> {
                SourceSetContainer sourceSets = p.getExtensions().getByType(SourceSetContainer.class);
                for (String name : subscription.getSourceSets().get()) {
                    SourceSet set = sourceSets.getByName(name);
                    set.getJava().srcDir(generate.flatMap(GenerateContractSourcesTask::getOutputDirectory));
                    set.getResources().srcDir(generate.flatMap(GenerateContractSourcesTask::getResourceDirectory));
                    manage(p, set, managedDependencies, extension);
                }
            }));
        });
    }

    /**
     * Adds the emitters' dependencies to a source set, each at a version it only
     * prefers, and checks the versions resolved for it.
     */
    private static void manage(Project project, SourceSet set,
                               Supplier<Map<String, List<ManagedDependency>>> managedDependencies,
                               ApiOnlyTranscriberJExtension extension) {
        project.getConfigurations().named(set.getImplementationConfigurationName()).configure(c ->
                c.withDependencies(dependencies -> managedDependencies.get().values().stream()
                        .flatMap(List::stream).distinct().forEach(m -> {
                            ExternalModuleDependency dependency = (ExternalModuleDependency)
                                    project.getDependencies().create(m.group() + ":" + m.name());
                            dependency.version(v -> v.prefer(m.pinnedVersion()));
                            dependencies.add(dependency);
                        })));
        for (String classpath : List.of(set.getCompileClasspathConfigurationName(),
                set.getRuntimeClasspathConfigurationName())) {
            project.getConfigurations().named(classpath).configure(c -> c.getIncoming().afterResolve(result -> {
                Map<String, String> resolved = new HashMap<>();
                result.getResolutionResult().getAllComponents().forEach(component -> {
                    ModuleVersionIdentifier id = component.getModuleVersion();
                    if (id != null) resolved.put(id.getGroup() + ":" + id.getName(), id.getVersion());
                });
                ManagedDependencies.check(classpath, resolved, managedDependencies.get(),
                        extension.getStrictDependencies().get(), project.getLogger()::warn);
            }));
        }
    }

    /** A contract's task-name suffix, as the Subscriber forms it: {@code user-account} is {@code UserAccount}. */
    static String suffix(String contract) {
        StringBuilder out = new StringBuilder(contract.length());
        boolean upper = true;
        for (char c : contract.toCharArray()) {
            if (c == '-' || c == '_' || c == '.') {
                upper = true;
                continue;
            }
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return out.toString();
    }

    /**
     * The machine-readable report's file, beside the report: {@code orders.txt} gives
     * {@code orders.valid-values.json}.
     */
    static java.io.File validValuesReport(RegularFile report) {
        java.io.File file = report.getAsFile();
        String name = file.getName();
        String base = name.endsWith(".txt") ? name.substring(0, name.length() - 4) : name;
        return new java.io.File(file.getParentFile(), base + ".valid-values.json");
    }
}
