package com.example.books.adapter.jdbc;

import com.example.books.application.BookStore;
import com.example.books.domain.Author;
import com.example.books.domain.Book;
import com.example.books.domain.BookFormat;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The persistence adapter: the books in the {@code book} table, which {@code schema.sql}
 * creates.
 */
@Repository
public class JdbcBookStore implements BookStore {

    private static final String PERSON = "person";
    private static final String ORGANISATION = "organisation";

    private static final RowMapper<Book> BOOK = (row, number) -> new Book(row.getObject("id", UUID.class),
            row.getString("isbn"), row.getString("title"), row.getString("subtitle"),
            author(row.getString("author_kind"), row.getString("author_name")), row.getInt("pages"),
            BookFormat.fromCode(row.getString("format")));

    private final NamedParameterJdbcOperations jdbc;

    /**
     * A store over a database.
     *
     * @param jdbc the database
     */
    public JdbcBookStore(NamedParameterJdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(Book book) {
        Map<String, Object> columns = new HashMap<>();
        columns.put("id", book.id());
        columns.put("isbn", book.isbn());
        columns.put("title", book.title());
        columns.put("subtitle", book.subtitle());
        switch (book.author()) {
            case Author.Person person -> {
                columns.put("author_kind", PERSON);
                columns.put("author_name", person.name());
            }
            case Author.Organisation organisation -> {
                columns.put("author_kind", ORGANISATION);
                columns.put("author_name", organisation.name());
            }
        }
        columns.put("pages", book.pages());
        columns.put("format", book.format().code());
        try {
            jdbc.update("INSERT INTO book (id, isbn, title, subtitle, author_kind, author_name, pages, format)"
                    + " VALUES (:id, :isbn, :title, :subtitle, :author_kind, :author_name, :pages, :format)", columns);
            return true;
        } catch (DuplicateKeyException isbnTaken) {
            return false;
        }
    }

    @Override
    public boolean existsByIsbn(String isbn) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM book WHERE isbn = :isbn", Map.of("isbn", isbn), Long.class) > 0;
    }

    @Override
    public Optional<Book> findById(UUID id) {
        return jdbc.query("SELECT * FROM book WHERE id = :id", Map.of("id", id), BOOK).stream().findFirst();
    }

    @Override
    public List<Book> findPage(int offset, int limit) {
        return jdbc.query("SELECT * FROM book ORDER BY isbn LIMIT :limit OFFSET :offset",
                Map.of("offset", offset, "limit", limit), BOOK);
    }

    @Override
    public long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM book", Map.of(), Long.class);
    }

    /** The author a row holds; a kind this adapter never writes is not a book it stored. */
    private static Author author(String kind, String name) {
        return switch (kind) {
            case PERSON -> new Author.Person(name);
            case ORGANISATION -> new Author.Organisation(name);
            default -> throw new IllegalStateException("A book's author_kind is " + kind
                    + ", which is neither " + PERSON + " nor " + ORGANISATION + ".");
        };
    }
}
