package com.example.books.contracttest.service;

import com.example.books.contract.ContractCase;
import com.example.books.contract.restdocs.CreateBookContractTests;
import com.example.books.contracttest.CreateBookConflictContractTests;
import tools.jackson.databind.json.JsonMapper;

/**
 * The contract tests of {@code POST /books}, against the real service: the generated ones,
 * and the hand-written 409.
 *
 * <p>{@code arrangeStatelessCase} is not overridden: the service refuses an invalid body and a
 * body that is not JSON before the catalogue is called, so those cases need no state.
 *
 * <p><b>WARNING:</b> the stateless cases violate the principle that tests are independent of
 * each other and idempotent. Every invalid-request case sends the same valid book with one
 * constraint broken, so if the service wrongly accepts one, it stores that book, and the next
 * case it wrongly accepts is answered 409, not 201: its failure is a consequence of the first,
 * not a second cause. Read the first failing stateless test first; re-run on a fresh database
 * -- delete {@code build/database} -- before diagnosing a second failure. A green run is not
 * affected.
 */
class CreateBookServiceContractTest extends ServiceContractTest
        implements CreateBookContractTests, CreateBookConflictContractTests {

    private static final JsonMapper JSON = new JsonMapper();

    /**
     * Puts the database in the state the case needs, and only there. The service must then
     * answer as the contract says: anything else is a defect in the service, not in the test.
     *
     * @param contractCase the case about to be sent
     */
    @Override
    public void arrangeState(ContractCase contractCase) {
        switch (contractCase.kind()) {
            // 201: no book has the request's ISBN yet -- on every run, not just the first.
            case SUCCESS -> ensureNoBookWithIsbn(JSON.readTree(contractCase.request().body()).get("isbn").asString());
            default -> throw new IllegalArgumentException("No state to arrange for case " + contractCase.id());
        }
    }

    /**
     * 409: a book with the request's ISBN exists.
     *
     * @param isbn the ISBN
     */
    @Override
    public void arrangeIsbnTaken(String isbn) {
        ensureBookExists("00000000-0000-4000-8000-000000000409", isbn);
    }
}
