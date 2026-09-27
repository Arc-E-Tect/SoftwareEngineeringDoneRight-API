package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.model.Construct;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Emitter;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.EmitterContext;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.ManagedDependency;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Output;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An emitter whose output its {@code mode} option selects: a comma-separated list of
 * {@code java}, {@code resources} and {@code files}, {@code java,resources} when absent. It
 * writes exactly what it declares, one of each.
 */
public class ModalEmitter implements Emitter {

    @Override
    public String id() {
        return "modal";
    }

    @Override
    public List<ManagedDependency> dependencies() {
        return List.of();
    }

    @Override
    public Set<Construct> represents() {
        return Set.of();
    }

    @Override
    public Set<Output> produces(Map<String, String> options) {
        Set<Output> out = EnumSet.noneOf(Output.class);
        for (String mode : options.getOrDefault("mode", "java,resources").split(",")) {
            if (!mode.isBlank()) out.add(Output.valueOf(mode.trim().toUpperCase(java.util.Locale.ROOT)));
        }
        return out;
    }

    @Override
    public void emit(EmitterContext context) {
        Set<Output> produces = produces(context.settings().emitterOptions(id()));
        String pkg = context.settings().basePackage() + "." + id();
        if (produces.contains(Output.JAVA)) {
            context.writeJava(pkg, "Modal", "package " + pkg + ";\n\n"
                    + "public final class Modal {\n"
                    + "    public static final String VERSION = " + context.settings().basePackage()
                    + ".ContractManifest.CONTRACT_VERSION;\n\n"
                    + "    private Modal() {\n    }\n}\n");
        }
        if (produces.contains(Output.RESOURCES)) {
            context.writeResource(id() + "/modal.properties", "contract=" + context.settings().contract() + "\n");
        }
        if (produces.contains(Output.FILES)) {
            context.writeFile("mappings/modal.json", "{\"contract\":\"" + context.settings().contract() + "\"}\n");
        }
    }
}
