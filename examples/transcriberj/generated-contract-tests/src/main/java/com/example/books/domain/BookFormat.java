package com.example.books.domain;

import java.util.Arrays;
import java.util.Locale;

/**
 * How a book is published.
 */
public enum BookFormat {
    HARDCOVER, PAPERBACK, EBOOK;

    /**
     * The format's code, as the API writes it.
     *
     * @return the name in lower case
     */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The format with a code.
     *
     * @param code the code, as {@link #code()} writes it
     * @return the format
     * @throws IllegalArgumentException when no format has that code
     */
    public static BookFormat fromCode(String code) {
        return Arrays.stream(values()).filter(format -> format.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No book format has the code " + code));
    }
}
