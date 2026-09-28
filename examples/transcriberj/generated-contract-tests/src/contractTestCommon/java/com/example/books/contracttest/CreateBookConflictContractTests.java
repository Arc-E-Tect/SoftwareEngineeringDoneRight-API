package com.example.books.contracttest;

import com.example.books.contract.BookRequestV1;
import com.example.books.contract.CreateBookOperation;
import com.example.books.contract.restdocs.ContractTestSupport;
import com.example.books.contract.restdocs.ProblemV1Docs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.springframework.restdocs.webtestclient.WebTestClientRestDocumentation.document;

/**
 * The contract test of the one response of {@code POST /books} the TranscriberJ cannot
 * derive: 409, when a book with the request's ISBN is in the catalogue already. The contract
 * does not say what provokes a 409, so the TranscriberJ reports it as not covered, and this
 * test is written by hand -- on the same pattern as the generated
 * {@code CreateBookContractTests}: a default test, a hook the target implements, the request
 * built from the generated classes, and the response checked with the generated descriptors.
 */
// tag::leaf[]
public interface CreateBookConflictContractTests {

    /**
     * The ISBN the test's book has. Not the one the generated cases send, so that a double's
     * stub for this request never answers theirs.
     */
    String TAKEN_ISBN = "9791234567896";

    /**
     * The request: a valid book, with {@link #TAKEN_ISBN}.
     */
    String REQUEST = BookRequestV1.body(TAKEN_ISBN, "a", 1, "hardcover");

    /**
     * The client, bound to the implementation under test and configured for Spring REST Docs.
     *
     * @return the client
     */
    WebTestClient client();

    /**
     * Puts the implementation under test in the state that makes 409 the right answer: a
     * book with this ISBN is in the catalogue.
     *
     * @param isbn the ISBN
     */
    void arrangeIsbnTaken(String isbn);

    /**
     * Where this test's by-product snippets go, as the generated tests'.
     *
     * @return the prefix
     */
    default String generatedDocumentationPrefix() {
        return ContractTestSupport.DEFAULT_PREFIX;
    }

    @Test
    @DisplayName("POST /books: a valid book whose ISBN is taken returns 409")
    default void isbnTaken_returns409() {
        arrangeIsbnTaken(TAKEN_ISBN);
        client().post()
                .uri(CreateBookOperation.PATH)
                .contentType(MediaType.parseMediaType(CreateBookOperation.REQUEST_CONTENT_TYPE))
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                .bodyValue(REQUEST)
                .exchange()
                .expectStatus().isEqualTo(CreateBookOperation.STATUS_409)
                .expectHeader().contentTypeCompatibleWith(CreateBookOperation.CONTENT_TYPE_409)
                .expectBody()
                .consumeWith(document(generatedDocumentationPrefix() + "/" + CreateBookOperation.OPERATION_ID
                        + "/isbn-taken", ProblemV1Docs.responseFields()));
    }
}
// end::leaf[]
