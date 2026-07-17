package com.rageval.workbench.common.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Centralized RFC 9457 {@link ProblemDetail} exception mapping. Every response carries the
 * request's correlation ID (see {@link CorrelationIdFilter}) as an {@code errors}/{@code
 * correlationId} extension property so a client-reported failure can be found in the logs.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ValidationException.class)
    public ProblemDetail handleValidation(ValidationException ex, HttpServletRequest request) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Validation failed");
        problem.setProperty("errors", ex.getViolations());
        return withCorrelationId(problem, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBeanValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<FieldViolation> violations =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(fieldError -> new FieldViolation(
                                fieldError.getField(),
                                fieldError.getDefaultMessage() != null
                                        ? fieldError.getDefaultMessage()
                                        : "invalid value"))
                        .toList();
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setTitle("Validation failed");
        problem.setProperty("errors", violations);
        return withCorrelationId(problem, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleMalformedBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request body is missing or malformed JSON");
        problem.setTitle("Malformed request body");
        return withCorrelationId(problem, request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleUploadTooLarge(
            MaxUploadSizeExceededException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONTENT_TOO_LARGE, "Uploaded file exceeds the maximum allowed size");
        problem.setTitle("Upload too large");
        return withCorrelationId(problem, request);
    }

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Not found");
        return withCorrelationId(problem, request);
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Conflict");
        return withCorrelationId(problem, request);
    }

    /**
     * A request to a URL no handler serves must stay a plain 404 — without this, the
     * {@code Exception} catch-all below would turn it into a misleading 500. Found in Phase 3 by
     * the test asserting the demo-target endpoint is absent outside the {@code demo} profile.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No endpoint at this path");
        problem.setTitle("Not found");
        return withCorrelationId(problem, request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        log.error("Unhandled exception [correlationId={}]", correlationId, ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        problem.setTitle("Internal error");
        problem.setProperty("correlationId", correlationId);
        return problem;
    }

    private ProblemDetail withCorrelationId(ProblemDetail problem, HttpServletRequest request) {
        problem.setProperty("correlationId", correlationId(request));
        return problem;
    }

    private String correlationId(HttpServletRequest request) {
        Object attribute = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
        return attribute != null ? attribute.toString() : "unknown";
    }
}
