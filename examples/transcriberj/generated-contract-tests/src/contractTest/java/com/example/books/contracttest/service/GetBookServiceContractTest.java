package com.example.books.contracttest.service;

import com.example.books.contract.ContractCase;
import com.example.books.contract.restdocs.GetBookContractTests;

/**
 * The generated contract tests of {@code GET /books/{id}}, against the real service.
 *
 * <p>{@code arrangeStatelessCase} is not overridden: the service refuses a malformed
 * identifier and an unacceptable {@code Accept} before it looks anything up, so those cases
 * need no state.
 *
 * <p><b>WARNING:</b> the stateless cases -- invalid request, not acceptable, unsupported media
 * type -- violate the principle that tests are independent of each other and idempotent. They
 * are derived from the contract, not from this service, and every case of an operation starts
 * from the same valid request, so a case the service wrongly accepts leaves state behind, and a
 * later case may then get another answer. Read the first failing stateless test first; re-run on
 * a fresh database -- delete {@code build/database} -- before diagnosing a second failure. A
 * green run is not affected.
 */
// tag::leaf[]
class GetBookServiceContractTest extends ServiceContractTest implements GetBookContractTests {

    /**
     * The ISBN of the book the success case reads. No other test uses it.
     */
    private static final String FIXTURE_ISBN = "9798000000002";

    /**
     * Puts the database in the state the case needs, and only there. The service must then
     * answer as the contract says: anything else is a defect in the service, not in the test.
     *
     * @param contractCase the case about to be sent
     */
    @Override
    public void arrangeState(ContractCase contractCase) {
        String id = contractCase.request().pathParameters().getFirst();
        switch (contractCase.kind()) {
            // 200: the book the path names exists.
            case SUCCESS -> ensureBookExists(id, FIXTURE_ISBN);
            // 404: the book the path names does not exist, whatever ran before.
            case NOT_FOUND -> ensureNoBook(id);
            default -> throw new IllegalArgumentException("No state to arrange for case " + contractCase.id());
        }
    }
}
// end::leaf[]
