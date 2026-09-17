package com.arc_e_tect.gradle.apionly.transcriberj.fixtures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.List;

/** Records {@code expected} for every case from the hand-written classes. */
public final class HandwrittenBodyRecorder {

    private HandwrittenBodyRecorder() {
    }

    public static void main(String[] arguments) {
        ObjectNode document = BodyFixtures.read();
        List<BodyFixtures.Case> cases = BodyFixtures.cases(document);
        int i = 0;
        for (JsonNode node : document.withArray("cases")) {
            ((ObjectNode) node).put("expected", cases.get(i++).replay(BodyFixtures.HANDWRITTEN_PACKAGE));
        }
        BodyFixtures.write(document, Path.of(arguments[0]));
    }
}
