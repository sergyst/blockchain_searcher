package com.blockchainsearcher.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import java.net.URI;

/**
 * Base for application errors. Extending {@link ErrorResponseException} means Spring MVC
 * renders them as RFC 9457 {@code application/problem+json} without extra handlers.
 */
public abstract class ApiException extends ErrorResponseException {

    protected ApiException(HttpStatus status, String problemType, String title, String detail, Throwable cause) {
        super(status, problem(status, problemType, title, detail), cause);
    }

    private static ProblemDetail problem(HttpStatus status, String problemType, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:blockchain-searcher:problem:" + problemType));
        problem.setTitle(title);
        return problem;
    }
}
