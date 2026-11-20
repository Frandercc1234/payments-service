package cr.portfolio.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Todo error, venga de donde venga, debe salir como application/problem+json con type/title/status/detail. */
class ErrorFormatIntegrationTest extends AbstractIntegrationTest {

    @Test
    void notFoundResource() {
        assertProblem(rest.getForEntity("/accounts/" + UUID.randomUUID(), Map.class), HttpStatus.NOT_FOUND);
    }

    @Test
    void unknownRoute() {
        assertProblem(rest.getForEntity("/no-existe", Map.class), HttpStatus.NOT_FOUND);
    }

    @Test
    void methodNotAllowed() {
        assertProblem(rest.getForEntity("/payments", Map.class), HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void unsupportedMediaType() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        var response = rest.exchange("/accounts", HttpMethod.POST, new HttpEntity<>("hola", headers), Map.class);
        assertProblem(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void malformedJson() {
        assertProblem(post("/accounts", "{no es json"), HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownFieldIsRejected() {
        assertProblem(post("/accounts", "{\"ownerName\":\"Ana\",\"currency\":\"CRC\",\"initialBalance\":0,\"extra\":1}"),
                HttpStatus.BAD_REQUEST);
    }

    @Test
    void unsupportedCurrency() {
        assertProblem(post("/accounts", "{\"ownerName\":\"Ana\",\"currency\":\"EUR\",\"initialBalance\":0}"),
                HttpStatus.BAD_REQUEST);
    }

    @Test
    void invalidPathVariable() {
        assertProblem(rest.getForEntity("/accounts/no-es-uuid", Map.class), HttpStatus.BAD_REQUEST);
    }

    @Test
    void bodyValidationListsEveryInvalidField() {
        var response = post("/accounts", "{\"ownerName\":\"\",\"currency\":null,\"initialBalance\":-5}");

        assertProblem(response, HttpStatus.BAD_REQUEST);
        assertThat((java.util.List<?>) response.getBody().get("errors")).hasSize(3);
    }

    @Test
    void negativeInitialBalanceIsRejected() {
        assertProblem(post("/accounts", "{\"ownerName\":\"Ana\",\"currency\":\"CRC\",\"initialBalance\":-1}"),
                HttpStatus.BAD_REQUEST);
    }

    // ---------- helpers ----------

    private ResponseEntity<Map> post(String path, String json) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), Map.class);
    }

    private void assertProblem(ResponseEntity<Map> response, HttpStatus expected) {
        assertThat(response.getStatusCode()).isEqualTo(expected);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(response.getBody()).containsKeys("type", "title", "status", "detail");
        assertThat(response.getBody().get("status")).isEqualTo(expected.value());
    }
}
