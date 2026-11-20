package cr.portfolio.payments;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * Además de PostgreSQL, levanta un RabbitMQ real. Solo lo extienden las pruebas que lo necesitan, para que
 * el resto no pague el arranque del broker. Mismo patrón que la base: un contenedor único para toda la ejecución.
 */
public abstract class AbstractRabbitIntegrationTest extends AbstractIntegrationTest {

    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    static {
        RABBIT.start();
    }

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }
}
