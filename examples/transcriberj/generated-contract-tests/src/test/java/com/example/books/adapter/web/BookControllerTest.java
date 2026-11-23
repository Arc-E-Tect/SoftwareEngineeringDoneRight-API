package com.example.books.adapter.web;

import com.example.books.application.AddOutcome;
import com.example.books.application.Catalogue;
import com.example.books.contract.BookRequestV1;
import com.example.books.contract.PersonAuthorV1;
import com.example.books.contract.CreateBookOperation;
import com.example.books.contract.GetBookOperation;
import com.example.books.contract.ListBooksOperation;
import com.example.books.contract.OrganisationAuthorV1;
import com.example.books.domain.Author;
import com.example.books.domain.Book;
import com.example.books.domain.BookFormat;
import com.example.books.domain.BookPage;
import com.example.books.domain.NewBook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Component tests of the HTTP adapter: the controller and its problem handling alone, the
 * catalogue mocked, no Spring Boot context and no server.
 *
 * <p>The requests are built with the generated schema classes. MockMvc in standalone mode
 * reads them with Spring's default JSON mapper, not the one Spring Boot configures, so these
 * tests say nothing about how the running service binds JSON: whether it refuses an unknown
 * member, or coerces a scalar. Only a test sending real HTTP to the running service can: in
 * this project, the contract tests.
 */
class BookControllerTest {

    private static final UUID ID = UUID.fromString("3f2b9c1e-8d4a-4b6e-9f10-2a7c5d8e1b34");
    private static final Book BOOK = new Book(ID, "9780201633610", "Design Patterns",
            "Elements of Reusable Object-Oriented Software", new Author.Person("Erich Gamma"), 395, BookFormat.HARDCOVER);

    // tag::standalone[]
    private final Catalogue catalogue = mock(Catalogue.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new BookController(catalogue))
            .setControllerAdvice(new ProblemAdvice())
            .build();
    // end::standalone[]

    @BeforeEach
    void theCatalogueAcceptsEveryBook() {
        // So that a request the adapter fails to refuse is answered 201, and the test says so.
        when(catalogue.add(any())).thenReturn(new AddOutcome.Added(BOOK));
    }

    @Test
    void aValidBookIsAddedAndAnsweredWith201() throws Exception {
        when(catalogue.add(any())).thenReturn(new AddOutcome.Added(BOOK));

        mockMvc.perform(post(CreateBookOperation.PATH)
                        .contentType(CreateBookOperation.REQUEST_CONTENT_TYPE)
                        .content(BookRequestV1.body("9780201633610", "Design Patterns", PersonAuthorV1.body("Erich Gamma"), 395, "hardcover",
                                "Elements of Reusable Object-Oriented Software")))
                .andExpect(status().is(CreateBookOperation.STATUS_201))
                .andExpect(content().contentType(CreateBookOperation.CONTENT_TYPE_201))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.format").value("hardcover"))
                .andExpect(jsonPath("$.subtitle").value("Elements of Reusable Object-Oriented Software"))
                .andExpect(jsonPath("$.author.name").value("Erich Gamma"))
                .andExpect(jsonPath("$.author.organisationName").doesNotExist());

        verify(catalogue).add(new NewBook("9780201633610", "Design Patterns",
                "Elements of Reusable Object-Oriented Software", new Author.Person("Erich Gamma"), 395, BookFormat.HARDCOVER));
    }

    @Test
    void aBookAnOrganisationWroteIsAddedAsOneAndAnsweredWithIt() throws Exception {
        Book byOrganisation = new Book(ID, "9780226287058", "The Chicago Manual of Style", null,
                new Author.Organisation("University of Chicago Press"), 1144, BookFormat.HARDCOVER);
        when(catalogue.add(any())).thenReturn(new AddOutcome.Added(byOrganisation));

        mockMvc.perform(post(CreateBookOperation.PATH)
                        .contentType(CreateBookOperation.REQUEST_CONTENT_TYPE)
                        .content(BookRequestV1.body("9780226287058", "The Chicago Manual of Style",
                                OrganisationAuthorV1.body("University of Chicago Press"), 1144, "hardcover")))
                .andExpect(status().is(CreateBookOperation.STATUS_201))
                .andExpect(jsonPath("$.author.organisationName").value("University of Chicago Press"))
                .andExpect(jsonPath("$.author.name").doesNotExist());

        verify(catalogue).add(new NewBook("9780226287058", "The Chicago Manual of Style", null,
                new Author.Organisation("University of Chicago Press"), 1144, BookFormat.HARDCOVER));
    }

