package com.arc_e_tect.gradle.apionly.transcriberj;

import org.gradle.api.Project;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.plugins.ide.eclipse.model.EclipseModel;
import org.gradle.plugins.ide.idea.model.IdeaModel;

import java.io.File;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Tells an IDE about a contract's generated sources: which task produces them, so a
 * project import runs it, and which directory holds them, so they are indexed as
 * generated code rather than as hand-written source.
 *
 * <p>Every hook is registered reactively, so applying the TranscriberJ never applies an
 * IDE plugin to a build that did not ask for one, and a build that applies one -- before
 * or after the TranscriberJ -- gets the wiring without configuring anything.
 */
final class IdeIntegration {

    /** The plugin id of the JetBrains IDEA extensions plugin, which carries the sync hook. */
    static final String IDEA_EXT_PLUGIN = "org.jetbrains.gradle.plugin.idea-ext";

    /** Where the root project remembers which generation tasks already trigger on sync. */
    private static final String REGISTERED = "apiOnlyTranscriberJ.afterSync";

    private IdeIntegration() {
    }

    /**
     * Wires one contract's generation task into whichever IDE integrations the build has.
     *
     * @param project   the project the TranscriberJ is applied to
     * @param generate  the contract's generation task
     * @param sources   where that task writes the class tree
     * @param resources where that task writes a resource an emitter wrote
     */
    static void wire(Project project, TaskProvider<GenerateContractSourcesTask> generate,
                     Provider<Directory> sources, Provider<Directory> resources) {
        project.getPluginManager().withPlugin("idea", applied -> {
            markGenerated(project, sources);
            markGenerated(project, resources);
        });
        // IntelliJ reads the task triggers from the root project's model, so that is where
        // they are registered however deep the project is, and whichever project applies
        // idea-ext. Registering is idempotent, so both reactions below can fire.
        Project root = project.getRootProject();
        root.getPluginManager().withPlugin(IDEA_EXT_PLUGIN, applied -> runOnSync(root, generate));
        if (root != project) {
            project.getPluginManager().withPlugin(IDEA_EXT_PLUGIN, applied -> runOnSync(root, generate));
        }
        project.getPluginManager().withPlugin("eclipse", applied ->
                project.getExtensions().getByType(EclipseModel.class).synchronizationTasks(generate));
    }

    /** Adds the directory to IntelliJ's generated source directories, keeping what is there. */
    private static void markGenerated(Project project, Provider<Directory> sources) {
        IdeaModel idea = project.getExtensions().getByType(IdeaModel.class);
        Set<File> directories = new LinkedHashSet<>(idea.getModule().getGeneratedSourceDirs());
        directories.add(sources.get().getAsFile());
        idea.getModule().setGeneratedSourceDirs(directories);
    }

    /**
     * Registers the generation task as an {@code afterSync} trigger, so importing the
     * project in IntelliJ generates the sources before it indexes them.
     *
     * <p>The idea-ext model is reached reflectively on purpose: Gradle gives each plugin
     * its own class loader, so the classes of the idea-ext the build applies are not
     * necessarily the ones this plugin could compile against, and its version is the
     * build's choice rather than this plugin's.
     */
    private static void runOnSync(Project root, TaskProvider<GenerateContractSourcesTask> generate) {
        if (registered(root).add(generate.getName())) {
            registerAfterSync(root, taskTriggers(root), generate);
        }
    }

    /** The generation tasks already registered on this root project, one set per build. */
    @SuppressWarnings("unchecked")
    private static Set<String> registered(Project root) {
        ExtraPropertiesExtension properties = root.getExtensions().getExtraProperties();
        if (!properties.has(REGISTERED)) {
            properties.set(REGISTERED, new HashSet<String>());
        }
        return (Set<String>) properties.get(REGISTERED);
    }

    /**
     * The idea-ext task triggers of a project, or null when its IDEA model does not carry
     * them -- which is what a build without idea-ext, or with one shaped differently, has.
     *
     * @param project the project
     * @return the {@code taskTriggers} object, or null
     */
    static Object taskTriggers(Project project) {
        IdeaModel idea = project.getExtensions().findByType(IdeaModel.class);
        // Only the root project has an IDEA project model; a subproject's is null.
        Object ideaProject = idea == null ? null : idea.getProject();
        Object settings = ideaProject == null ? null
                : ((ExtensionAware) ideaProject).getExtensions().findByName("settings");
        return settings == null ? null : ((ExtensionAware) settings).getExtensions().findByName("taskTriggers");
    }

    /**
     * Calls {@code afterSync} on the idea-ext task triggers, and says whether it could.
     *
     * <p>A build that applies an idea-ext this plugin does not recognise keeps working: the
     * sources are still generated by every build that compiles them, and the warning says
     * what to run by hand.
     *
     * @param project  the project, for its logger
     * @param triggers the idea-ext task triggers, or null when the model does not hold them
     * @param generate the contract's generation task
     * @return true when the task was registered as an {@code afterSync} trigger
     */
    static boolean registerAfterSync(Project project, Object triggers,
                                     TaskProvider<GenerateContractSourcesTask> generate) {
        if (triggers == null) {
            project.getLogger().warn("The API-Only TranscriberJ found {} applied without the task triggers it "
                    + "carries, so {} will not run on IDE sync. Run it by hand, or see the plugin's README.",
                    IDEA_EXT_PLUGIN, generate.getName());
            return false;
        }
        try {
            triggers.getClass().getMethod("afterSync", Object[].class)
                    .invoke(triggers, (Object) new Object[]{generate});
            return true;
        } catch (ReflectiveOperationException e) {
            project.getLogger().warn("The API-Only TranscriberJ could not register {} as an afterSync trigger "
                    + "of {}: {}. Run the task by hand, or see the plugin's README.",
                    generate.getName(), IDEA_EXT_PLUGIN, e.getMessage());
            return false;
        }
    }
}
