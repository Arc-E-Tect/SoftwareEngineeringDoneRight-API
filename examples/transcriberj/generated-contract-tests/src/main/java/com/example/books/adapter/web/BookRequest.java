package com.example.books.adapter.web;

import com.example.books.domain.BookFormat;
import com.example.books.domain.NewBook;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /books}, as it arrives, with the contract's constraints on it.
 *
 * <p>A class with setters rather than a record: Jackson calls a setter only for a member the
 * body has, so an absent {@code subtitle} and a {@code "subtitle": null} stay apart -- the
 * first is allowed, and the second is refused, as {@link JsonConfiguration} says. Bean
 * Validation reads the fields; nothing needs a getter.
 */
public class BookRequest {

    @NotNull
    @Pattern(regexp = "^97[89][0-9]{10}$")
    private String isbn;

    @NotNull
    @Size(min = 1, max = 120)
    private String title;

    @Size(min = 1, max = 120)
    private String subtitle;

    @NotNull
    @Min(1)
    @Max(5000)
    private Integer pages;

    @NotNull
    @Pattern(regexp = "^(hardcover|paperback|ebook)$")
    private String format;

    /**
     * The book this request asks for. Only called once the request is valid.
     *
     * @return the book
     */
    NewBook toNewBook() {
        return new NewBook(isbn, title, subtitle, pages, BookFormat.fromCode(format));
    }


    public void setIsbn(String isbn) {
        this.isbn = isbn;
    }


    public void setTitle(String title) {
        this.title = title;
    }


    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }


    public void setPages(Integer pages) {
        this.pages = pages;
    }


    public void setFormat(String format) {
        this.format = format;
    }
}
