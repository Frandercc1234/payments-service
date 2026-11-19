package cr.portfolio.payments;

import org.springframework.boot.test.context.SpringBootTest;
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

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
