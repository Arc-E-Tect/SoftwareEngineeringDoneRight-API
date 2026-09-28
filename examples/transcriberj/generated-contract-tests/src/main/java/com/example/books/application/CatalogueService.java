package com.example.books.application;

import com.example.books.domain.Book;
import com.example.books.domain.BookPage;
import com.example.books.domain.NewBook;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The catalogue, over a {@link BookStore}.
 */
public class CatalogueService implements Catalogue {

    private final BookStore store;
    private final Supplier<UUID> identifiers;

    /**
     * A catalogue over a store.
     *
     * @param store       where the books are kept
     * @param identifiers where a new book's identifier comes from
     */
    public CatalogueService(BookStore store, Supplier<UUID> identifiers) {
        this.store = store;
        this.identifiers = identifiers;
    }

    @Override
    public AddOutcome add(NewBook newBook) {
        Book book = newBook.withId(identifiers.get());
        return store.insert(book) ? new AddOutcome.Added(book) : new AddOutcome.IsbnTaken(book.isbn());
    }

    @Override
    public Optional<Book> find(UUID id) {
        return store.findById(id);
    }

    @Override
    public BookPage list(int page, int size) {
        return new BookPage(store.findPage(page * size, size), page, size, store.count());
    }
}
