package com.example.books.application;

import com.example.books.domain.Book;
import com.example.books.domain.BookFormat;
import com.example.books.domain.BookPage;
import com.example.books.domain.NewBook;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Component tests of {@link CatalogueService}: the service alone, its store mocked.
 */
class CatalogueServiceTest {

    private static final UUID ID = UUID.fromString("3f2b9c1e-8d4a-4b6e-9f10-2a7c5d8e1b34");
    private static final NewBook NEW_BOOK = new NewBook("9780201633610", "Design Patterns", null, 395,
            BookFormat.HARDCOVER);
    private static final Book BOOK = NEW_BOOK.withId(ID);

    private final BookStore store = mock(BookStore.class);
    private final CatalogueService catalogue = new CatalogueService(store, () -> ID);

    @Test
    void aNewBookIsStoredUnderANewIdentifier() {
        when(store.insert(BOOK)).thenReturn(true);

        assertThat(catalogue.add(NEW_BOOK)).isEqualTo(new AddOutcome.Added(BOOK));
        verify(store).insert(BOOK);
    }

    @Test
    void aBookWhoseIsbnIsTakenIsNotAdded() {
        when(store.insert(BOOK)).thenReturn(false);

        assertThat(catalogue.add(NEW_BOOK)).isEqualTo(new AddOutcome.IsbnTaken("9780201633610"));
    }

    @Test
    void aBookIsFoundByItsIdentifier() {
        when(store.findById(ID)).thenReturn(Optional.of(BOOK));

        assertThat(catalogue.find(ID)).contains(BOOK);
    }

    @Test
    void aPageHoldsTheBooksAtItsOffsetAndTheCatalogueSize() {
        when(store.findPage(40, 20)).thenReturn(List.of(BOOK));
        when(store.count()).thenReturn(41L);

        assertThat(catalogue.list(2, 20)).isEqualTo(new BookPage(List.of(BOOK), 2, 20, 41L));
    }
}
