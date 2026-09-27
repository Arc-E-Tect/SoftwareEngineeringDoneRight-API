package com.example.books.adapter.web;

import com.example.books.domain.BookPage;

import java.util.List;

/**
 * A page of the catalogue, as the API writes it.
 *
 * @param items      the books on it
 * @param page       which page it is
 * @param size       how many books a page holds
 * @param totalItems how many books the catalogue holds
 */
public record BookPageResource(List<BookResource> items, int page, int size, long totalItems) {

    static BookPageResource of(BookPage page) {
        return new BookPageResource(page.items().stream().map(BookResource::of).toList(), page.page(), page.size(),
                page.totalItems());
    }
}
