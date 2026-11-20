package cr.portfolio.payments.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI paymentsOpenApi() {
        return new OpenAPI().info(new Info()
                .title("payments-service")
                .version("0.1.0")
                .description("""
                        Servicio de pagos de portafolio: cuentas, pagos idempotentes con ledger de doble entrada \
                        y webhook de un proveedor simulado. Los montos son enteros en centavos (CRC o USD). \
                        No mueve dinero real."""));
    }
}
