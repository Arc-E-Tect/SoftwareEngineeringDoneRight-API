package com.arc_e_tect.gradle.apionly.transcriberj.core;

import com.arc_e_tect.gradle.apionly.transcriberj.model.ContractModel;
import com.arc_e_tect.gradle.apionly.transcriberj.model.MediaType;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Operation;
import com.arc_e_tect.gradle.apionly.transcriberj.model.Response;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.GeneratedClass;
import com.arc_e_tect.gradle.apionly.transcriberj.spi.ResponseExample;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The named examples every response declares, as emitters are given them: resolved as a request's
 * named examples are, checked against the response's schema, and the one a double answers with by
 * default marked -- the example the media type's {@code x-transcriberj-examples} names as
 * {@code default}, else the first.
 */
final class ResponseExamples {

    /** The one key {@code x-transcriberj-examples} takes on a response's media type. */
    static final String DEFAULT = "default";

    private final ContractModel model;
    private final ValidValues values;
    private final ValidRequests requests;

    ResponseExamples(ContractModel model, ValidValues values, ValidRequests requests) {
        this.model = model;
        this.values = values;
        this.requests = requests;
    }

    /**
     * Every response's examples, by the operation's JSON pointer and the response's status,
     * separated by a space; each response with content reported.
     *
     * @throws ValidValues.InvalidExample when an example, or the extension, is not one the contract may give
     */
    Map<String, List<ResponseExample>> derive(CoreClassNames names, GenerationReport report) {
        Map<String, List<ResponseExample>> out = new LinkedHashMap<>();
        for (Operation operation : model.operations()) {
            if (operation.responses() == null) continue;
            String location = CoreClassNames.operationLocation(operation);
            String className = names.operation(location).map(GeneratedClass::simpleName).orElse(location);
            for (Response response : operation.responses()) {
                if (response.content() == null || response.content().isEmpty()) continue;
                List<ResponseExample> examples = examples(response, written(location, response));
                out.put(key(location, response.status()), examples);
                report.responseExamples(className, response.status(),
                        examples.stream().filter(ResponseExample::isDefault).map(ResponseExample::name).findFirst().orElse(null),
                        examples.stream().map(ResponseExample::name).toList());
            }
        }
        return out;
    }

    static String key(String location, String status) {
        return location + " " + status;
    }

    /** Where a response is written: the component it refers to, or the operation itself. */
    private static String written(String location, Response response) {
        return response.reference() != null
                ? "/components/responses/" + Shapes.escape(response.reference().name())
                : location + "/responses/" + Shapes.escape(response.status());
    }

    private List<ResponseExample> examples(Response response, String responseAt) {
        record Found(String name, String contentType, Object value, String location) {
        }
        List<Found> found = new ArrayList<>();
        String named = null;
        for (MediaType mediaType : response.content()) {
            String mediaAt = responseAt + "/content/" + Shapes.escape(mediaType.contentType());
            Map<?, ?> declared = mediaType.other().get("examples") instanceof Map<?, ?> m ? m : Map.of();
            String pointer = defaultName(mediaType, mediaAt, declared);
            if (named == null) named = pointer;
            for (Map.Entry<?, ?> entry : declared.entrySet()) {
                String name = String.valueOf(entry.getKey());
                String at = mediaAt + "/examples/" + Shapes.escape(name);
                Object value = requests.exampleValue(entry.getValue(), at);
                if (mediaType.schema() != null) {
                    String schemaAt = mediaAt + "/schema";
                    if (!values.valid(value, mediaType.schema(), schemaAt)) {
                        throw new ValidValues.InvalidExample("The example " + name + " at " + at + " does not satisfy the "
                                + "schema at " + schemaAt + "; correct the example, or the schema.");
                    }
                }
                found.add(new Found(name, mediaType.contentType(), value, at));
            }
        }
        String chosen = named != null ? named : found.isEmpty() ? null : found.get(0).name();
        List<ResponseExample> out = new ArrayList<>();
        boolean marked = false;
        for (Found f : found) {
            boolean isDefault = !marked && f.name().equals(chosen);
            marked |= isDefault;
            out.add(new ResponseExample(f.name(), f.contentType(), ValueJson.write(f.value()) + "\n", f.location(),
                    isDefault));
        }
        return List.copyOf(out);
    }

    /**
     * The name the media type's {@code x-transcriberj-examples} gives as {@code default}, checked;
     * or null when it names none.
     */
    private static String defaultName(MediaType mediaType, String mediaAt, Map<?, ?> declared) {
        if (!mediaType.other().containsKey(ValidRequests.NAMED_EXAMPLES)) return null;
        String at = mediaAt + "/" + ValidRequests.NAMED_EXAMPLES;
        if (!(mediaType.other().get(ValidRequests.NAMED_EXAMPLES) instanceof Map<?, ?> pointer)) {
            throw new ValidValues.InvalidExample(at + " must map " + DEFAULT
                    + " to the name of one of the media type's examples.");
        }
        for (Map.Entry<?, ?> entry : pointer.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!DEFAULT.equals(key)) {
                throw new ValidValues.InvalidExample(at + "/" + Shapes.escape(key) + " names a key this generator "
                        + "does not know for a response; the only one is " + DEFAULT + ".");
            }
            if (!(entry.getValue() instanceof String)) {
                throw new ValidValues.InvalidExample(at + "/" + DEFAULT + " must be the name of one of the media "
                        + "type's examples.");
            }
        }
        String name = (String) pointer.get(DEFAULT);
        if (name != null && !declared.containsKey(name)) {
            throw new ValidValues.InvalidExample(at + "/" + DEFAULT + " names the example " + name + ", but the media "
                    + "type declares no example of that name.");
        }
        return name;
    }
}
