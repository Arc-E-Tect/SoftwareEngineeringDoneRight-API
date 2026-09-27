package com.example.books.domain;

import java.util.List;

/**
 * One page of the catalogue.
 *
 * @param items      the books on it, in ISBN order
 * @param page       which page it is, the first being 0
 * @param size       how many books a page holds
 * @param totalItems how many books the catalogue holds
 */
public record BookPage(List<Book> items, int page, int size, long totalItems) {
}
