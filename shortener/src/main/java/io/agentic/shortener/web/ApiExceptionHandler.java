package io.agentic.shortener.web;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import io.agentic.shortener.domain.AliasTakenException;
import io.agentic.shortener.domain.CodeSpaceExhaustedException;
import io.agentic.shortener.domain.InvalidFieldException;
import io.agentic.shortener.domain.LinkExpiredException;
import io.agentic.shortener.domain.ShortUrlNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every failure to an RFC 9457 problem (SHORT-016). Field-level problems carry
 * {@code errors[]}; 5xx responses never expose exception details.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    static final String PROBLEM_BASE = "https://agentic.local/problems/";

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** One {@code errors[]} entry, as declared by the contract's FieldError schema. */
    record FieldError(String field, String message) {}

    // ---- domain ----------------------------------------------------------------------

    @ExceptionHandler(InvalidFieldException.class)
    ResponseEntity<Object> invalidField(InvalidFieldException ex, WebRequest request) {
        return validation(List.of(new FieldError(ex.field(), ex.getMessage())), request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> constraintViolation(ConstraintViolationException ex, WebRequest request) {
        List<FieldError> errors = ex.getConstraintViolations().stream()
                .map(v -> new FieldError(leafName(v), v.getMessage()))
                .toList();
        return validation(errors, request);
    }

    @ExceptionHandler(ShortUrlNotFoundException.class)
    ResponseEntity<Object> notFound(ShortUrlNotFoundException ex, WebRequest request) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Short URL not found", ex.getMessage(), request);
    }

    @ExceptionHandler(LinkExpiredException.class)
    ResponseEntity<Object> gone(LinkExpiredException ex, WebRequest request) {
        return problem(HttpStatus.GONE, "expired", "Short URL expired", ex.getMessage(), request);
    }

    @ExceptionHandler(AliasTakenException.class)
    ResponseEntity<Object> conflict(AliasTakenException ex, WebRequest request) {
        return problem(HttpStatus.CONFLICT, "alias-taken", "Alias already taken", ex.getMessage(), request);
    }

    @ExceptionHandler(CodeSpaceExhaustedException.class)
    ResponseEntity<Object> exhausted(CodeSpaceExhaustedException ex, WebRequest request) {
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "code-allocation-failed",
                "Could not allocate a short code",
                "Please retry the request.",
                request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception for {}", path(request), ex);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal",
                "Internal error",
                "An unexpected error occurred.",
                request);
    }

    // ---- framework validation, re-shaped to carry errors[] ---------------------------

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new FieldError(e.getField(), String.valueOf(e.getDefaultMessage())))
                .toList();
        return validation(errors, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldError> errors = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(e -> new FieldError(
                                String.valueOf(r.getMethodParameter().getParameterName()),
                                String.valueOf(e.getDefaultMessage()))))
                .toList();
        return validation(errors, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return validation(List.of(unreadable(ex.getCause())), request);
    }

    /** Every framework-produced problem gets an instance; 5xx detail is scrubbed. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            problem.setInstance(URI.create(path(request)));
            if (statusCode.is5xxServerError()) {
                problem.setDetail("An unexpected error occurred.");
            }
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    // ---- helpers ---------------------------------------------------------------------

    private static FieldError unreadable(@Nullable Throwable cause) {
        if (cause instanceof UnrecognizedPropertyException e) {
            return new FieldError(e.getPropertyName(), "is not a recognised field");
        }
        if (cause instanceof InvalidFormatException e && !e.getPath().isEmpty()) {
            return new FieldError(jsonPath(e), "has an invalid format");
        }
        if (cause instanceof JsonMappingException e && !e.getPath().isEmpty()) {
            return new FieldError(jsonPath(e), "is invalid");
        }
        return new FieldError("body", "is not valid JSON");
    }

    private static String jsonPath(JsonMappingException e) {
        return e.getPath().stream()
                .map(r -> r.getFieldName() != null ? r.getFieldName() : "[" + r.getIndex() + "]")
                .collect(Collectors.joining("."));
    }

    private static String leafName(ConstraintViolation<?> violation) {
        String name = "";
        for (Path.Node node : violation.getPropertyPath()) {
            name = node.getName();
        }
        return name;
    }

    private ResponseEntity<Object> validation(List<FieldError> errors, WebRequest request) {
        ProblemDetail problem = build(
                HttpStatus.BAD_REQUEST, "validation", "Invalid request", "One or more fields are invalid.", request);
        problem.setProperties(Map.of("errors", errors));
        return ResponseEntity.badRequest().body(problem);
    }

    private ResponseEntity<Object> problem(
            HttpStatus status, String slug, String title, String detail, WebRequest request) {
        return ResponseEntity.status(status).body(build(status, slug, title, detail, request));
    }

    private static ProblemDetail build(
            HttpStatus status, String slug, String title, String detail, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(PROBLEM_BASE + slug));
        problem.setTitle(title);
        problem.setInstance(URI.create(path(request)));
        return problem;
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servlet
                ? servlet.getRequest().getRequestURI()
                : "about:blank";
    }
}
