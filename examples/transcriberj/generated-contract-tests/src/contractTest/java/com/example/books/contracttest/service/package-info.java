/**
 * The contract tests against the real service: one leaf per generated
 * {@code <Operation>ContractTests} interface, each implementing the fixture its operation's
 * stateful cases need.
 *
 * <p>A fixture puts the database in the state a case needs, and the service must then answer
 * as the contract says. Anything else is a defect in the service, not in the test. A response
 * with the declared status, whose body matches the declared fields, passes whatever its values.
 */
package com.example.books.contracttest.service;
