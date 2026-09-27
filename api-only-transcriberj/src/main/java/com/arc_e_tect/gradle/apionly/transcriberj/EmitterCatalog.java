package com.arc_e_tect.gradle.apionly.transcriberj;

import com.arc_e_tect.gradle.apionly.transcriberj.spi.Emitter;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.ManagedDependency;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The emitters on the {@code transcriberjEmitters} classpath, loaded once while the build is
 * configured: what each declares it writes decides where its output goes, so the plugin must ask
 * before any task runs. Each emitter's version is the version its library resolved at.
 */
final class EmitterCatalog implements AutoCloseable {

    private final URLClassLoader loader;
    private final List<Emitter> emitters;
    private final Map<String, String> versions = new HashMap<>();

    private EmitterCatalog(URLClassLoader loader, List<Emitter> emitters) {
        this.loader = loader;
        this.emitters = emitters;
    }

    /**
     * Loads the emitters of a configuration.
     *
     * @param configuration the {@code transcriberjEmitters} configuration
     * @return the catalog; close it when the build is configured
     */
    static EmitterCatalog of(Configuration configuration) {
        Map<File, String> fileVersions = new HashMap<>();
        for (ResolvedArtifactResult artifact : configuration.getIncoming().getArtifacts().getArtifacts()) {
            String version = artifact.getId().getComponentIdentifier() instanceof ModuleComponentIdentifier module
                    ? module.getVersion() : "unspecified";
            fileVersions.put(artifact.getFile().getAbsoluteFile(), version);
        }
        URL[] urls = configuration.getFiles().stream().map(EmitterCatalog::url).toArray(URL[]::new);
        URLClassLoader loader = new URLClassLoader(urls, EmitterCatalog.class.getClassLoader());
        EmitterCatalog catalog = new EmitterCatalog(loader, GenerateContractSourcesAction.emitters(loader));
        for (Emitter emitter : catalog.emitters) {
            catalog.versions.put(emitter.id(), fileVersions.getOrDefault(location(emitter), "unspecified"));
        }
        return catalog;
    }

    /**
     * The emitters, in id order.
     *
     * @return the emitters
     */
    List<Emitter> emitters() {
        return emitters;
    }

    /**
     * An emitter's version.
     *
     * @param id the emitter's id
     * @return its library's version, or {@code unspecified} for a library given as a file
     */
    String version(String id) {
        return versions.getOrDefault(id, "unspecified");
    }

    /**
     * What an emitter's generated code needs.
     *
     * @param id the emitter's id
     * @return the dependencies
     */
    List<ManagedDependency> dependencies(String id) {
        return emitters.stream().filter(e -> e.id().equals(id)).findFirst()
                .map(e -> List.copyOf(e.dependencies())).orElse(List.of());
    }

    private static File location(Emitter emitter) {
        try {
            var source = emitter.getClass().getProtectionDomain().getCodeSource();
            return source == null ? null : new File(source.getLocation().toURI()).getAbsoluteFile();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static URL url(File file) {
        try {
            return file.toURI().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        try {
            loader.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
