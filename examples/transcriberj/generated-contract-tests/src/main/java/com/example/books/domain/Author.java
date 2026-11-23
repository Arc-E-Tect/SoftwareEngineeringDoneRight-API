package com.example.books.domain;

/**
 * Who wrote a book: a person or an organisation.
 */
public sealed interface Author {

    /**
     * A person who wrote a book.
     *
     * @param name their name
     */
    record Person(String name) implements Author {
    }

    /**
     * An organisation that wrote a book, such as a standards body or a publisher's editorial team.
     *
     * @param name its name
     */
    record Organisation(String name) implements Author {
    }
}
