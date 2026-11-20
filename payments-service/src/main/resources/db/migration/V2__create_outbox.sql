-- Outbox transaccional: el evento se guarda en la MISMA transacción que el cambio de negocio.
-- Un proceso aparte (outbox-relay) lo publica al broker y marca published_at.
-- Si la transacción de negocio hace rollback, el evento desaparece con ella; si hace commit, el evento
-- queda garantizado aunque el broker esté caído en ese momento.
CREATE TABLE outbox_events (
    id              UUID         PRIMARY KEY,           -- también es el eventId que ve el consumidor
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    UUID         NOT NULL,
    event_type      VARCHAR(50)  NOT NULL,
    payload         TEXT         NOT NULL,              -- JSON del evento
    created_at      TIMESTAMPTZ  NOT NULL,
    published_at    TIMESTAMPTZ
);

-- Índice parcial: solo las filas pendientes. Es lo único que consulta el relay, y se mantiene pequeño.
CREATE INDEX idx_outbox_pending ON outbox_events (created_at) WHERE published_at IS NULL;
