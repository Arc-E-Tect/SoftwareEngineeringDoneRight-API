package com.example.books.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests of {@link NewBook}.
 */
class NewBookTest {

    @Test
    void aNewBookBecomesABookWithTheIdentifierItIsGiven() {
        UUID id = UUID.fromString("3f2b9c1e-8d4a-4b6e-9f10-2a7c5d8e1b34");
        NewBook newBook = new NewBook("9780201633610", "Design Patterns",
                "Elements of Reusable Object-Oriented Software", 395, BookFormat.HARDCOVER);

        assertThat(newBook.withId(id)).isEqualTo(new Book(id, "9780201633610", "Design Patterns",
                "Elements of Reusable Object-Oriented Software", 395, BookFormat.HARDCOVER));
    }
}
