package com.arc_e_tect.gradle.apionly.transcriberj.fixtures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.List;

/** Records {@code expected} for every event case from the hand-written classes. */
public final class HandwrittenEventPayloadRecorder {

    private HandwrittenEventPayloadRecorder() {
    }

    public static void main(String[] arguments) {
        ObjectNode document = EventPayloads.read();
        List<EventPayloads.Case> cases = EventPayloads.cases(document);
        int i = 0;
        for (JsonNode node : document.withArray("cases")) {
            ((ObjectNode) node).put("expected", cases.get(i++).handwritten());
        }
        EventPayloads.write(document, Path.of(arguments[0]));
    }
}