    @Test
    void aBookWithoutAnAuthorIsRefusedBeforeTheCatalogueIsCalled() throws Exception {
        mockMvc.perform(post(CreateBookOperation.PATH)
                        .contentType(CreateBookOperation.REQUEST_CONTENT_TYPE)
                        .content("{\"isbn\":\"9780201633610\",\"title\":\"Design Patterns\",\"pages\":395,\"format\":\"hardcover\"}"))
                .andExpect(status().is(CreateBookOperation.STATUS_400))
                .andExpect(jsonPath("$.errors[0].field").value("author"));

        verifyNoInteractions(catalogue);
    }

    @Test
    void anAuthorThatIsNeitherAPersonNorAnOrganisationIsRefusedBeforeTheCatalogueIsCalled() throws Exception {
        mockMvc.perform(post(CreateBookOperation.PATH)
                        .contentType(CreateBookOperation.REQUEST_CONTENT_TYPE)
                        .content(BookRequestV1.body("9780201633610", "Design Patterns", "{}", 395, "hardcover")))
                .andExpect(status().is(CreateBookOperation.STATUS_400))
                .andExpect(jsonPath("$.errors[0].field").value("author"));

        verifyNoInteractions(catalogue);
    }

    @Test
    void aPersonsNameLongerThan120IsRefusedBeforeTheCatalogueIsCalled() throws Exception {
        mockMvc.perform(post(CreateBookOperation.PATH)
                        .contentType(CreateBookOperation.REQUEST_CONTENT_TYPE)
                        .content(BookRequestV1.body("9780201633610", "Design Patterns",
                                PersonAuthorV1.body("a".repeat(121)), 395, "hardcover")))
                .andExpect(status().is(CreateBookOperation.STATUS_400))
                .andExpect(jsonPath("$.errors[0].field").value("author.name"));

        verifyNoInteractions(catalogue);
    }

    @Test
    void anEmptyOrganisationNameIsRefusedBeforeTheCatalogueIsCalled() throws Exception {
        mockMvc.perform(post(CreateBookOperation.PATH)
                        .contentType(CreateBookOperation.REQUEST_CONTENT_TYPE)
                        .content(BookRequestV1.body("9780201633610", "Design Patterns",
                                OrganisationAuthorV1.body(""), 395, "hardcover")))
                .andExpect(status().is(CreateBookOperation.STATUS_400))
                .andExpect(jsonPath("$.errors[0].field").value("author.organisationName"));

        verifyNoInteractions(catalogue);
    }

    @Test
    void aClientThatPrefersAnAsynchronousAnswerIsAnsweredWith202AndWhereTheBookWillBe() throws Exception {
        when(catalogue.addLater(any())).thenReturn(new AddOutcome.Accepted(BOOK));

        mockMvc.perform(post(CreateBookOperation.PATH)
                        .header("Prefer", "respond-async")
                        .contentType(CreateBookOperation.REQUEST_CONTENT_TYPE)
                        .content(BookRequestV1.body("9780201633610", "Design Patterns", PersonAuthorV1.body("Erich Gamma"), 395, "hardcover")))
                .andExpect(status().is(CreateBookOperation.STATUS_202))
                .andExpect(header().string("Location", "http://localhost/books/" + ID))
                .andExpect(header().string("Preference-Applied", "respond-async"))
                .andExpect(content().string(""));

        verify(catalogue).addLater(new NewBook("9780201633610", "Design Patterns", null, new Author.Person("Erich Gamma"), 395, BookFormat.HARDCOVER));
    }

    @Test
    void aBookWhoseIsbnIsTakenIsAnsweredWith409AlsoWhenAnAsynchronousAnswerIsPreferred() throws Exception {
        when(catalogue.addLater(any())).thenReturn(new AddOutcome.IsbnTaken("9780201633610"));

        mockMvc.perform(post(CreateBookOperation.PATH)
                        .header("Prefer", "respond-async")
                        .contentType(CreateBookOperation.REQUEST_CONTENT_TYPE)
                        .content(BookRequestV1.body("9780201633610", "Design Patterns", PersonAuthorV1.body("Erich Gamma"), 395, "hardcover")))
                .andExpect(status().is(CreateBookOperation.STATUS_409));
    }

