package com.arc_e_tect.gradle.apionly.transcriberj;

import com.arc_e_tect.gradle.apionly.subscriber.ApiOnlySubscriberExtension;
import com.arc_e_tect.gradle.apionly.subscriber.ApiOnlySubscriberPlugin;
import com.arc_e_tect.gradle.apionly.subscriber.Subscription;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.ManagedDependency;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Output;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.Usage;
import org.gradle.api.component.AdhocComponentWithVariants;
import org.gradle.api.component.SoftwareComponentFactory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The API-Only TranscriberJ: a class tree derived from a subscribed contract, for
 * contract tests and stubs.
 *
 * <p>Applies the API-Only Subscriber, which fetches the contract. For every
 * {@code subscription} in the {@code apiOnlyTranscriberJ} block it registers
 * {@code generateContractSources<Contract>} for the core and
 * {@code generateContractSources<Contract><Emitter>} for each emitter, places each one's output
 * where the subscription says, and registers {@code reportContractSources<Contract>}, which
 * writes the one report, and {@code verifyContractSources<Contract>}, which {@code check} runs.
 * An emitter that writes files gets {@code package<Contract><Emitter>}, an archive of them, and
 * a component to publish it with.
 */
public class ApiOnlyTranscriberJPlugin implements Plugin<Project> {

    /** The configuration emitter libraries are added to. */
    public static final String EMITTERS_CONFIGURATION = "transcriberjEmitters";

    /** The prefix of each contract's and each emitter's generation task. */
    public static final String GENERATE_TASK = "generateContractSources";

    /** The prefix of each contract's report task. */
    public static final String REPORT_TASK = "reportContractSources";

    /** The prefix of each contract's verification task. */
    public static final String VERIFY_TASK = "verifyContractSources";

    /** The prefix of each archive task. */
    public static final String PACKAGE_TASK = "package";

    /** The task that adds missing {@code apiOnlyTranscriberJ} properties to the build file. */
    public static final String UPDATE_DSL_TASK = "updateApiOnlyTranscriberJDSL";

    /** The usage an archive of an emitter's files is published with. */
    public static final String FILES_USAGE = "apionly-files";

    /**
     * What the generated code itself needs: the marker annotation every generated class
     * carries, so that a project measuring coverage does not measure code nobody wrote.
     * Managed like an emitter's dependency -- preferred, never forced -- so a project
     * already using the library keeps its own version.
     */
    static final ManagedDependency ANNOTATION =
            new ManagedDependency("com.arc-e-tect.sedr.utils", "sedr-library", "1.0.0", "2");

    private static final String GENERATED_CODE = "the generated code";

    private final SoftwareComponentFactory components;

    /**
     * Creates the plugin.
     *
     * @param components Gradle's factory of publishable components
     */
    @Inject
    public ApiOnlyTranscriberJPlugin(SoftwareComponentFactory components) {
        this.components = components;
    }

    @Override
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

        Map<String, TaskProvider<GenerateContractSourcesTask>> cores = new LinkedHashMap<>();
        extension.getSubscriptions().all(subscription -> cores.put(subscription.getName(),
                configure(project, subscriber, subscription)));

