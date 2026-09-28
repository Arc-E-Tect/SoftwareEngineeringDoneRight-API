package com.example.books.contracttest.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.springframework.restdocs.webtestclient.WebTestClientRestDocumentation.documentationConfiguration;

/**
 * The real target: the service, started by Spring Boot on a random port, over its real
 * database. The requests travel as real HTTP, so they meet the service exactly as a client's
 * do -- Spring Boot's JSON mapper included.
 *
 * <p>The fixtures below reach into the database, and nothing else does: a contract test
 * arranges state, then asks the service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(RestDocumentationExtension.class)
abstract class ServiceContractTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient database;

    private WebTestClient client;

    @BeforeEach
    void bindClientToTheService(RestDocumentationContextProvider restDocumentation) {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .filter(documentationConfiguration(restDocumentation))
                .build();
    }

    /**
     * The client, bound to the running service.
     *
     * @return the client
     */
    public WebTestClient client() {
        return client;
    }

    /**
     * The real target's by-product snippets go under {@code generated-service/}, so that the
     * double's, under {@code generated-stubs/}, do not overwrite them.
     *
     * @return the prefix
     */
    public String generatedDocumentationPrefix() {
        return "generated-service";
    }

    /**
     * Makes sure a book with this identifier and this ISBN exists: an upsert, replacing any
     * book with either.
     *
     * @param id   the identifier
     * @param isbn the ISBN
     */
    void ensureBookExists(String id, String isbn) {
        ensureNoBook(id);
        ensureNoBookWithIsbn(isbn);
        database.sql("INSERT INTO book (id, isbn, title, pages, format) VALUES (:id, :isbn, 'a', 1, 'hardcover')")
                .param("id", java.util.UUID.fromString(id))
                .param("isbn", isbn)
                .update();
    }

    /**
     * Makes sure no book has this identifier.
     *
     * @param id the identifier
     */
    void ensureNoBook(String id) {
        database.sql("DELETE FROM book WHERE id = :id").param("id", java.util.UUID.fromString(id)).update();
    }

    /**
     * Makes sure no book has this ISBN.
     *
     * @param isbn the ISBN
     */
    void ensureNoBookWithIsbn(String isbn) {
        database.sql("DELETE FROM book WHERE isbn = :isbn").param("isbn", isbn).update();
    }
}
