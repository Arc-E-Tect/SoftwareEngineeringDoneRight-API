package com.arc_e_tect.gradle.apionly.transcriberj.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Turning contract text into Java names and literals. */
final class JavaText {

    private static final char BACKSLASH = 92;
    private static final char QUOTE = 34;

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "var", "record", "yield", "sealed", "permits");

    private JavaText() {
    }

    /** The words of a name: split at anything not a letter or digit, and at lower-to-upper changes. */
    static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                flush(words, word);
                continue;
            }
            if (Character.isUpperCase(c) && !word.isEmpty()
                    && Character.isLowerCase(word.charAt(word.length() - 1))) {
                flush(words, word);
            }
            word.append(c);
        }
        flush(words, word);
        return words;
    }

    private static void flush(List<String> words, StringBuilder word) {
        if (!word.isEmpty()) {
            words.add(word.toString());
            word.setLength(0);
        }
    }

    /**
     * A type name. A name that is already a Java identifier keeps its own casing, so
     * {@code UserV1} stays {@code UserV1}; anything else is joined in PascalCase.
     */
    static String typeName(String text) {
        String name;
        if (isIdentifier(text)) {
            name = Character.toUpperCase(text.charAt(0)) + text.substring(1);
        } else {
            StringBuilder out = new StringBuilder();
            for (String word : words(text)) {
                out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
            name = out.toString();
        }
        return safe(name.isEmpty() ? "Unnamed" : name, "Type");
    }

    /** A parameter name, in camelCase. */
    static String variableName(String text) {
        StringBuilder out = new StringBuilder();
        for (String word : words(text)) {
            if (out.isEmpty()) {
                out.append(Character.toLowerCase(word.charAt(0))).append(word.substring(1));
            } else {
                out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        String name = out.isEmpty() ? "value" : out.toString();
        return KEYWORDS.contains(name) ? name + "Value" : safe(name, "value");
    }

    /** A constant name, in UPPER_SNAKE_CASE. */
    static String constantName(String text) {
        List<String> words = words(text);
        String name = words.isEmpty() ? "VALUE" : String.join("_", words).toUpperCase(Locale.ROOT);
        return safe(name, "VALUE");
    }

    private static String safe(String name, String prefix) {
        return Character.isJavaIdentifierStart(name.charAt(0)) ? name : prefix + name;
    }

    private static boolean isIdentifier(String text) {
        if (text.isEmpty() || !Character.isJavaIdentifierStart(text.charAt(0)) || text.charAt(0) == '$') {
            return false;
        }
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') return false;
        }
        return !KEYWORDS.contains(text);
    }

    /**
     * A Java string literal. Control characters are written as octal escapes, which,
     * unlike Unicode escapes, cannot end the literal early.
     */
    static String literal(String value) {
        StringBuilder out = new StringBuilder().append(QUOTE);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == QUOTE || c == BACKSLASH) {
                out.append(BACKSLASH).append(c);
            } else if (c == 10) {
                out.append(BACKSLASH).append('n');
            } else if (c == 13) {
                out.append(BACKSLASH).append('r');
            } else if (c < 0x20 || c == 0x7f) {
                out.append(BACKSLASH).append(String.format("%03o", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append(QUOTE).toString();
    }

    /** Text safe inside a Javadoc comment. */
    static String comment(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("*/", "*&#47;").replace("@", "&#64;")
                .replace(String.valueOf(BACKSLASH), "&#92;");
    }
}
