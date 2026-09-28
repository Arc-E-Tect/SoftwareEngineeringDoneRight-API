package com.example.books.domain;

import java.util.UUID;

/**
 * A book in the catalogue.
 *
 * @param id       the identifier the catalogue gave it
 * @param isbn     its ISBN-13, which no other book in the catalogue has
 * @param title    its title
 * @param subtitle its subtitle, or null when it has none
 * @param pages    its number of pages
 * @param format   how it is published
 */
public record Book(UUID id, String isbn, String title, String subtitle, int pages, BookFormat format) {
}
