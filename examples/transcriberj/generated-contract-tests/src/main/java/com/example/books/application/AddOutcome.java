package com.example.books.application;

import com.example.books.domain.Book;

/**
 * What adding a book came to.
 */
public sealed interface AddOutcome {

    /**
     * The book was added.
     *
     * @param book the book, with its new identifier
     */
    record Added(Book book) implements AddOutcome {
    }

    /**
     * A book with this ISBN is in the catalogue already.
     *
     * @param isbn the ISBN
     */
    record IsbnTaken(String isbn) implements AddOutcome {
    }
}
