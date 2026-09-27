package com.example.books.adapter.web;

/**
 * What is wrong with one member of the body, or one parameter.
 *
 * @param field   the member or parameter
 * @param message what is wrong with it
 */
public record FieldError(String field, String message) {
}
