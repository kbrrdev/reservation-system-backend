package dev.kbrr.ticketing.auth;

import dev.kbrr.ticketing.common.ErrorCode;
import dev.kbrr.ticketing.common.Problems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class ProblemDetailAuthHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final JsonMapper mapper;

	ProblemDetailAuthHandlers(JsonMapper mapper) {
		this.mapper = mapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
			throws IOException {
		write(response, Problems.of(ErrorCode.UNAUTHENTICATED, "Authentication required"));
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
			throws IOException {
		write(response, Problems.of(ErrorCode.FORBIDDEN, "Not allowed"));
	}

	private void write(HttpServletResponse response, ProblemDetail pd) throws IOException {
		response.setStatus(pd.getStatus());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		mapper.writeValue(response.getOutputStream(), pd);
	}
}
