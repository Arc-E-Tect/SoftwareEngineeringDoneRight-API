package com.example.books.contracttest.stubs;

import com.example.books.contract.ContractCase;
import com.example.books.contract.CreateBookOperation;
import com.example.books.contract.ProblemV1;
import com.example.books.contract.restdocs.CreateBookContractTests;
import com.example.books.contracttest.CreateBookConflictContractTests;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/**
 * The contract tests of {@code POST /books}, against the double: the generated ones, and the
 * hand-written 409.
 */
// tag::leaf[]
class CreateBookStubsContractTest extends StubsContractTest
        implements CreateBookContractTests, CreateBookConflictContractTests {

    /**
     * Nothing to arrange: the published stubs already hold every case, and each matches its
     * own case's request exactly, so none answers another's.
     *
     * @param contractCase the case about to be sent
     */
    @Override
    public void arrangeState(ContractCase contractCase) {
    }

    /**
     * The generated stubs cannot answer 409: it depends on state they do not hold. So this
     * registers a hand-written stub, at priority 1, above the generated stubs' 2, answering a
     * book with this ISBN with the generated problem body.
     *
     * @param isbn the ISBN
     */
    @Override
    public void arrangeIsbnTaken(String isbn) {
        stubs.stubFor(post(urlPathEqualTo(CreateBookOperation.PATH))
                .atPriority(1)
                .withRequestBody(matchingJsonPath("$.isbn", equalTo(isbn)))
                .willReturn(aResponse()
                        .withStatus(CreateBookOperation.STATUS_409)
                        .withHeader("Content-Type", CreateBookOperation.CONTENT_TYPE_409)
                        .withBody(ProblemV1.body("A book with this ISBN is already in the catalogue.",
                                CreateBookOperation.STATUS_409))));
    }
}
// end::leaf[]
