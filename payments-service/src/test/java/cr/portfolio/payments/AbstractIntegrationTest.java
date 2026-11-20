package cr.portfolio.payments;

import cr.portfolio.payments.domain.Account;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base de las pruebas de integración: un único PostgreSQL real en Docker (Testcontainers)
 * compartido por todas las clases de prueba.
 *
 * <p>Se arranca una sola vez en un bloque estático en lugar de usar {@code @Container}: Spring
 * cachea el contexto entre clases de prueba, y si cada clase detuviera y recreara el contenedor,
 * el contexto cacheado apuntaría a un puerto que ya no existe. Testcontainers (Ryuk) lo elimina
 * al terminar la JVM.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected TestRestTemplate rest;

    /** POST /payments con el header Idempotency-Key. El cuerpo puede ser un Map o un JSON ya armado (String). */
    protected ResponseEntity<Map> postPayment(Object body, String idempotencyKey) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return rest.exchange("/payments", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    protected static Map<String, Object> paymentBody(Account from, Account to, long amount, String currency) {
        return Map.of("sourceAccountId", from.getId(), "destinationAccountId", to.getId(),
                "amount", amount, "currency", currency);
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
