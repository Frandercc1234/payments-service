-- Montos siempre en centavos (BIGINT). Las restricciones CHECK son la última defensa:
-- aunque el código tuviera un bug, la base no permite saldos negativos ni montos <= 0.

CREATE TABLE accounts (
    id          UUID         PRIMARY KEY,
    owner_name  VARCHAR(255) NOT NULL,
    currency    VARCHAR(3)   NOT NULL CHECK (currency IN ('CRC', 'USD')),
    balance     BIGINT       NOT NULL CHECK (balance >= 0),
    created_at  TIMESTAMPTZ  NOT NULL
);

CREATE TABLE payments (
    id                      UUID        PRIMARY KEY,
    source_account_id       UUID        NOT NULL REFERENCES accounts (id),
    destination_account_id  UUID        NOT NULL REFERENCES accounts (id),
    amount                  BIGINT      NOT NULL CHECK (amount > 0),
    currency                VARCHAR(3)  NOT NULL CHECK (currency IN ('CRC', 'USD')),
    status                  VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'FAILED')),
    created_at              TIMESTAMPTZ NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL,
    CHECK (source_account_id <> destination_account_id)
);

-- Libro mayor de doble entrada, solo se agregan filas.
-- Invariante: por cada payment_id, SUM(CREDIT) - SUM(DEBIT) = 0.
CREATE TABLE ledger_entries (
    id          UUID        PRIMARY KEY,
    payment_id  UUID        NOT NULL REFERENCES payments (id),
    account_id  UUID        NOT NULL REFERENCES accounts (id),
    direction   VARCHAR(6)  NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    kind        VARCHAR(8)  NOT NULL CHECK (kind IN ('PAYMENT', 'REVERSAL')),
    amount      BIGINT      NOT NULL CHECK (amount > 0),
    currency    VARCHAR(3)  NOT NULL CHECK (currency IN ('CRC', 'USD')),
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_ledger_payment ON ledger_entries (payment_id);
CREATE INDEX idx_ledger_account ON ledger_entries (account_id);

-- Idempotencia: la PK en la clave resuelve la carrera entre dos requests simultáneas.
CREATE TABLE idempotency_keys (
    idempotency_key  VARCHAR(255) PRIMARY KEY,
    request_hash     VARCHAR(64)  NOT NULL,
    response_status  INTEGER,
    response_body    TEXT,
    created_at       TIMESTAMPTZ  NOT NULL
);

-- Eventos del proveedor: la PK en event_id hace que un reenvío no tenga efecto.
CREATE TABLE webhook_events (
    event_id     VARCHAR(100) PRIMARY KEY,
    payment_id   UUID         REFERENCES payments (id),
    event_type   VARCHAR(50)  NOT NULL,
    payload      TEXT         NOT NULL,
    received_at  TIMESTAMPTZ  NOT NULL
);
