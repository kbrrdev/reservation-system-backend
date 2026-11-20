package dev.kbrr.ticketing.common;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail pd = Problems.of(ErrorCode.VALIDATION_FAILED, "Request validation failed");
		List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(e -> Map.of("field", e.getField(), "message", String.valueOf(e.getDefaultMessage())))
				.toList();
		pd.setProperty("errors", errors);
		return handleExceptionInternal(ex, pd, headers, HttpStatus.BAD_REQUEST, request);
	}

	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail pd = Problems.of(ErrorCode.VALIDATION_FAILED, "Request validation failed");
		List<Map<String, String>> errors = ex.getParameterValidationResults().stream()
				.flatMap(r -> r.getResolvableErrors().stream()
						.map(e -> Map.of("field", String.valueOf(r.getMethodParameter().getParameterName()),
								"message", String.valueOf(e.getDefaultMessage()))))
				.toList();
		pd.setProperty("errors", errors);
		return handleExceptionInternal(ex, pd, headers, HttpStatus.BAD_REQUEST, request);
	}

	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		return handleExceptionInternal(ex, Problems.of(ErrorCode.MALFORMED_REQUEST, "Request body is not valid JSON"),
				headers, HttpStatus.BAD_REQUEST, request);
	}

	// Catches Spring's own errors (404, 405, 415, ...) so each one carries a code.
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		// The base class builds the ProblemDetail itself when body is null (ErrorResponse exceptions), so patch the result.
		ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
		if (response != null && response.getBody() instanceof ProblemDetail pd
				&& (pd.getProperties() == null || !pd.getProperties().containsKey("code"))) {
			pd.setProperty("code", codeFor(statusCode).name());
		}
		return response;
	}

	private static ErrorCode codeFor(HttpStatusCode s) {
		return switch (s.value()) {
			case 404 -> ErrorCode.NOT_FOUND;
			case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
			case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
			default -> s.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.MALFORMED_REQUEST;
		};
	}

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ProblemDetail> handleApi(ApiException e) {
		ProblemDetail pd = Problems.of(e.code(), e.getMessage());
		e.properties().forEach(pd::setProperty);
		return ResponseEntity.status(e.code().status()).body(pd);
	}

	@ExceptionHandler(OptimisticLockingFailureException.class)
	ResponseEntity<ProblemDetail> handleStale(OptimisticLockingFailureException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(Problems.of(ErrorCode.STALE_UPDATE, "Changed by someone else, reload and retry"));
	}

	// Re-thrown so Spring Security's ExceptionTranslationFilter writes the 401/403 instead of the catch-all's 500.
	@ExceptionHandler({ AccessDeniedException.class, AuthenticationException.class })
	void rethrowSecurity(RuntimeException e) {
		throw e;
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
		log.error("Unexpected error", e);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(Problems.of(ErrorCode.INTERNAL_ERROR, "Unexpected error"));
	}
}
