package cr.portfolio.notifications.config;

import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * El consumidor declara SU cola y su cola de mensajes muertos (DLQ). El productor solo declara el exchange.
 *
 * <pre>
 * payments.events (topic) --payment.#--> notifications.payment-events
 *                                              | rechazado / reintentos agotados
 *                                              v
 *                    notifications.dlx (direct) --> notifications.payment-events.dlq
 * </pre>
 */
@Configuration
public class RabbitTopology {

    public static final String QUEUE = "notifications.payment-events";
    public static final String DEAD_LETTER_EXCHANGE = "notifications.dlx";
    public static final String DEAD_LETTER_QUEUE = "notifications.payment-events.dlq";
    private static final String DEAD_LETTER_KEY = "payment-events.dead";

    @Bean
    TopicExchange paymentEventsExchange(@Value("${app.events.exchange}") String name) {
        return new TopicExchange(name, true, false);   // mismos argumentos que el productor: declarar dos veces es inocuo
    }

    @Bean
    Queue notificationsQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArguments(Map.of(
                        "x-dead-letter-exchange", DEAD_LETTER_EXCHANGE,
                        "x-dead-letter-routing-key", DEAD_LETTER_KEY))
                .build();
    }

    @Bean
    Binding notificationsBinding(Queue notificationsQueue, TopicExchange paymentEventsExchange) {
        return BindingBuilder.bind(notificationsQueue).to(paymentEventsExchange).with("payment.#");
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding deadLetterBinding(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(DEAD_LETTER_KEY);
    }
}
