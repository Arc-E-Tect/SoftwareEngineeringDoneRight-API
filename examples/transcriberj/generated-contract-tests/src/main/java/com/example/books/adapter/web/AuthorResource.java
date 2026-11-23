package com.example.books.adapter.web;

import com.example.books.domain.Author;

/**
 * A book's author, as the API writes it: {@code PersonAuthorV1} or {@code OrganisationAuthorV1}.
 */
public sealed interface AuthorResource {

    /**
     * The author as the API writes it.
     *
     * @param author the author
     * @return its resource
     */
    static AuthorResource of(Author author) {
        return switch (author) {
            case Author.Person person -> new PersonAuthorResource(person.name());
            case Author.Organisation organisation -> new OrganisationAuthorResource(organisation.name());
        };
    }

    /**
     * {@code PersonAuthorV1}.
     *
     * @param name the person's name
     */
    record PersonAuthorResource(String name) implements AuthorResource {
    }

    /**
     * {@code OrganisationAuthorV1}.
     *
     * @param organisationName the organisation's name
     */
    record OrganisationAuthorResource(String organisationName) implements AuthorResource {
    }
}
