package cr.portfolio.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cr.portfolio.payments.config.OutboxProperties;
import cr.portfolio.payments.domain.OutboxEvent;
import cr.portfolio.payments.repository.OutboxEventRepository;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Semántica de fallos del relay, sin Spring ni broker: lo que NO debe marcarse como publicado. */
class OutboxPublisherTest {

    private final OutboxEventRepository repo = mock(OutboxEventRepository.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final OutboxPublisher publisher = new OutboxPublisher(repo, rabbit,
            new OutboxProperties("payments.events", 50, Duration.ofSeconds(1), Duration.ofMillis(500)));

    private static final Answer<Object> ACK = invocation -> {
        CorrelationData correlation = invocation.getArgument(3);
        correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
        return null;
    };

    @Test
    void stopsAtTheFirstFailureAndKeepsEarlierEventsPublished() {
        var e1 = event("payment.created");
        var e2 = event("payment.confirmed");
        var e3 = event("payment.failed");
        when(repo.lockPending(anyInt())).thenReturn(List.of(e1, e2, e3));
        doAnswer(ACK).doThrow(new AmqpConnectException(new IOException("broker caído")))
                .when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        int published = publisher.publishPending();

        assertThat(published).isEqualTo(1);
        assertThat(e1.getPublishedAt()).isNotNull();
        assertThat(e2.getPublishedAt()).as("falló: sigue pendiente").isNull();
        assertThat(e3.getPublishedAt()).as("no se intenta después de un fallo, para no reordenar").isNull();
    }

    @Test
    void aNackLeavesTheEventPending() {
        var e1 = event("payment.created");
        when(repo.lockPending(anyInt())).thenReturn(List.of(e1));
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "disco lleno"));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        assertThat(publisher.publishPending()).isZero();
        assertThat(e1.getPublishedAt()).isNull();
    }

    @Test
    void anUnroutableMessageLeavesTheEventPending() {
        var e1 = event("payment.created");
        when(repo.lockPending(anyInt())).thenReturn(List.of(e1));
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            CorrelationData correlation = invocation.getArgument(3);
            correlation.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE", "payments.events", "payment.created"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        assertThat(publisher.publishPending()).isZero();
        assertThat(e1.getPublishedAt()).as("el broker lo devolvió: no se pierde, se reintenta").isNull();
    }

    @Test
    void noConfirmationWithinTheTimeoutLeavesTheEventPending() {
        var e1 = event("payment.created");
        when(repo.lockPending(anyInt())).thenReturn(List.of(e1));
        // el mock nunca completa la confirmación: simula un broker que no responde

        assertThat(publisher.publishPending()).isZero();
        assertThat(e1.getPublishedAt()).isNull();
    }

    @Test
    void theMessageCarriesTheEventIdAsMessageIdAndIsPersistent() {
        var e1 = event("payment.confirmed");
        when(repo.lockPending(anyInt())).thenReturn(List.of(e1));
        doAnswer(ACK).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        publisher.publishPending();

        var message = ArgumentCaptor.forClass(Message.class);
        var routingKey = ArgumentCaptor.forClass(String.class);
        verify(rabbit).send(anyString(), routingKey.capture(), message.capture(), any(CorrelationData.class));
        assertThat(routingKey.getValue()).isEqualTo("payment.confirmed");
        assertThat(message.getValue().getMessageProperties().getMessageId()).isEqualTo(e1.getId().toString());
        assertThat(message.getValue().getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(new String(message.getValue().getBody())).isEqualTo(e1.getPayload());
    }

    @Test
    void anEmptyOutboxDoesNothing() {
        when(repo.lockPending(anyInt())).thenReturn(List.of());

        assertThat(publisher.publishPending()).isZero();
        verify(rabbit, never()).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    private static OutboxEvent event(String type) {
        var id = UUID.randomUUID();
        return OutboxEvent.of(id, "payment", UUID.randomUUID(), type, "{\"eventId\":\"" + id + "\"}", Instant.now());
    }
}
