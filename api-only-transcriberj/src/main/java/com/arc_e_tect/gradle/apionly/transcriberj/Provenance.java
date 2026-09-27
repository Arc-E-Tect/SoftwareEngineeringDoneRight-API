package com.arc_e_tect.gradle.apionly.transcriberj;

import java.util.Map;
import java.util.TreeMap;

/**
 * The {@code apionly-provenance.json} every archive of an emitter's files holds: what the files
 * were generated from, and by what. Its members are sorted, and it ends in a newline, so the same
 * inputs give the same bytes.
 */
final class Provenance {

    /** The file's name in the archive. */
    static final String FILE = "apionly-provenance.json";

    private Provenance() {
    }

    /**
     * The provenance's text.
     *
     * @param contract       the contract's name
     * @param version        its locked version
     * @param sha256         its document's hash, as the lockfile records it
     * @param transcriberJ   the TranscriberJ's version
     * @param emitter        the emitter's id
     * @param emitterVersion the emitter's version
     * @param options        the emitter's options
     * @return the JSON text
     */
    static String render(String contract, String version, String sha256, String transcriberJ, String emitter,
                         String emitterVersion, Map<String, String> options) {
        Map<String, Object> out = new TreeMap<>();
        out.put("contract", contract);
        out.put("contractSha256", sha256);
        out.put("contractVersion", version);
        out.put("emitter", emitter);
        out.put("emitterVersion", emitterVersion);
        out.put("options", new TreeMap<>(options));
        out.put("transcriberj", transcriberJ);
        return json(out) + "\n";
    }

    private static String json(Object value) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            map.forEach((k, v) -> {
                if (out.length() > 1) out.append(',');
                out.append(string((String) k)).append(':').append(json(v));
            });
            return out.append('}').toString();
        }
        return string((String) value);
    }

    private static String string(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
