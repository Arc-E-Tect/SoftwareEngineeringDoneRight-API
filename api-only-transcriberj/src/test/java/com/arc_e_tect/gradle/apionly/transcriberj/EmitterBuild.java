package com.arc_e_tect.gradle.apionly.transcriberj;

import com.arc_e_tect.gradle.apionly.subscriber.Lockfile;
import com.arc_e_tect.gradle.apionly.transcriberj.core.ModalEmitter;
import com.arc_e_tect.gradle.apionly.transcriberj.core.TestEmitter;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Emitter;
import org.gradle.testkit.runner.GradleRunner;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A small consumer build for the emitter-configuration tests: the reference user-account
 * contract published to a file channel, a jar holding the two test emitters, and three source
 * sets -- {@code test}, {@code contractTest} and {@code systemTest} -- each with a class that uses
 * the generated code and prints what it sees.
 */
final class EmitterBuild {

    static final Path CONTRACT = Path.of(System.getProperty("transcriberj.referenceApi"),
            "user-account/openapi.yaml");

    final Path dir;
    private final List<Class<? extends Emitter>> emitters;

    EmitterBuild(Path dir) {
        this(dir, List.of(TestEmitter.class, ModalEmitter.class));
    }

    @SafeVarargs
    EmitterBuild(Path dir, Class<? extends Emitter>... emitters) {
        this(dir, List.of(emitters));
    }

    private EmitterBuild(Path dir, List<Class<? extends Emitter>> emitters) {
        this.dir = dir;
        this.emitters = List.copyOf(emitters);
    }

    /** The whole build: settings, contract, emitters, sources and a build script around the subscription's body. */
    EmitterBuild write(String subscriptionBody) throws Exception {
        return write(subscriptionBody, "");
    }

    EmitterBuild write(String subscriptionBody, String extra) throws Exception {
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'consumer'\n");
        if (!Files.exists(dir.resolve("channel"))) publish("1.0.0");
        if (!Files.exists(dir.resolve("emitters.jar"))) {
            emittersJar(dir.resolve("emitters.jar"), emitters.toArray(Class[]::new));
        }
        Files.writeString(dir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'com.arc-e-tect.api-only-transcriberj'
                }

                repositories {
                    mavenCentral()
                }

                sourceSets {
                    contractTest
                    systemTest
                }

                dependencies {
                    transcriberjEmitters files('emitters.jar')
                }

                apiOnlySubscriber {
                    channel {
                        type = 'file'
                        directory = file('channel').path
                    }
                    subscribe('user-account') {
                        apiContractVersion = findProperty('contractVersion') ?: '1.0.0'
                    }
                }

                apiOnlyTranscriberJ {
                    subscription('user-account') {
                        basePackage = 'com.example.contract'
                %s
                    }
                }

                ['test', 'contractTest', 'systemTest'].each { name ->
                    tasks.register("use${name.capitalize()}", JavaExec) {
                        classpath = sourceSets[name].runtimeClasspath
                        mainClass = "Use${name.capitalize()}"
                    }
                }
                tasks.register('runHandwritten', JavaExec) {
                    classpath = sourceSets.contractTest.runtimeClasspath
                    mainClass = 'com.example.contract.Handwritten'
                }
                %s
                """.formatted(subscriptionBody.indent(8).stripTrailing(), extra));
        for (String set : List.of("test", "contractTest", "systemTest")) {
            String name = "Use" + Character.toUpperCase(set.charAt(0)) + set.substring(1);
            Path source = Files.createDirectories(dir.resolve("src/" + set + "/java"));
            Files.writeString(source.resolve(name + ".java"), """
                    public class %s {
                        public static void main(String[] args) {
                            System.out.println("%s VERSION " + com.example.contract.ContractManifest.CONTRACT_VERSION);
                        }
                    }
                    """.formatted(name, set.toUpperCase(java.util.Locale.ROOT)));
        }
        return this;
    }

    /** Adds a class to a source set's Java sources. */
    EmitterBuild source(String set, String path, String content) throws IOException {
        Path file = dir.resolve("src/" + set + "/java/" + path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return this;
    }

    /** Publishes the reference contract to the file channel at a version, as the Publisher ships one. */
    void publish(String version) throws Exception {
        String document = Files.readString(CONTRACT).replace("version: 1.0.0", "version: " + version);
        Path stage = Files.createTempDirectory(dir, "stage");
        Files.writeString(stage.resolve("openapi.yaml"), document);
        Files.writeString(stage.resolve("manifest.json"),
                "{\"schemaVersion\":1,\"target\":\"user-account\",\"version\":\"%s\",\"files\":[{\"path\":\"openapi.yaml\",\"sha256\":\"%s\"}]}"
                        .formatted(version, Lockfile.sha256(stage.resolve("openapi.yaml").toFile())));
        Path archives = Files.createDirectories(dir.resolve("channel/user-account/" + version));
        Process tar = new ProcessBuilder("tar", "-czf",
                archives.resolve("user-account-" + version + ".tgz").toString(), "manifest.json", "openapi.yaml")
                .directory(stage.toFile()).inheritIO().start();
        assertThat(tar.waitFor()).isZero();
    }

    /** A jar holding emitter classes, each registered as a service. */
    @SafeVarargs
    static void emittersJar(Path jar, Class<? extends Emitter>... emitters) throws IOException, URISyntaxException {
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out)) {
            StringBuilder services = new StringBuilder();
            for (Class<? extends Emitter> emitter : emitters) {
                Path classes = Path.of(emitter.getProtectionDomain().getCodeSource().getLocation().toURI());
                String entry = emitter.getName().replace('.', '/') + ".class";
                jarOut.putNextEntry(new JarEntry(entry));
                jarOut.write(Files.readAllBytes(classes.resolve(entry)));
                jarOut.closeEntry();
                services.append(emitter.getName()).append('\n');
            }
            jarOut.putNextEntry(new JarEntry("META-INF/services/" + Emitter.class.getName()));
            jarOut.write(services.toString().getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
        }
    }

    GradleRunner runner(String... arguments) {
        List<String> args = new ArrayList<>(List.of(arguments));
        args.add("--stacktrace");
        return GradleRunner.create().withProjectDir(dir.toFile()).withPluginClasspath()
                .withArguments(args).forwardOutput();
    }

    Path file(String path) {
        return dir.resolve(path);
    }
}
