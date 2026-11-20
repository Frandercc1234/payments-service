package cr.portfolio.payments.web;

import cr.portfolio.payments.service.WebhookService;
import cr.portfolio.payments.web.dto.WebhookDtos.ProviderEvent;
import cr.portfolio.payments.web.dto.WebhookDtos.WebhookAck;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Webhooks")
@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private final WebhookRequestReader reader;
    private final WebhookService webhooks;

    public WebhookController(WebhookRequestReader reader, WebhookService webhooks) {
        this.reader = reader;
        this.webhooks = webhooks;
    }

    /**
     * El cuerpo llega como bytes crudos a propósito: la firma se calcula sobre el texto exacto que mandó
     * el proveedor. Si primero lo convirtiéramos a objeto y lo volviéramos a serializar, cualquier
     * diferencia de espacios u orden de campos invalidaría una firma legítima.
     */
    @Operation(summary = "Recibe eventos del proveedor de pagos (simulado)",
            description = "Exige X-Signature: sha256=<HMAC-SHA256 hex del cuerpo exacto con el secreto compartido>. "
                    + "Firma ausente o inválida: 401. Un eventId repetido responde 200 'duplicate' sin ningún efecto.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(schema = @Schema(implementation = ProviderEvent.class))))
    @PostMapping("/provider")
    public WebhookAck provider(@RequestBody byte[] body,
            @RequestHeader(value = "X-Signature", required = false) String signature) {
        var event = reader.read(body, signature);       // firma, parseo y validación (sin transacción)
        return WebhookAck.from(webhooks.handle(event));  // efecto de negocio (transaccional)
    }
}
