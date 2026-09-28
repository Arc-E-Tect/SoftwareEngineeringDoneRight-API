package com.example.books.adapter.jdbc;

import com.example.books.domain.Book;
import com.example.books.domain.BookFormat;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Component tests of the persistence adapter: the adapter alone, its JDBC operations mocked,
 * no database. That the SQL is right for the real schema, the contract tests show: they run
 * the service against the real database.
 */
class JdbcBookStoreTest {

    private static final UUID ID = UUID.fromString("3f2b9c1e-8d4a-4b6e-9f10-2a7c5d8e1b34");
    private static final Book BOOK = new Book(ID, "9780201633610", "Design Patterns",
            "Elements of Reusable Object-Oriented Software", 395, BookFormat.HARDCOVER);

    private final NamedParameterJdbcOperations jdbc = mock(NamedParameterJdbcOperations.class);
    private final JdbcBookStore store = new JdbcBookStore(jdbc);

    @Test
    void aBookIsInsertedWithEveryColumn() {
        assertThat(store.insert(BOOK)).isTrue();

        verify(jdbc).update(anyString(), eq(Map.of("id", ID, "isbn", "9780201633610", "title", "Design Patterns",
                "subtitle", "Elements of Reusable Object-Oriented Software", "pages", 395, "format", "hardcover")));
    }

    @Test
    void aBookWithoutASubtitleIsInsertedWithANullSubtitle() {
        store.insert(new Book(ID, "9780201633610", "Design Patterns", null, 395, BookFormat.HARDCOVER));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> parameters = ArgumentCaptor.forClass(Map.class);
        verify(jdbc).update(anyString(), parameters.capture());
        assertThat(parameters.getValue()).containsEntry("subtitle", null);
    }

    @Test
    void aBookWhoseIsbnIsStoredAlreadyIsNotInserted() {
        when(jdbc.update(anyString(), anyMap())).thenThrow(new DuplicateKeyException("isbn"));

        assertThat(store.insert(BOOK)).isFalse();
    }

    @Test
    void aBookIsFoundByItsIdentifier() {
        when(jdbc.query(anyString(), eq(Map.of("id", ID)), any())).thenReturn(List.of(BOOK));

        assertThat(store.findById(ID)).contains(BOOK);
    }

    @Test
    void aPageIsReadAtItsOffset() {
        when(jdbc.query(anyString(), eq(Map.of("offset", 40, "limit", 20)), any())).thenReturn(List.of(BOOK));

        assertThat(store.findPage(40, 20)).containsExactly(BOOK);
    }

    @Test
    void theBooksAreCounted() {
        when(jdbc.queryForObject(anyString(), eq(Map.of()), eq(Long.class))).thenReturn(41L);

        assertThat(store.count()).isEqualTo(41L);
    }

    @Test
    void aRowBecomesABook() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("id", UUID.class)).thenReturn(ID);
        when(row.getString("isbn")).thenReturn("9780201633610");
        when(row.getString("title")).thenReturn("Design Patterns");
        when(row.getString("subtitle")).thenReturn("Elements of Reusable Object-Oriented Software");
        when(row.getInt("pages")).thenReturn(395);
        when(row.getString("format")).thenReturn("hardcover");

        assertThat(rowMapper().mapRow(row, 0)).isEqualTo(BOOK);
    }

    private RowMapper<Book> rowMapper() {
        store.findById(ID);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<RowMapper<Book>> mapper = ArgumentCaptor.forClass(RowMapper.class);
        verify(jdbc).query(anyString(), anyMap(), mapper.capture());
        return mapper.getValue();
    }

    private static <T> RowMapper<T> any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
