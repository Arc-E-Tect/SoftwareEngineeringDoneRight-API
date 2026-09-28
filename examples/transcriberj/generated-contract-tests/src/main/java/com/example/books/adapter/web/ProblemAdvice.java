package com.example.books.adapter.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.InvalidNullException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * How the HTTP adapter answers a request that breaks the contract: 400, 406 or 415, each
 * before the catalogue is called.
 */
@RestControllerAdvice
public class ProblemAdvice {

    private static final String INVALID = "The request does not match the contract.";

    /**
     * A body member breaks a constraint.
     *
     * @param exception what Bean Validation found
     * @return 400
     */
    @ExceptionHandler
    public ResponseEntity<Problem> invalidBody(MethodArgumentNotValidException exception) {
        return invalid(exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), error.getDefaultMessage()))
                .toList());
    }

    /**
     * A path or query parameter breaks a constraint.
     *
     * @param exception what method validation found
     * @return 400
     */
    @ExceptionHandler
    public ResponseEntity<Problem> invalidParameter(HandlerMethodValidationException exception) {
        return invalid(exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldError(result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage())))
                .toList());
    }

    /**
     * A parameter is not of its type.
     *
     * @param exception what binding found
     * @return 400
     */
    @ExceptionHandler
    public ResponseEntity<Problem> mistypedParameter(MethodArgumentTypeMismatchException exception) {
        return invalid(List.of(new FieldError(exception.getName(), "must be an integer")));
    }

    /**
     * The body is missing, or JSON that cannot become a {@link BookRequest}: not an object, a
     * member of the wrong type or {@code null}, or a member the contract does not declare.
     *
     * @param exception what reading the body found
     * @return 400
     */
    @ExceptionHandler
    public ResponseEntity<Problem> unreadableBody(HttpMessageNotReadableException exception) {
        if (!(exception.getCause() instanceof JacksonException jackson)) {
            return problem(HttpStatus.BAD_REQUEST, INVALID, "The request has no body.", null);
        }
        if (jackson.getPath().isEmpty()) {
            return problem(HttpStatus.BAD_REQUEST, INVALID, "The body must be a JSON object.", null);
        }
        String field = jackson.getPath().stream()
                .map(JacksonException.Reference::getPropertyName)
                .collect(Collectors.joining("."));
        String message = switch (jackson) {
            case UnrecognizedPropertyException unknown -> "is not a member the contract declares";
            case InvalidNullException nullValue -> "must not be null";
            default -> "is not of the type the contract declares";
        };
        return invalid(List.of(new FieldError(field, message)));
    }

    /**
     * The body is not JSON.
     *
     * @param exception what Spring MVC found
     * @return 415
     */
    @ExceptionHandler
    public ResponseEntity<Problem> unsupportedMediaType(HttpMediaTypeNotSupportedException exception) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The request body is not JSON.",
                "The body must be sent as " + MediaType.APPLICATION_JSON_VALUE + ".", null);
    }

    /**
     * The request accepts no media type the answer could be given in. There is no body to
     * give: none would be acceptable.
     *
     * @param exception what Spring MVC found
     * @return 406
     */
    @ExceptionHandler
    public ResponseEntity<Void> notAcceptable(HttpMediaTypeNotAcceptableException exception) {
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }

    static ResponseEntity<Problem> problem(HttpStatus status, String title, String detail, List<FieldError> errors) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new Problem(title, status.value(), detail, errors));
    }

    private static ResponseEntity<Problem> invalid(List<FieldError> errors) {
        return problem(HttpStatus.BAD_REQUEST, INVALID, null, errors);
    }
}
