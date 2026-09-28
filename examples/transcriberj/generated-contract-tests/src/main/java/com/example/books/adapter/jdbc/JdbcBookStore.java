package com.example.books.adapter.jdbc;

import com.example.books.application.BookStore;
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

    private static final RowMapper<Book> BOOK = (row, number) -> new Book(row.getObject("id", UUID.class),
            row.getString("isbn"), row.getString("title"), row.getString("subtitle"), row.getInt("pages"),
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
        columns.put("pages", book.pages());
        columns.put("format", book.format().code());
        try {
            jdbc.update("INSERT INTO book (id, isbn, title, subtitle, pages, format)"
                    + " VALUES (:id, :isbn, :title, :subtitle, :pages, :format)", columns);
            return true;
        } catch (DuplicateKeyException isbnTaken) {
            return false;
        }
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
}
