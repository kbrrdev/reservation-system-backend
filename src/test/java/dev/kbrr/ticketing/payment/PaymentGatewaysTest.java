package dev.kbrr.ticketing.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kbrr.ticketing.country.CountryProperties;
import dev.kbrr.ticketing.country.CountryProperties.Country;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class PaymentGatewaysTest {

	private static PaymentGateway stub(String provider) {
		return new PaymentGateway() {
			public String provider() { return provider; }
			public Checkout createCheckout(CheckoutRequest req) { throw new UnsupportedOperationException(); }
			public void expireCheckout(String sessionId) { throw new UnsupportedOperationException(); }
			public PaymentNotification parseWebhook(byte[] body, HttpHeaders headers) { throw new UnsupportedOperationException(); }
		};
	}

	private static CountryProperties countries(String provider) {
		return new CountryProperties(Map.of("PH", new Country(true, "PHP", provider, 2000)));
	}

	@Test
	void routesByProvider() {
		PaymentGateway a = stub("a");
		PaymentGateways gateways = new PaymentGateways(List.of(a, stub("b")), countries("a"));
		assertThat(gateways.get("a")).isSameAs(a);
		assertThat(gateways.find("zz")).isEmpty();
		assertThatThrownBy(() -> gateways.get("zz")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void duplicateProviderFailsStartup() {
		assertThatThrownBy(() -> new PaymentGateways(List.of(stub("a"), stub("a")), countries("a")))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void countryWithoutGatewayFailsStartup() {
		assertThatThrownBy(() -> new PaymentGateways(List.of(stub("a")), countries("paymongo")))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("paymongo");
	}

	@Test
	void disabledCountryStillNeedsGateway() {
		CountryProperties c = new CountryProperties(Map.of("US", new Country(false, "USD", "x", 50)));
		assertThatThrownBy(() -> new PaymentGateways(List.of(stub("a")), c)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("x");
	}
}
