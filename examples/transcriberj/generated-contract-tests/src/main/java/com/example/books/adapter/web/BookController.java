package com.example.books.adapter.web;

import com.example.books.application.AddOutcome;
import com.example.books.application.Catalogue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The HTTP adapter: {@code /books} and {@code /books/{id}}.
 *
 * <p>Every request is checked against the contract before the catalogue is called: the
 * annotations on the parameters and on {@link BookRequest}, and the JSON binding
 * {@link JsonConfiguration} sets up. A request that breaks the contract is answered by
 * {@link ProblemAdvice}, and the catalogue never sees it. The mappings declare what they
 * produce, so an {@code Accept} they cannot meet is answered with 406. They declare nothing
 * they consume: the body's media type is checked when it is read, so a request without a
 * body is answered with 400, not 415.
 */
@RestController
public class BookController {

    private static final String ID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    private final Catalogue catalogue;

    /**
     * An adapter over the catalogue.
     *
     * @param catalogue the catalogue
     */
    public BookController(Catalogue catalogue) {
        this.catalogue = catalogue;
    }

    /**
     * Adds a book.
     *
     * @param request the book
     * @return 201 with the book, or 409 when its ISBN is taken
     */
    @PostMapping(path = "/books", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
    public ResponseEntity<?> createBook(@Valid @RequestBody BookRequest request) {
        return switch (catalogue.add(request.toNewBook())) {
            case AddOutcome.Added added -> ResponseEntity.status(HttpStatus.CREATED)
                    .contentType(MediaType.APPLICATION_JSON).body(BookResource.of(added.book()));
            case AddOutcome.IsbnTaken taken -> ProblemAdvice.problem(HttpStatus.CONFLICT,
                    "A book with this ISBN is already in the catalogue.",
                    "The catalogue already holds a book with ISBN " + taken.isbn() + ".", null);
        };
    }

    /**
     * Reads a book.
     *
     * @param id its identifier
     * @return 200 with the book, or 404
     */
    @GetMapping(path = "/books/{id}", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
    public ResponseEntity<?> getBook(@PathVariable @Pattern(regexp = ID_PATTERN) String id) {
        return catalogue.find(UUID.fromString(id))
                .<ResponseEntity<?>>map(book -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON).body(BookResource.of(book)))
                .orElseGet(() -> ProblemAdvice.problem(HttpStatus.NOT_FOUND, "No book has this identifier.",
                        "There is no book " + id + ".", null));
    }

    /**
     * Lists the catalogue, a page at a time.
     *
     * @param page which page, the first being 0
     * @param size how many books a page holds
     * @return 200 with the page
     */
    @GetMapping(path = "/books", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
    public ResponseEntity<BookPageResource> listBooks(
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(BookPageResource.of(catalogue.list(page, size)));
    }
}
