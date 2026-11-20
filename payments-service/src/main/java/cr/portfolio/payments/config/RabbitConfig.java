package cr.portfolio.payments.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Solo declara el exchange (el "buzón de entrada" de eventos de pago). Las COLAS las declara cada consumidor
 * para sí mismo: el productor no sabe ni le importa quién escucha.
 */
@Configuration
public class RabbitConfig {

    @Bean
    TopicExchange paymentEventsExchange(OutboxProperties properties) {
        return new TopicExchange(properties.exchange(), true, false);   // durable, sin auto-borrado
    }
}
