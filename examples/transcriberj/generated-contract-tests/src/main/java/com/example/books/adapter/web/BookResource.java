package com.example.books.adapter.web;

import com.example.books.domain.Book;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A book, as the API writes it.
 *
 * @param id       the identifier
 * @param isbn     the ISBN-13
 * @param title    the title
 * @param subtitle the subtitle, left out when there is none
 * @param pages    the number of pages
 * @param format   the format's code
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BookResource(String id, String isbn, String title, String subtitle, int pages, String format) {

    static BookResource of(Book book) {
        return new BookResource(book.id().toString(), book.isbn(), book.title(), book.subtitle(), book.pages(),
                book.format().code());
    }
}