        project.afterEvaluate(p -> {
            if (extension.getSubscriptions().isEmpty()) return;
            try (EmitterCatalog catalog = EmitterCatalog.of(emitters)) {
                for (TranscriberJSubscription subscription : extension.getSubscriptions()) {
                    wireEmitters(p, subscriber, emitters, extension, catalog, subscription,
                            cores.get(subscription.getName()));
                }
            }
        });
    }

    /** The subscription's conventions, and the tasks that do not depend on which emitters are loaded. */
    private TaskProvider<GenerateContractSourcesTask> configure(Project project,
                                                                       ApiOnlySubscriberExtension subscriber,
                                                                       TranscriberJSubscription subscription) {
        String contract = subscription.getName();
        String suffix = suffix(contract);
        var build = project.getLayout().getBuildDirectory();
        subscription.getSourceSets().convention(List.of("test"));
        subscription.getSchemaClasses().convention(EmitterPlan.PER_SOURCE_SET);
        subscription.getRecursionDepth().convention(3);
        subscription.getGenerateDocs().convention(false);
        subscription.getInvalidRequestStatus().convention(Settings.DEFAULT_INVALID_REQUEST_STATUS);
        subscription.getStrictRequests().convention(true);
        subscription.getValidateFormats().convention(List.of());
        emitterOptions(subscription).convention(Map.of());
        subscription.getDescriptionPlaceholder()
                .convention(TranscriberJSubscription.DEFAULT_DESCRIPTION_PLACEHOLDER);
        subscription.getInto().convention(build.dir("generated/sources/transcriberj/" + contract + "/core"));
        subscription.getIntoResources().convention(build.dir("generated/resources/transcriberj/" + contract + "/core"));
        subscription.getReportFile().convention(build.file("reports/transcriberj/" + contract + ".txt"));
        subscription.getEndpointIndexFile().convention(
                build.file("generated/transcriberj-index/" + contract + "/contract-endpoints.properties"));
        subscription.getEmitters().all(spec -> {
            String id = spec.getName();
            spec.getSourceSets().convention((List<String>) null);
            spec.getOptions().convention(Map.of());
            spec.getIntoJava().convention(build.dir("generated/sources/transcriberj/" + contract + "/" + id));
            spec.getIntoResources().convention(build.dir("generated/resources/transcriberj/" + contract + "/" + id));
            spec.getIntoFiles().convention(build.dir("generated/files/transcriberj/" + contract + "/" + id));
            // Created as soon as the block is, so that a build script can publish it; the archive
            // is attached once the emitter is known to write files.
            component(project, contract, id);
        });

        TaskProvider<GenerateContractSourcesTask> generate = project.getTasks().register(
                GENERATE_TASK + suffix, GenerateContractSourcesTask.class, task -> {
                    task.setGroup("api-only");
                    task.setDescription("Generates the core of the class tree of the " + contract + " contract.");
                    inputs(project, subscriber, subscription, task);
                    task.getOutputDirectory().set(subscription.getInto());
                    task.getResourceDirectory().set(subscription.getIntoResources());
                    task.getEndpointIndex().set(subscription.getEndpointIndexFile());
                    task.getReportFragment().set(fragment(project, subscription, "core", ".json"));
                    task.getAsciiDocFragment().set(fragment(project, subscription, "core", ".adoc"));
                    task.getValidValuesReport().set(project.getLayout().file(
                            subscription.getReportFile().map(ApiOnlyTranscriberJPlugin::validValuesReport)));
                });

        TaskProvider<ReportContractSourcesTask> report = project.getTasks().register(
                REPORT_TASK + suffix, ReportContractSourcesTask.class, task -> {
                    task.setGroup("api-only");
                    task.setDescription("Writes the report of the " + contract + " contract's generation.");
                    task.getContractName().set(contract);
                    task.getLockfile().set(subscriber.getLockfile());
                    task.getCoreFragment().set(generate.flatMap(GenerateContractSourcesTask::getReportFragment));
                    task.getAsciiDocFragments().from(generate.flatMap(GenerateContractSourcesTask::getAsciiDocFragment));
                    task.getReportFile().set(subscription.getReportFile());
                    task.getAsciiDocReport().set(project.getLayout().file(
                            subscription.getReportFile().map(ApiOnlyTranscriberJPlugin::asciiDocReport)));
                });
        generate.configure(task -> task.finalizedBy(report));

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
        return generate;
    }

    /** What every generation task of a subscription derives the model from. */
    private static void inputs(Project project, ApiOnlySubscriberExtension subscriber,
                               TranscriberJSubscription subscription, ContractSourcesTask task) {
        String contract = subscription.getName();
        Provider<RegularFile> document = project.provider(() -> subscriber.subscription(contract))
                .flatMap(Subscription::getOpenapi);
        // A contract describes events or it does not; the Subscriber only offers the
        // document when the archive holds one.
        Provider<RegularFile> asyncDocument = project.provider(() -> subscriber.subscription(contract))
                .flatMap(Subscription::getAsyncapi);
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
    }

    /** The emitters' tasks, archives and placement, once the build has said which it configures. */
    private void wireEmitters(Project project, ApiOnlySubscriberExtension subscriber, Configuration emitters,
                              ApiOnlyTranscriberJExtension extension, EmitterCatalog catalog,
                              TranscriberJSubscription subscription,
                              TaskProvider<GenerateContractSourcesTask> generate) {
        String contract = subscription.getName();
        String suffix = suffix(contract);
        Map<String, EmitterPlan.Configured> configured = new LinkedHashMap<>();
        for (EmitterSpec spec : subscription.getEmitters()) {
            configured.put(spec.getName(), new EmitterPlan.Configured(spec.getSourceSets().getOrNull(),
                    spec.getOptions().getOrElse(Map.of())));
        }
        EmitterPlan plan = EmitterPlan.of(contract, subscription.getSchemaClasses().get(),
                subscription.getSourceSets().get(), emitterOptions(subscription).get(), configured, catalog.emitters());
        plan.warnings().forEach(warning -> project.getLogger().warn(warning));

        TaskProvider<ReportContractSourcesTask> report = project.getTasks()
                .named(REPORT_TASK + suffix, ReportContractSourcesTask.class);
        TaskProvider<VerifyContractSourcesTask> verify = project.getTasks()
                .named(VERIFY_TASK + suffix, VerifyContractSourcesTask.class);
        // Known before any task runs, as a publication reads an archive's name while the build is
        // configured: the locked version, or on a first build, before anything is fetched, the
        // version the subscription asks for.
        Provider<String> subscribed = project.provider(() -> subscriber.subscription(contract))
                .flatMap(Subscription::getApiContractVersion).orElse("unspecified");
        Provider<String> version = project.getProviders().fileContents(subscriber.getLockfile()).getAsText()
                .orElse("").map(text -> LockedContract.version(text, contract))
                .zip(subscribed, (locked, asked) -> locked.equals("unspecified") ? asked : locked);

        Map<String, TaskProvider<GenerateEmitterSourcesTask>> tasks = new LinkedHashMap<>();
        for (EmitterPlan.Placement placement : plan.emitters().values()) {
            String id = placement.id();
            EmitterSpec spec = subscription.getEmitters().maybeCreate(id);
            boolean files = placement.produces().contains(Output.FILES);
            TaskProvider<GenerateEmitterSourcesTask> task = project.getTasks().register(
                    GENERATE_TASK + suffix + suffix(id), GenerateEmitterSourcesTask.class, t -> {
                        t.setGroup("api-only");
                        t.setDescription("Generates the " + id + " emitter's output for the " + contract
                                + " contract.");
                        inputs(project, subscriber, subscription, t);
                        t.dependsOn(generate);
                        t.getEmitterId().set(id);
                        t.getOptions().set(placement.options());
                        t.getEmitterVersion().set(catalog.version(id));
                        t.getTranscriberJVersion().set(transcriberJVersion());
                        t.getEmitterClasspath().from(emitters);
                        t.getJavaDirectory().set(spec.getIntoJava());
                        t.getResourceDirectory().set(spec.getIntoResources());
                        t.getFilesDirectory().set(spec.getIntoFiles());
                        t.getReportFragment().set(fragment(project, subscription, id, ".json"));
                        t.getAsciiDocFragment().set(fragment(project, subscription, id, ".adoc"));
                        t.getStamp().set(project.getLayout().getBuildDirectory()
                                .file("generated/transcriberj-stamps/" + contract + "/" + id + ".properties"));
                        if (files) {
                            t.getProvenance().set(project.getLayout().getBuildDirectory().file(
                                    "generated/transcriberj-provenance/" + contract + "/" + id + "/"
                                            + Provenance.FILE));
                        }
                        t.finalizedBy(report);
                    });
            tasks.put(id, task);
            report.configure(r -> {
                r.getEmitterFragments().from(task.flatMap(GenerateEmitterSourcesTask::getReportFragment));
                r.getAsciiDocFragments().from(task.flatMap(GenerateEmitterSourcesTask::getAsciiDocFragment));
            });
            verify.configure(v -> {
                v.mustRunAfter(task);
                v.getEmitterStamps().from(task.flatMap(GenerateEmitterSourcesTask::getStamp));
            });
            IdeIntegration.sync(project, task);
            if (!placement.sourceSets().isEmpty()) {
                IdeIntegration.markGenerated(project, spec.getIntoJava(), spec.getIntoResources());
            }
            if (files) {
                archive(project, contract, id, task, version);
            }
        }

        project.getPlugins().withType(JavaPlugin.class, plugin ->
                placeSourceSets(project, extension, catalog, plan, generate, tasks));
    }

    /** The archive of an emitter's files, the configuration it is published through, and its component. */
    private void archive(Project project, String contract, String id, TaskProvider<GenerateEmitterSourcesTask> task,
                         Provider<String> version) {
        String name = suffix(contract) + suffix(id);
        TaskProvider<Zip> zip = project.getTasks().register(PACKAGE_TASK + name, Zip.class, z -> {
            z.setGroup("api-only");
            z.setDescription("Packages the " + id + " emitter's files for the " + contract + " contract.");
            z.from(task.flatMap(GenerateEmitterSourcesTask::getFilesDirectory));
            z.from(task.flatMap(GenerateEmitterSourcesTask::getProvenance));
            z.getDestinationDirectory().set(project.getLayout().getBuildDirectory().dir("distributions"));
            z.getArchiveFileName().set(version.map(v -> contract + "-" + id + "-" + v + ".zip"));
            z.setPreserveFileTimestamps(false);
            z.setReproducibleFileOrder(true);
            z.filePermissions(permissions -> permissions.unix("rw-r--r--"));
            z.dirPermissions(permissions -> permissions.unix("rwxr-xr-x"));
        });
        zip.configure(z -> z.getOutputs().cacheIf("the archive is reproducible", t -> true));
        component(project, contract, id).getOutgoing().artifact(zip);
    }

    /**
     * The consumable configuration an emitter's archive is published through, and the component
     * that holds it, created once: {@code transcriberj<Contract><Emitter>Elements} and
     * {@code transcriberj<Contract><Emitter>}. The archive is attached only to an emitter that
     * writes files.
     */
    private Configuration component(Project project, String contract, String id) {
        String name = suffix(contract) + suffix(id);
        Configuration existing = project.getConfigurations().findByName("transcriberj" + name + "Elements");
        if (existing != null) return existing;
        Configuration elements = project.getConfigurations().create("transcriberj" + name + "Elements", c -> {
            c.setDescription("The " + id + " emitter's files for the " + contract + " contract, as an archive.");
            c.setCanBeConsumed(true);
            c.setCanBeResolved(false);
            c.attributes(attributes -> {
                attributes.attribute(Category.CATEGORY_ATTRIBUTE,
                        project.getObjects().named(Category.class, Category.LIBRARY));
                attributes.attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, FILES_USAGE));
            });
        });
        AdhocComponentWithVariants component = components.adhoc("transcriberj" + name);
        component.addVariantsFromConfiguration(elements, details -> {
        });
        project.getComponents().add(component);
        return elements;
    }

    /** Each source set's generated directories and managed dependencies, as the plan places them. */
    private static void placeSourceSets(Project project, ApiOnlyTranscriberJExtension extension,
                                        EmitterCatalog catalog, EmitterPlan plan,
                                        TaskProvider<GenerateContractSourcesTask> generate,
                                        Map<String, TaskProvider<GenerateEmitterSourcesTask>> tasks) {
        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        Map<String, Map<String, List<ManagedDependency>>> managed = new LinkedHashMap<>();
        List<SourceSet> schema = new ArrayList<>();
        if (EmitterPlan.SHARED.equals(plan.schemaClasses())) {
            SourceSet shared = sourceSets.maybeCreate(EmitterPlan.sharedSourceSet(plan.contract()));
            schema.add(shared);
            for (String name : plan.coreSourceSets()) {
                SourceSet set = sourceSet(sourceSets, plan, name);
                project.getDependencies().add(set.getImplementationConfigurationName(),
                        project.files(shared.getOutput()));
                managed.computeIfAbsent(name, n -> new LinkedHashMap<>()).put(GENERATED_CODE, List.of(ANNOTATION));
            }
        } else {
            plan.coreSourceSets().forEach(name -> schema.add(sourceSet(sourceSets, plan, name)));
        }
        for (SourceSet set : schema) {
            set.getJava().srcDir(generate.flatMap(GenerateContractSourcesTask::getOutputDirectory));
            set.getResources().srcDir(generate.flatMap(GenerateContractSourcesTask::getResourceDirectory));
            managed.computeIfAbsent(set.getName(), n -> new LinkedHashMap<>()).put(GENERATED_CODE, List.of(ANNOTATION));
        }
        for (EmitterPlan.Placement placement : plan.emitters().values()) {
            TaskProvider<GenerateEmitterSourcesTask> task = tasks.get(placement.id());
            for (String name : placement.sourceSets()) {
                SourceSet set = sourceSet(sourceSets, plan, name);
                if (placement.produces().contains(Output.JAVA)) {
                    set.getJava().srcDir(task.flatMap(GenerateEmitterSourcesTask::getJavaDirectory));
                }
                if (placement.produces().contains(Output.RESOURCES)) {
                    set.getResources().srcDir(task.flatMap(GenerateEmitterSourcesTask::getResourceDirectory));
                }
                Map<String, List<ManagedDependency>> forSet = managed.computeIfAbsent(name, n -> new LinkedHashMap<>());
                forSet.put(GENERATED_CODE, List.of(ANNOTATION));
                forSet.put(placement.id(), catalog.dependencies(placement.id()));
            }
        }
        managed.forEach((name, byEmitter) -> manage(project, sourceSets.getByName(name), byEmitter, extension));
    }

    private static SourceSet sourceSet(SourceSetContainer sourceSets, EmitterPlan plan, String name) {
        SourceSet set = sourceSets.findByName(name);
        if (set == null) {
            throw new org.gradle.api.GradleException("apiOnlyTranscriberJ: subscription('" + plan.contract()
                    + "') names source set '" + name + "', which this project does not have; the source sets are "
                    + String.join(", ", sourceSets.getNames()) + ".");
        }
        return set;
    }

    /**
     * Adds the dependencies of what a source set compiles to it, each at a version it only
     * prefers, and checks the versions resolved for it.
     */
    private static void manage(Project project, SourceSet set, Map<String, List<ManagedDependency>> byEmitter,
                               ApiOnlyTranscriberJExtension extension) {
        project.getConfigurations().named(set.getImplementationConfigurationName()).configure(c ->
                c.withDependencies(dependencies -> byEmitter.values().stream()
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
                ManagedDependencies.check(classpath, resolved, byEmitter,
                        extension.getStrictDependencies().get(), project.getLogger()::warn);
            }));
        }
    }

    @SuppressWarnings("deprecation")
    private static org.gradle.api.provider.MapProperty<String, Map<String, String>> emitterOptions(
            TranscriberJSubscription subscription) {
        return subscription.getEmitterOptions();
    }

    /** Where a generation task writes its part of the report, beside the report. */
    private static Provider<RegularFile> fragment(Project project, TranscriberJSubscription subscription,
                                                  String part, String extension) {
        return project.getLayout().file(subscription.getReportFile().map(report -> {
            java.io.File file = report.getAsFile();
            String name = file.getName();
            String base = name.endsWith(".txt") ? name.substring(0, name.length() - 4) : name;
            return new java.io.File(new java.io.File(file.getParentFile(), base), part + extension);
        }));
    }

    /** The TranscriberJ's own version, as its build recorded it. */
    static String transcriberJVersion() {
        try (InputStream in = ApiOnlyTranscriberJPlugin.class.getResourceAsStream("transcriberj.properties")) {
            if (in == null) return "unspecified";
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("version", "unspecified");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A contract's or an emitter's task-name suffix, as the Subscriber forms it: {@code user-account} is {@code UserAccount}. */
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
        return sibling(report, ".valid-values.json");
    }

    /** The AsciiDoc report's file, beside the report: {@code orders.txt} gives {@code orders.adoc}. */
    static java.io.File asciiDocReport(RegularFile report) {
        return sibling(report, ".adoc");
    }

    private static java.io.File sibling(RegularFile report, String extension) {
        java.io.File file = report.getAsFile();
        String name = file.getName();
        String base = name.endsWith(".txt") ? name.substring(0, name.length() - 4) : name;
        return new java.io.File(file.getParentFile(), base + extension);
    }
}
