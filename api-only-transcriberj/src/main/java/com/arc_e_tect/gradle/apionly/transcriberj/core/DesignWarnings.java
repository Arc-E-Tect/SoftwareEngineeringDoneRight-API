package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.model.Construct;
import com.arc_e_tect.gradle.apionly.transcriberj.model.ContractModel;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Finding;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Schema;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.GeneratedClass;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Origin;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.Settings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Depth as a design smell. Three levels of recursion or nesting is more than a
 * contract usually means to have, so it is warned about whatever the project
 * allows recursionDepth to be.
 */
final class DesignWarnings {

    static final int LIMIT = 3;

    private DesignWarnings() {
    }

    static void check(ContractModel model, Settings settings, Shapes shapes, CoreClassNames names,
                      GenerationReport report) {
        if (settings.recursionDepth() > LIMIT) {
            report.warn("recursionDepth is " + settings.recursionDepth() + "; following a recursion more than "
                    + LIMIT + " times suggests the contract nests deeper than a test should need to");
        }
        for (Finding finding : model.findings()) {
            if (finding.construct() != Construct.RECURSIVE_REF) continue;
            int components = finding.detail().split(" -> ").length - 1;
            if (components >= LIMIT) {
                report.warn(finding.location() + ": a recursive reference through " + components
                        + " components (" + finding.detail() + "); a cycle this long is easily not noticed");
            }
        }
        for (GeneratedClass generated : names.all()) {
            if (!generated.exposed() || !generated.bodyShaped()) continue;
            Set<String> visiting = new HashSet<>();
            if (generated.origin() == Origin.SCHEMA) visiting.add(generated.key());
            List<String> deepest = deepest(shapes, generated.schema(), visiting);
            if (deepest.size() >= LIMIT) {
                report.warn(generated.simpleName() + ": its body nests objects " + deepest.size()
                        + " levels deep, at " + String.join(".", deepest)
                        + "; a body this deep is hard to test and easily designed by accident");
            }
        }
    }

    /** The longest chain of nested objects below an object, as property names; recursion is not followed. */
    private static List<String> deepest(Shapes shapes, Schema object, Set<String> visiting) {
        List<String> best = List.of();
        List<Shapes.Property> properties;
        try {
            properties = shapes.object(object, "").properties();
        } catch (Shapes.Unrepresentable e) {
            return best;
        }
        for (Shapes.Property p : properties) {
            String name = p.name();
            Schema target = p.schema();
            String ref = reference(target);
            if (ref != null) target = shapes.component(ref).orElseThrow();
            if (shapes.isArray(target)) {
                if (target.items() == null) continue;
                name += "[]";
                target = target.items();
                ref = reference(target);
                if (ref != null) target = shapes.component(ref).orElseThrow();
            }
            if (!shapes.isObject(target) || (ref != null && !visiting.add(ref))) continue;
            List<String> below = deepest(shapes, target, visiting);
            if (ref != null) visiting.remove(ref);
            if (below.size() + 1 > best.size()) {
                List<String> chain = new ArrayList<>();
                chain.add(name);
                chain.addAll(below);
                best = chain;
            }
        }
        return best;
    }

    private static String reference(Schema schema) {
        return schema.ref() != null && schema.types() == null && schema.properties() == null ? schema.ref() : null;
    }
}
