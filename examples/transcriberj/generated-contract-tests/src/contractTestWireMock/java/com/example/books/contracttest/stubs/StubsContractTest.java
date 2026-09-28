package com.example.books.contracttest.stubs;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.test.web.reactive.server.WebTestClient;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.restdocs.webtestclient.WebTestClientRestDocumentation.documentationConfiguration;

/**
 * The double: WireMock, loading the mapping files the archive ships, unpacked afresh into
 * {@code build/stubs/books} by {@code unpackBooksStubs} before every run. The contract tests
 * therefore verify the very files a consumer gets.
 */
@ExtendWith(RestDocumentationExtension.class)
abstract class StubsContractTest {

    static WireMockServer stubs;

    private WebTestClient client;

    @BeforeAll
    static void startTheDouble() {
        stubs = new WireMockServer(options().dynamicPort().usingFilesUnderDirectory(System.getProperty("books.stubs")));
        stubs.start();
    }

    @AfterAll
    static void stopTheDouble() {
        stubs.stop();
    }

    @BeforeEach
    void bindClientToTheDouble(RestDocumentationContextProvider restDocumentation) {
        client = WebTestClient.bindToServer()
                .baseUrl(stubs.baseUrl())
                .filter(documentationConfiguration(restDocumentation))
                .build();
    }

    /**
     * The client, bound to the double.
     *
     * @return the client
     */
    public WebTestClient client() {
        return client;
    }

    /**
     * The double's by-product snippets go under {@code generated-stubs/}, so that they do not
     * overwrite the real target's. They hold WireMock's own headers, which is one reason no
     * contract test's snippets are ever published.
     *
     * @return the prefix
     */
    public String generatedDocumentationPrefix() {
        return "generated-stubs";
    }
}