    @Test
    void anotherPreferenceIsAnsweredWith201() throws Exception {
        mockMvc.perform(post(CreateBookOperation.PATH)
                        .header("Prefer", "return=minimal")
                        .contentType(CreateBookOperation.REQUEST_CONTENT_TYPE)
                        .content(BookRequestV1.body("9780201633610", "Design Patterns", PersonAuthorV1.body("Erich Gamma"), 395, "hardcover")))
                .andExpect(status().is(CreateBookOperation.STATUS_201));

        verify(catalogue).add(any());
    }

    @Test
    void respondAsyncIsFoundAmongOtherPreferences() {
        assertThat(BookController.prefersAsynchronous("return=minimal, Respond-Async; x=1")).isTrue();
        assertThat(BookController.prefersAsynchronous("wait=10")).isFalse();
        assertThat(BookController.prefersAsynchronous(null)).isFalse();
    }

    @Test
    void anEmptyPreferIsRefusedBeforeTheCatalogueIsCalled() throws Exception {
        mockMvc.perform(post(CreateBookOperation.PATH)
                        .header("Prefer", "")
                        .contentType(CreateBookOperation.REQUEST_CONTENT_TYPE)
                        .content(BookRequestV1.body("9780201633610", "Design Patterns", PersonAuthorV1.body("Erich Gamma"), 395, "hardcover")))
                .andExpect(status().is(CreateBookOperation.STATUS_400))
                .andExpect(jsonPath("$.errors[0].field").value("Prefer"));

        verifyNoInteractions(catalogue);
    }

    @Test
    void aBookWithoutASubtitleIsAnsweredWithoutOne() throws Exception {
        Book withoutSubtitle = new Book(ID, "9780201633610", "Design Patterns", null, new Author.Person("Erich Gamma"), 395, BookFormat.HARDCOVER);
        when(catalogue.add(any())).thenReturn(new AddOutcome.Added(withoutSubtitle));

        mockMvc.perform(post(CreateBookOperation.PATH)
                        .contentType(CreateBookOperation.REQUEST_CONTENT_TYPE)
                        .content(BookRequestV1.body("9780201633610", "Design Patterns", PersonAuthorV1.body("Erich Gamma"), 395, "hardcover")))
                .andExpect(status().is(CreateBookOperation.STATUS_201))
                .andExpect(jsonPath("$.subtitle").doesNotExist());
    }

