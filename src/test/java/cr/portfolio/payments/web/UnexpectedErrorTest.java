package cr.portfolio.payments.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class UnexpectedErrorTest {

    @Test
    void internalDetailsNeverReachTheClient() {
        var problem = new ApiExceptionHandler()
                .unexpected(new IllegalStateException("password=hunter2 relation \"accounts\" does not exist"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getTitle()).isEqualTo("Error interno");
        assertThat(problem.getDetail()).doesNotContain("hunter2").doesNotContain("accounts");
    }
}
