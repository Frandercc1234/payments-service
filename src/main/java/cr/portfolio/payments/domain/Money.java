package cr.portfolio.payments.domain;

import java.util.Objects;

/**
 * Monto positivo en la unidad mínima de la moneda (centavos), siempre como {@code long}.
 * Nunca se usa {@code double}: el punto flotante no representa 0.10 exactamente.
 */
public record Money(long cents, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (cents <= 0) {
            throw new IllegalArgumentException("El monto debe ser mayor que cero");
        }
    }
}
