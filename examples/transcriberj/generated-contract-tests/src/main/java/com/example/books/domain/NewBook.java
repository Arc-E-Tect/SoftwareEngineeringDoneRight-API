package com.example.books.domain;

import java.util.UUID;

/**
 * A book about to be added to the catalogue, which has not given it an identifier yet.
 *
 * @param isbn     its ISBN-13
 * @param title    its title
 * @param subtitle its subtitle, or null when it has none
 * @param pages    its number of pages
 * @param format   how it is published
 */
public record NewBook(String isbn, String title, String subtitle, int pages, BookFormat format) {

    /**
     * This book, under an identifier.
     *
     * @param id the identifier
     * @return the book
     */
    public Book withId(UUID id) {
        return new Book(id, isbn, title, subtitle, pages, format);
    }
}
