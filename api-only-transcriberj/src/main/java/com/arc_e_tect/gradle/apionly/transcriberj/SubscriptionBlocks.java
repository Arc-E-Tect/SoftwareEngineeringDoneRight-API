package com.arc_e_tect.gradle.apionly.transcriberj;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The properties a {@code subscription('<contract>') { }} block of an existing build lacks, added
 * with their defaults: what the shared DSL updater cannot do, as it only sees the extension's own
 * properties. {@code emitter(...)} blocks inside are left alone.
 */
final class SubscriptionBlocks {

    private static final Pattern BLOCK = Pattern.compile("\\bapiOnlyTranscriberJ\\s*\\{");
    private static final Pattern SUBSCRIPTION =
            Pattern.compile("(?m)^([ \\t]*)subscription\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)\\s*\\{[ \\t]*\\n");

    private SubscriptionBlocks() {
    }

    /**
     * What was added, and the build file it was added to.
     *
     * @param source  the build file's text
     * @param updated the contracts whose blocks gained a property
     */
    record Outcome(String source, List<String> updated) {
    }

    /**
     * Adds {@code schemaClasses} to every subscription block that does not set it.
     *
     * @param source   the build file's text
     * @param comments whether to write a comment above each added property
     * @return the text, and which subscriptions changed
     */
    static Outcome addMissing(String source, boolean comments) {
        Matcher extension = BLOCK.matcher(source);
        if (!extension.find()) return new Outcome(source, List.of());
        int start = extension.end();
        int end = closing(source, start - 1);
        StringBuilder out = new StringBuilder(source.substring(0, start));
        List<String> updated = new ArrayList<>();
        String body = source.substring(start, end);
        Matcher subscription = SUBSCRIPTION.matcher(body);
        int copied = 0;
        while (subscription.find()) {
            int open = subscription.end() - 1;
            while (body.charAt(open) != '{') open--;
            int close = closing(body, open);
            if (body.substring(open, close).contains("schemaClasses")) continue;
            String indent = subscription.group(1) + "    ";
            out.append(body, copied, subscription.end());
            if (comments) {
                out.append(indent).append("// How the schema classes reach the source sets: 'perSourceSet', each ")
                        .append("compiling them, or 'shared', one source set\n")
                        .append(indent).append("// compiling them for the others. Default: 'perSourceSet'\n");
            }
            out.append(indent).append("schemaClasses = '").append(EmitterPlan.PER_SOURCE_SET).append("'\n");
            copied = subscription.end();
            updated.add(subscription.group(2));
        }
        out.append(body.substring(copied)).append(source.substring(end));
        return new Outcome(out.toString(), List.copyOf(updated));
    }

    /** The index of the brace closing the one at {@code open}, skipping strings and comments. */
    static int closing(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                while (i < text.length() && text.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int endComment = text.indexOf("*/", i + 2);
                i = endComment < 0 ? text.length() : endComment + 1;
            } else if (c == '\'' || c == '"') {
                i++;
                while (i < text.length() && text.charAt(i) != c) {
                    if (text.charAt(i) == '\\') i++;
                    i++;
                }
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return text.length();
    }
}
