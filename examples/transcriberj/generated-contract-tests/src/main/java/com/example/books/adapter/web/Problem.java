package com.example.books.adapter.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * What went wrong with a request, as RFC 9457 describes it, and as the contract's
 * {@code ProblemV1} declares it.
 *
 * @param title  a short summary
 * @param status the HTTP status code
 * @param detail what went wrong with this request, if there is more to say
 * @param errors what is wrong with each member or parameter, for an invalid request
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(String title, int status, String detail, List<FieldError> errors) {
}
