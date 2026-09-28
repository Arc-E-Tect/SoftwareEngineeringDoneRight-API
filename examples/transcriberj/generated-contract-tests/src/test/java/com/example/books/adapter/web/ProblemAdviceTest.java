package com.example.books.adapter.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.PropertyName;
import tools.jackson.databind.exc.InvalidNullException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests of how {@link ProblemAdvice} answers a body that cannot be read: which field it
 * names, and what it says about it. Which bodies cannot be read is the JSON mapper's business,
 * which only the contract tests see; these tests start from the mapper's exceptions.
 */
class ProblemAdviceTest {

    private final ProblemAdvice advice = new ProblemAdvice();

    @Test
    void anUnknownMemberIsNamed() {
        JacksonException unknown = new UnrecognizedPropertyException(null, "unknown", null, BookRequest.class,
                "unexpectedMember", List.of());
        unknown.prependPath(BookRequest.class, "unexpectedMember");

        assertThat(answerTo(unknown).getBody().errors())
                .containsExactly(new FieldError("unexpectedMember", "is not a member the contract declares"));
    }

    @Test
    void aNullMemberIsNamed() {
        JacksonException nullValue = new InvalidNullException((JsonParser) null, "null",
                PropertyName.construct("subtitle")) {
        };
        nullValue.prependPath(BookRequest.class, "subtitle");

        assertThat(answerTo(nullValue).getBody().errors())
                .containsExactly(new FieldError("subtitle", "must not be null"));
    }

    private ResponseEntity<Problem> answerTo(JacksonException cause) {
        ResponseEntity<Problem> answer = advice.unreadableBody(
                new HttpMessageNotReadableException("unreadable", cause, mock(HttpInputMessage.class)));
        assertThat(answer.getStatusCode().value()).isEqualTo(400);
        return answer;
    }
}
