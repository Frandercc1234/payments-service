-- Consumidor idempotente: processed_messages recuerda qué mensajes ya se atendieron.
-- Se escribe en la MISMA transacción que la notificación, así que "procesado" y "efecto" van siempre juntos.
CREATE TABLE processed_messages (
    message_id    VARCHAR(100) PRIMARY KEY,   -- el messageId de RabbitMQ (= eventId del outbox del productor)
    processed_at  TIMESTAMPTZ  NOT NULL
);

CREATE TABLE notifications (
    id          UUID         PRIMARY KEY,
    message_id  VARCHAR(100) NOT NULL UNIQUE REFERENCES processed_messages (message_id),
    payment_id  UUID         NOT NULL,
    event_type  VARCHAR(50)  NOT NULL,
    channel     VARCHAR(20)  NOT NULL,         -- simulado: "LOG"
    message     TEXT         NOT NULL,
    status      VARCHAR(10)  NOT NULL CHECK (status IN ('SENT', 'FAILED')),
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_notifications_payment ON notifications (payment_id, created_at);
