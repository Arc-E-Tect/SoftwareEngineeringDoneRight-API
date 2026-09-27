package com.example.books.application;

import com.example.books.domain.BookPage;
import com.example.books.domain.NewBook;
import com.example.books.domain.Book;

import java.util.Optional;
import java.util.UUID;

/**
 * The catalogue: the port the HTTP adapter calls. Everything it is given is valid; the
 * adapter has checked it against the contract.
 */
public interface Catalogue {

    /**
     * Adds a book, unless one with its ISBN is in the catalogue already.
     *
     * @param newBook the book
     * @return the book as added, or that its ISBN is taken
     */
    AddOutcome add(NewBook newBook);

    /**
     * Finds a book.
     *
     * @param id its identifier
     * @return the book, or empty when none has that identifier
     */
    Optional<Book> find(UUID id);

    /**
     * One page of the catalogue, in ISBN order.
     *
     * @param page which page, the first being 0
     * @param size how many books a page holds
     * @return the page
     */
    BookPage list(int page, int size);
}
