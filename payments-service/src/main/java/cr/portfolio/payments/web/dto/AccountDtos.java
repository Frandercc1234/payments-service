package cr.portfolio.payments.web.dto;

import cr.portfolio.payments.domain.Account;
import cr.portfolio.payments.domain.Currency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class AccountDtos {

    private AccountDtos() {
    }

    /** {@code initialBalance} en centavos (long), nunca decimales. */
    public record CreateAccountRequest(
            @NotBlank @Size(max = 255) String ownerName,
            @NotNull Currency currency,
            @PositiveOrZero long initialBalance) {
    }

    public record AccountResponse(UUID id, String ownerName, Currency currency, long balance) {
        public static AccountResponse from(Account account) {
            return new AccountResponse(account.getId(), account.getOwnerName(), account.getCurrency(), account.getBalance());
        }
    }
}
