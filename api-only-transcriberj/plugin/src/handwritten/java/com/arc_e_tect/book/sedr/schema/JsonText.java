package com.arc_e_tect.book.sedr.schema;

/** Minimal JSON string escaping for the hand-built bodies in this package. */
final class JsonText {

    private JsonText() {
    }

    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
