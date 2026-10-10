package com.blockchainsearcher.error;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every error leaves the API as {@code application/problem+json}.
 * {@link ResponseEntityExceptionHandler} already covers Spring MVC exceptions and our
 * {@link ErrorResponseException} subclasses; this adds field-level validation errors and a catch-all.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getParameterValidationResults().forEach(result -> result.getResolvableErrors().forEach(error ->
                errors.put(result.getMethodParameter().getParameterName(), error.getDefaultMessage())));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request parameters are invalid");
        problem.setType(URI.create("urn:blockchain-searcher:problem:invalid-request"));
        problem.setTitle("Invalid request");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleErrorResponseException(
            ErrorResponseException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            log.error("{}: {}", ex.getBody().getTitle(), ex.getBody().getDetail(), ex);
        } else {
            log.info("{}: {}", ex.getBody().getTitle(), ex.getBody().getDetail());
        }
        return super.handleErrorResponseException(ex, headers, status, request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred");
        problem.setType(URI.create("urn:blockchain-searcher:problem:internal-error"));
        problem.setTitle("Internal error");
        return problem;
    }
}
