package dev.kbrr.ticketing.common;

import java.util.List;
import java.util.Map;

public class ApiException extends RuntimeException {

	private final ErrorCode code;
	private final Map<String, Object> properties;

	public ApiException(ErrorCode code, String detail) {
		this(code, detail, Map.of());
	}

	public ApiException(ErrorCode code, String detail, Map<String, Object> properties) {
		super(detail);
		this.code = code;
		this.properties = properties;
	}

	// Same `errors` shape as bean validation, for failures only the service layer can detect.
	public static ApiException fieldError(String field, String message) {
		return new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed",
				Map.of("errors", List.of(Map.of("field", field, "message", message))));
	}

	public ErrorCode code() {
		return code;
	}

	public Map<String, Object> properties() {
		return properties;
	}
}
