package com.example.books.contracttest.stubs;

import com.example.books.contract.ContractCase;
import com.example.books.contract.restdocs.GetBookContractTests;

/**
 * The generated contract tests of {@code GET /books/{id}}, against the double.
 */
class GetBookStubsContractTest extends StubsContractTest implements GetBookContractTests {

    /**
     * Nothing to arrange: the published stubs already hold every case, and each matches its
     * own case's request exactly, so none answers another's.
     *
     * @param contractCase the case about to be sent
     */
    @Override
    public void arrangeState(ContractCase contractCase) {
    }
}
