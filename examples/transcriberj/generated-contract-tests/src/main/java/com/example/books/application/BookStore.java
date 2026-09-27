package com.example.books.application;

import com.example.books.domain.Book;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Where the catalogue keeps its books: the port the persistence adapter implements.
 */
public interface BookStore {

    /**
     * Stores a new book.
     *
     * @param book the book
     * @return false, storing nothing, when a book with its ISBN is stored already
     */
    boolean insert(Book book);

    /**
     * Finds a book.
     *
     * @param id its identifier
     * @return the book, or empty
     */
    Optional<Book> findById(UUID id);

    /**
     * Some of the books, in ISBN order.
     *
     * @param offset how many to skip
     * @param limit  how many at most
     * @return the books
     */
    List<Book> findPage(int offset, int limit);

    /**
     * How many books are stored.
     *
     * @return the number
     */
    long count();
}
