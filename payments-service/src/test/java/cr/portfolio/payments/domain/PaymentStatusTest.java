package cr.portfolio.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentStatusTest {

    @Test
    void pendingCanBeConfirmedOrFailed() {
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.CONFIRMED)).isTrue();
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.FAILED)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void confirmedAndFailedAreTerminal(PaymentStatus target) {
        assertThat(PaymentStatus.CONFIRMED.canTransitionTo(target)).isFalse();
        assertThat(PaymentStatus.FAILED.canTransitionTo(target)).isFalse();
    }

    @Test
    void pendingCannotTransitionToItself() {
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.PENDING)).isFalse();
    }

    @Test
    void invalidTransitionThrowsWithBothStatesInMessage() {
        assertThatThrownBy(() -> PaymentStatus.CONFIRMED.requireTransitionTo(PaymentStatus.FAILED))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("CONFIRMED")
                .hasMessageContaining("FAILED");
    }

    @Test
    void paymentAppliesValidTransitionAndRejectsInvalidOne() {
        var payment = Payment.create(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                new Money(1_000, Currency.CRC));

        payment.transitionTo(PaymentStatus.CONFIRMED);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThatThrownBy(() -> payment.transitionTo(PaymentStatus.FAILED))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
    }
}
