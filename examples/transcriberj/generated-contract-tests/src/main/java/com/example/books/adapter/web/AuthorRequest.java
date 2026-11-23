package com.example.books.adapter.web;

import com.example.books.domain.Author;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The {@code author} of {@code POST /books}, as it arrives: {@code AuthorV1}, a person or an
 * organisation.
 *
 * <p>The contract has no discriminator: each branch is a closed object with its own required
 * member, so a value matches one branch by the members it has. Jackson deduces the branch the
 * same way. An author with no member of either branch, such as {@code {}}, matches neither, and
 * one with a member of each is refused by the strict mapper {@link JsonConfiguration} sets up,
 * as a member the branch does not declare.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes({
        @JsonSubTypes.Type(AuthorRequest.PersonAuthorRequest.class),
        @JsonSubTypes.Type(AuthorRequest.OrganisationAuthorRequest.class)})
public sealed interface AuthorRequest {

    /**
     * The author this request names. Only called once the request is valid.
     *
     * @return the author
     */
    Author toAuthor();

    /** {@code PersonAuthorV1}: a person. Setters for the reason {@link BookRequest} gives. */
    final class PersonAuthorRequest implements AuthorRequest {

        @NotNull
        @Size(min = 1, max = 120)
        private String name;

        @Override
        public Author toAuthor() {
            return new Author.Person(name);
        }

        /**
         * The person's name.
         *
         * @param name the name
         */
        public void setName(String name) {
            this.name = name;
        }
    }

    /** {@code OrganisationAuthorV1}: an organisation. Setters for the reason {@link BookRequest} gives. */
    final class OrganisationAuthorRequest implements AuthorRequest {

        @NotNull
        @Size(min = 1, max = 120)
        private String organisationName;

        @Override
        public Author toAuthor() {
            return new Author.Organisation(organisationName);
        }

        /**
         * The organisation's name.
         *
         * @param organisationName the name
         */
        public void setOrganisationName(String organisationName) {
            this.organisationName = organisationName;
        }
    }
}
