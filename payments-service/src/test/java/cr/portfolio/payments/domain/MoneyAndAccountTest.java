package cr.portfolio.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyAndAccountTest {

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void moneyMustBePositive(long cents) {
        assertThatThrownBy(() -> new Money(cents, Currency.CRC))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void moneyRequiresCurrency() {
        assertThatThrownBy(() -> new Money(100, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void accountDebitAndCreditMoveExactCents() {
        var account = Account.open("Ana", Currency.CRC, 10_000);

        account.debit(new Money(2_500, Currency.CRC));
        account.credit(new Money(100, Currency.CRC));

        assertThat(account.getBalance()).isEqualTo(7_600L);
    }

    @Test
    void debitCanDrainAccountToExactlyZero() {
        var account = Account.open("Ana", Currency.USD, 500);

        account.debit(new Money(500, Currency.USD));

        assertThat(account.getBalance()).isZero();
    }

    @Test
    void debitBeyondBalanceIsRejectedAndLeavesBalanceUntouched() {
        var account = Account.open("Ana", Currency.CRC, 1_000);

        assertThatThrownBy(() -> account.debit(new Money(1_001, Currency.CRC)))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(account.getBalance()).isEqualTo(1_000L);
    }

    @Test
    void operatingWithAnotherCurrencyIsRejected() {
        var account = Account.open("Ana", Currency.CRC, 1_000);

        assertThatThrownBy(() -> account.debit(new Money(1, Currency.USD)))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> account.credit(new Money(1, Currency.USD)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void creditOverflowIsDetectedInsteadOfWrappingToNegative() {
        var account = Account.open("Ana", Currency.CRC, Long.MAX_VALUE);

        assertThatThrownBy(() -> account.credit(new Money(1, Currency.CRC)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void accountCannotBeOpenedWithNegativeBalance() {
        assertThatThrownBy(() -> Account.open("Ana", Currency.CRC, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void paymentBetweenSameAccountIsRejected() {
        var id = java.util.UUID.randomUUID();
        assertThatThrownBy(() -> Payment.create(id, id, new Money(1, Currency.CRC)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
