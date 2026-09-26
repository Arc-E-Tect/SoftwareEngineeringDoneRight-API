package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.model.Construct;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Finding;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Treatment;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Emitter;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.EmitterContext;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.GeneratedClass;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.ManagedDependency;

import java.util.List;
import java.util.Set;

/**
 * The trivial emitter that exercises the SPI: for every core class with a body, a
 * companion that counts its fields, and one degraded method.
 */
public class TestEmitter implements Emitter {

    @Override
    public String id() {
        return "counting";
    }

    @Override
    public List<ManagedDependency> dependencies() {
        return List.of(new ManagedDependency("org.apiguardian", "apiguardian-api", "1.1.0", "1.1.2"));
    }

    @Override
    public Set<Construct> represents() {
        return Set.of();
    }

    @Override
    public void emit(EmitterContext context) {
        String pkg = context.settings().basePackage() + "." + id();
        java.util.Map<String, String> options = context.settings().emitterOptions(id());
        if (!options.isEmpty()) {
            StringBuilder written = new StringBuilder();
            options.forEach((name, value) -> written.append(name).append('=').append(value).append('\n'));
            context.writeResource(id() + "/options.properties", written.toString());
        }
        for (GeneratedClass generated : context.names().all()) {
            if (!generated.bodyShaped() || !generated.exposed()) continue;
            String name = generated.simpleName() + "Count";
            String degraded = context.degraded(name, "unsupported()", new Finding(generated.key(),
                    Construct.UNMODELLED_KEYWORD, Treatment.UNDECIDED, "for the test"));
            context.writeJava(pkg, name, "package " + pkg + ";\n\n"
                    + "public final class " + name + " {\n"
                    + "    private " + name + "() {\n    }\n\n"
                    + "    public static int count() {\n"
                    + "        return " + context.settings().basePackage() + "." + generated.simpleName()
                    + ".fields(\"\").size();\n"
                    + "    }\n\n"
                    + "    public static void unsupported() {\n"
                    + "        " + degraded + "\n"
                    + "    }\n"
                    + "}\n");
        }
    }
}
