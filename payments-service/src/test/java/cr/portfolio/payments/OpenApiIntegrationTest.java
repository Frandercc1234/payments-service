package cr.portfolio.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class OpenApiIntegrationTest extends AbstractIntegrationTest {

    @Test
    @SuppressWarnings("unchecked")
    void apiDocsListEveryEndpoint() {
        var response = rest.getForEntity("/v3/api-docs", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var paths = (Map<String, Object>) response.getBody().get("paths");
        assertThat(paths).containsKeys("/accounts", "/accounts/{id}", "/payments", "/payments/{id}", "/webhooks/provider");
    }

    @Test
    void swaggerUiIsServed() {
        var response = rest.getForEntity("/swagger-ui/index.html", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Swagger UI");
    }
}
