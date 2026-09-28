package com.example.books.documentation;

import com.example.books.contract.BookRequestV1;
import com.example.books.contract.CreateBookOperation;
import com.example.books.contract.GetBookOperation;
import com.example.books.contract.ListBooksOperation;
import com.example.books.contract.restdocs.BookPageV1Docs;
import com.example.books.contract.restdocs.BookRequestV1Docs;
import com.example.books.contract.restdocs.BookV1Docs;
import com.example.books.contract.restdocs.ProblemV1Docs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.UUID;

import static org.springframework.restdocs.operation.preprocess.Preprocessors.modifyHeaders;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.webtestclient.WebTestClientRestDocumentation.document;
import static org.springframework.restdocs.webtestclient.WebTestClientRestDocumentation.documentationConfiguration;

/**
 * The published documentation of the books API: one test per request and response worth
 * showing, with values a reader recognises, written here on purpose -- never taken from a
 * generated case, whose values are the smallest valid ones.
 *
 * <p>The requests are built with the generated {@code body(...)} and {@code PATH}, and
 * {@code document(...)} checks them and their responses against the generated descriptors, so
 * the documentation cannot drift from the contract. They run against the real service, with the
 * state each needs arranged in the database as the real contract tests arrange it, so every
 * response shown is one the service really gave.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(RestDocumentationExtension.class)
class BooksDocumentationTest {

    private static final String DESIGN_PATTERNS = "9780201633610";
    private static final String REFACTORING_ID = "5d1c7e2a-3b4f-4a6d-8e9c-0f1a2b3c4d5e";
    private static final String REFACTORING = "9780134757599";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient database;

    private WebTestClient client;

    @BeforeEach
    void bindClientToTheService(RestDocumentationContextProvider restDocumentation) {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .filter(documentationConfiguration(restDocumentation)
                        .operationPreprocessors()
                        .withRequestDefaults(prettyPrint())
                        // What the server's transport adds is not the API's to document.
                        .withResponseDefaults(prettyPrint(),
                                modifyHeaders().remove("Date").remove("Connection").remove("Transfer-Encoding")))
                .build();
    }

    @Test
    void addingABook() {
        deleteBookWithIsbn(DESIGN_PATTERNS);

        client.post().uri(CreateBookOperation.PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(BookRequestV1.body(DESIGN_PATTERNS, "Design Patterns", 395, "hardcover",
                        "Elements of Reusable Object-Oriented Software"))
                .exchange()
                .expectStatus().isEqualTo(CreateBookOperation.STATUS_201)
                .expectBody()
                .consumeWith(document("create-book", BookRequestV1Docs.requestFields(), BookV1Docs.responseFields()));
    }

    @Test
    void addingABookWithAnIsbn10() {
        client.post().uri(CreateBookOperation.PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                .bodyValue(BookRequestV1.body("0201633612", "Design Patterns", 395, "hardcover"))
                .exchange()
                .expectStatus().isEqualTo(CreateBookOperation.STATUS_400)
                .expectBody()
                .consumeWith(document("create-book-invalid", BookRequestV1Docs.requestFields(),
                        ProblemV1Docs.responseFields()));
    }

    @Test
    void readingABook() {
        ensureBookExists(REFACTORING_ID, REFACTORING, "Refactoring", "Improving the Design of Existing Code", 448,
                "hardcover");

        client.get().uri(GetBookOperation.PATH, REFACTORING_ID)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isEqualTo(GetBookOperation.STATUS_200)
                .expectBody()
                .consumeWith(document("get-book", BookV1Docs.responseFields()));
    }

    @Test
    void readingABookThatIsNotInTheCatalogue() {
        String unknown = "9b2e4c6a-1d3f-4e5a-8b7c-6d5e4f3a2b1c";
        deleteBook(unknown);

        client.get().uri(GetBookOperation.PATH, unknown)
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                .exchange()
                .expectStatus().isEqualTo(GetBookOperation.STATUS_404)
                .expectBody()
                .consumeWith(document("get-book-not-found", ProblemV1Docs.responseFields()));
    }

    @Test
    void listingTheCatalogue() {
        ensureBookExists(REFACTORING_ID, REFACTORING, "Refactoring", "Improving the Design of Existing Code", 448,
                "hardcover");
        ensureBookExists("7a8b9c0d-1e2f-4a3b-9c4d-5e6f7a8b9c0d", "9780132350884", "Clean Code", null, 464,
                "paperback");

        client.get().uri(ListBooksOperation.PATH + "?page=0&size=2")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isEqualTo(ListBooksOperation.STATUS_200)
                .expectBody()
                .consumeWith(document("list-books", BookPageV1Docs.responseFields()));
    }

    private void ensureBookExists(String id, String isbn, String title, String subtitle, int pages, String format) {
        deleteBook(id);
        deleteBookWithIsbn(isbn);
        database.sql("INSERT INTO book (id, isbn, title, subtitle, pages, format)"
                        + " VALUES (:id, :isbn, :title, :subtitle, :pages, :format)")
                .param("id", UUID.fromString(id))
                .param("isbn", isbn)
                .param("title", title)
                .param("subtitle", subtitle)
                .param("pages", pages)
                .param("format", format)
                .update();
    }

    private void deleteBook(String id) {
        database.sql("DELETE FROM book WHERE id = :id").param("id", UUID.fromString(id)).update();
    }

    private void deleteBookWithIsbn(String isbn) {
        database.sql("DELETE FROM book WHERE isbn = :isbn").param("isbn", isbn).update();
    }
}
