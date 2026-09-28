package com.example.books.contracttest.service;

import com.example.books.contract.ContractCase;
import com.example.books.contract.restdocs.ListBooksContractTests;

/**
 * The generated contract tests of {@code GET /books}, against the real service.
 *
 * <p>{@code arrangeStatelessCase} is not overridden: the service refuses an out-of-range
 * {@code page} or {@code size} before it reads the catalogue, so those cases need no state.
 *
 * <p><b>WARNING:</b> the stateless cases violate the principle that tests are independent of
 * each other and idempotent: a case the service wrongly accepts may leave state behind for the
 * cases after it. See {@link GetBookServiceContractTest}.
 */
class ListBooksServiceContractTest extends ServiceContractTest implements ListBooksContractTests {

    /**
     * The book every page the success cases ask for holds at least. No other test uses it.
     */
    private static final String FIXTURE_ID = "00000000-0000-4000-8000-000000000200";
    private static final String FIXTURE_ISBN = "9780000000001";

    /**
     * Puts the database in the state the case needs, and only there. The service must then
     * answer as the contract says: anything else is a defect in the service, not in the test.
     *
     * @param contractCase the case about to be sent
     */
    @Override
    public void arrangeState(ContractCase contractCase) {
        switch (contractCase.kind()) {
            // 200 with at least one book: an empty page is a 200 as well, but its items could
            // not be checked against the fields the contract declares for a book.
            case SUCCESS -> ensureBookExists(FIXTURE_ID, FIXTURE_ISBN);
            default -> throw new IllegalArgumentException("No state to arrange for case " + contractCase.id());
        }
    }
}
