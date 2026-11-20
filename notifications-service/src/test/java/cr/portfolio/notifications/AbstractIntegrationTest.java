package cr.portfolio.notifications;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * PostgreSQL y RabbitMQ reales (Testcontainers), compartidos por todas las pruebas de integración.
 * Se arrancan una sola vez en un bloque estático: Spring cachea el contexto entre clases de prueba y
 * un contenedor que se detiene y se recrea dejaría al contexto apuntando a un puerto que ya no existe.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    static {
        POSTGRES.start();
        RABBIT.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        // Reintentos rápidos para que las pruebas de la DLQ no tarden segundos de más.
        registry.add("spring.rabbitmq.listener.simple.retry.initial-interval", () -> "100ms");
        registry.add("spring.rabbitmq.listener.simple.retry.multiplier", () -> "1.0");
    }
}
