package dev.kbrr.ticketing.payment;

import dev.kbrr.ticketing.country.CountryProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class PaymentGateways {

	private final Map<String, PaymentGateway> byProvider = new HashMap<>();

	public PaymentGateways(List<PaymentGateway> all, CountryProperties countries) {
		for (PaymentGateway g : all) {
			if (byProvider.putIfAbsent(g.provider(), g) != null) {
				throw new IllegalStateException("Duplicate payment gateway for provider " + g.provider());
			}
		}
		// Disabled countries too: their adapter stays deployed so existing bookings keep working.
		countries.countries().forEach((code, c) -> {
			if (!byProvider.containsKey(c.paymentProvider())) {
				throw new IllegalStateException(
						"Country " + code + " uses payment provider '" + c.paymentProvider() + "' but no gateway exists");
			}
		});
	}

	public Optional<PaymentGateway> find(String provider) {
		return Optional.ofNullable(byProvider.get(provider));
	}

	public PaymentGateway get(String provider) {
		return find(provider)
				.orElseThrow(() -> new IllegalStateException("No payment gateway for provider " + provider));
	}
}
