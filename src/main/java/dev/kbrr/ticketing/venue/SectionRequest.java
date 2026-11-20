package dev.kbrr.ticketing.venue;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SectionRequest(@NotBlank @Size(max = 100) String name,
		@NotEmpty @Size(max = 100) List<@NotNull @Pattern(regexp = "[A-Za-z0-9]{1,10}") String> rowLabels,
		@NotNull @Min(1) @Max(200) Integer seatsPerRow) {
}
