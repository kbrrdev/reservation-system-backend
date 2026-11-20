package dev.kbrr.ticketing.common;

import org.springframework.http.ProblemDetail;

public final class Problems {

	private Problems() {
	}

	public static ProblemDetail of(ErrorCode code, String detail) {
		ProblemDetail pd = ProblemDetail.forStatusAndDetail(code.status(), detail);
		pd.setTitle(code.status().getReasonPhrase());
		pd.setProperty("code", code.name());
		return pd;
	}
}