    @Test
    void aBookWhoseIsbnIsTakenIsAnsweredWith409() throws Exception {
        when(catalogue.add(any())).thenReturn(new AddOutcome.IsbnTaken("9780201633610"));

        mockMvc.perform(post(CreateBookOperation.PATH)
                        .contentType(CreateBookOperation.REQUEST_CONTENT_TYPE)
                        .content(BookRequestV1.body("9780201633610", "Design Patterns", PersonAuthorV1.body("Erich Gamma"), 395, "hardcover")))
                .andExpect(status().is(CreateBookOperation.STATUS_409))
                .andExpect(content().contentType(CreateBookOperation.CONTENT_TYPE_409))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void aTitleLongerThan120IsRefusedBeforeTheCatalogueIsCalled() throws Exception {
        mockMvc.perform(post(CreateBookOperation.PATH)
                        .contentType(CreateBookOperation.REQUEST_CONTENT_TYPE)
                        .content(BookRequestV1.body("9780201633610", "a".repeat(121), PersonAuthorV1.body("Erich Gamma"), 395,
                                "hardcover")))
                .andExpect(status().is(CreateBookOperation.STATUS_400))
                .andExpect(content().contentType(CreateBookOperation.CONTENT_TYPE_400))
                .andExpect(jsonPath("$.errors[0].field").value("title"));

        verifyNoInteractions(catalogue);
    }

    @Test
    void moreThan5000PagesAreRefusedBeforeTheCatalogueIsCalled() throws Exception {
        mockMvc.perform(post(CreateBookOperation.PATH)
                        .contentType(CreateBookOperation.REQUEST_CONTENT_TYPE)
                        .content(BookRequestV1.body("9780201633610", "Design Patterns", PersonAuthorV1.body("Erich Gamma"), 5001,
                                "hardcover")))
                .andExpect(status().is(CreateBookOperation.STATUS_400))
                .andExpect(jsonPath("$.errors[0].field").value("pages"));

        verifyNoInteractions(catalogue);
    }

    @Test
    void aMemberOfTheWrongTypeIsRefusedBeforeTheCatalogueIsCalled() throws Exception {
        mockMvc.perform(post(CreateBookOperation.PATH)
                        .contentType(CreateBookOperation.REQUEST_CONTENT_TYPE)
                        .content("{\"isbn\":\"9780201633610\",\"title\":{},\"pages\":395,\"format\":\"hardcover\"}"))
                .andExpect(status().is(CreateBookOperation.STATUS_400))
                .andExpect(jsonPath("$.errors[0].field").value("title"));

        verifyNoInteractions(catalogue);
    }

    @Test
    void aBodyThatIsNotAnObjectIsRefused() throws Exception {
        mockMvc.perform(post(CreateBookOperation.PATH)
                        .contentType(CreateBookOperation.REQUEST_CONTENT_TYPE)
                        .content("[]"))
                .andExpect(status().is(CreateBookOperation.STATUS_400))
                .andExpect(jsonPath("$.errors").doesNotExist());

        verifyNoInteractions(catalogue);
    }

    @Test
    void aRequestWithoutABodyIsRefused() throws Exception {
        mockMvc.perform(post(CreateBookOperation.PATH))
                .andExpect(status().is(CreateBookOperation.STATUS_400));

        verifyNoInteractions(catalogue);
    }

    @Test
    void aBodyThatIsNotJsonIsAnsweredWith415() throws Exception {
        mockMvc.perform(post(CreateBookOperation.PATH)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(BookRequestV1.requiredBody()))
                .andExpect(status().is(CreateBookOperation.STATUS_415))
                .andExpect(content().contentType(CreateBookOperation.CONTENT_TYPE_415));

        verifyNoInteractions(catalogue);
    }

    @Test
    void aBookIsAnsweredWith200() throws Exception {
        when(catalogue.find(ID)).thenReturn(Optional.of(BOOK));

        mockMvc.perform(get(GetBookOperation.PATH, ID))
                .andExpect(status().is(GetBookOperation.STATUS_200))
                .andExpect(content().contentType(GetBookOperation.CONTENT_TYPE_200))
                .andExpect(jsonPath("$.isbn").value("9780201633610"));
    }

    @Test
    void anUnknownBookIsAnsweredWith404() throws Exception {
        when(catalogue.find(ID)).thenReturn(Optional.empty());

        mockMvc.perform(get(GetBookOperation.PATH, ID))
                .andExpect(status().is(GetBookOperation.STATUS_404))
                .andExpect(content().contentType(GetBookOperation.CONTENT_TYPE_404));
    }

    @Test
    void aMalformedIdentifierIsRefusedBeforeTheCatalogueIsCalled() throws Exception {
        mockMvc.perform(get(GetBookOperation.PATH, "not-an-identifier"))
                .andExpect(status().is(GetBookOperation.STATUS_400))
                .andExpect(content().contentType(GetBookOperation.CONTENT_TYPE_400))
                .andExpect(jsonPath("$.errors[0].field").value("id"));

        verifyNoInteractions(catalogue);
    }

    @Test
    void aBookIsNotGivenInAMediaTypeTheRequestDoesNotAccept() throws Exception {
        mockMvc.perform(get(GetBookOperation.PATH, ID).accept(MediaType.TEXT_HTML))
                .andExpect(status().is(GetBookOperation.STATUS_406));

        verifyNoInteractions(catalogue);
    }

    @Test
    void theFirstPageHoldsTwentyBooksUnlessAskedOtherwise() throws Exception {
        when(catalogue.list(0, 20)).thenReturn(new BookPage(List.of(BOOK), 0, 20, 1));

        mockMvc.perform(get(ListBooksOperation.PATH))
                .andExpect(status().is(ListBooksOperation.STATUS_200))
                .andExpect(content().contentType(ListBooksOperation.CONTENT_TYPE_200))
                .andExpect(jsonPath("$.items[0].id").value(ID.toString()))
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void aPageOutOfRangeIsRefusedBeforeTheCatalogueIsCalled() throws Exception {
        mockMvc.perform(get(ListBooksOperation.PATH).queryParam("size", "101"))
                .andExpect(status().is(ListBooksOperation.STATUS_400))
                .andExpect(jsonPath("$.errors[0].field").value("size"));

        verifyNoInteractions(catalogue);
    }

    @Test
    void aPageThatIsNotANumberIsRefusedBeforeTheCatalogueIsCalled() throws Exception {
        mockMvc.perform(get(ListBooksOperation.PATH).queryParam("page", "first"))
                .andExpect(status().is(ListBooksOperation.STATUS_400))
                .andExpect(jsonPath("$.errors[0].field").value("page"));

        verifyNoInteractions(catalogue);
    }
}
