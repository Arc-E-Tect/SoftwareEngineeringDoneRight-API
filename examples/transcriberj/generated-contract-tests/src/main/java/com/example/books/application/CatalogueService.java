package com.example.books.application;

import com.example.books.domain.Book;
import com.example.books.domain.BookPage;
import com.example.books.domain.NewBook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * The catalogue, over a {@link BookStore}.
 */
public class CatalogueService implements Catalogue {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogueService.class);

    private final BookStore store;
    private final Supplier<UUID> identifiers;
    private final Executor background;

    /**
     * A catalogue over a store.
     *
     * @param store       where the books are kept
     * @param identifiers where a new book's identifier comes from
     * @param background  where a book added later is added
     */
    public CatalogueService(BookStore store, Supplier<UUID> identifiers, Executor background) {
        this.store = store;
        this.identifiers = identifiers;
        this.background = background;
    }

    @Override
    public AddOutcome add(NewBook newBook) {
        Book book = newBook.withId(identifiers.get());
        return store.insert(book) ? new AddOutcome.Added(book) : new AddOutcome.IsbnTaken(book.isbn());
    }

    /**
     * {@inheritDoc}
     *
     * <p>An ISBN taken already is refused at once. One taken while the book waits is not:
     * the background then stores nothing, and says so in the log.
     */
    @Override
    public AddOutcome addLater(NewBook newBook) {
        if (store.existsByIsbn(newBook.isbn())) return new AddOutcome.IsbnTaken(newBook.isbn());
        Book book = newBook.withId(identifiers.get());
        background.execute(() -> {
            if (!store.insert(book)) {
                LOG.warn("Book {} was not added: its ISBN {} was taken while it waited", book.id(), book.isbn());
            }
        });
        return new AddOutcome.Accepted(book);
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
