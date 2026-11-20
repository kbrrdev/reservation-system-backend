package dev.kbrr.ticketing.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.servlet.ServletException;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.RequestParam;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

	MockMvc mvc;

	@BeforeEach
	void setUp() {
		LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();
		mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
				.setControllerAdvice(new GlobalExceptionHandler())
				.setValidator(validator)
				.build();
	}

	@Test
	void validationError() throws Exception {
		mvc.perform(post("/probe/validated").contentType(APPLICATION_JSON).content("{\"name\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("name"));
	}

	@Test
	void malformedJson() throws Exception {
		mvc.perform(post("/probe/validated").contentType(APPLICATION_JSON).content("{not json"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
	}

	@Test
	void apiException() throws Exception {
		mvc.perform(get("/probe/api-error")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("EMAIL_TAKEN")).andExpect(jsonPath("$.detail").value("taken"));
	}

	@Test
	void unexpectedErrorDoesNotLeak() throws Exception {
		mvc.perform(get("/probe/boom")).andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
				.andExpect(content().string(not(containsString("secret internals"))));
	}

	@Test
	void staleUpdate() throws Exception {
		mvc.perform(get("/probe/stale")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("STALE_UPDATE"));
	}

	@Test
	void securityExceptionsPropagateToSecurityFilterChain() {
		ServletException ex = assertThrows(ServletException.class, () -> mvc.perform(get("/probe/denied")));
		assertInstanceOf(AccessDeniedException.class, ex.getCause());
	}

	@Test
	void methodParameterValidation() throws Exception {
		mvc.perform(get("/probe/paged").param("page", "0")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("page"));
	}

	record Body(@NotBlank String name) {
	}

	@RestController
	static class ProbeController {
		@PostMapping("/probe/validated")
		String validated(@Valid @RequestBody Body body) {
			return "ok";
		}

		@GetMapping("/probe/api-error")
		String apiError() {
			throw new ApiException(ErrorCode.EMAIL_TAKEN, "taken");
		}

		@GetMapping("/probe/boom")
		String boom() {
			throw new IllegalStateException("secret internals");
		}

		@GetMapping("/probe/denied")
		String denied() {
			throw new AccessDeniedException("no");
		}

		@GetMapping("/probe/paged")
		String paged(@RequestParam @Min(1) int page) {
			return "ok";
		}

		@GetMapping("/probe/stale")
		String stale() {
			throw new ObjectOptimisticLockingFailureException(Object.class, 1);
		}
	}
}
