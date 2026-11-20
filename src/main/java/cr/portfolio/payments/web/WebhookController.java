package cr.portfolio.payments.web;

import cr.portfolio.payments.service.WebhookService;
import cr.portfolio.payments.web.dto.WebhookDtos.WebhookAck;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private final WebhookService webhooks;

    public WebhookController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    /**
     * El cuerpo llega como bytes crudos a propósito: la firma se calcula sobre el texto exacto que mandó
     * el proveedor. Si primero lo convirtiéramos a objeto y lo volviéramos a serializar, cualquier
     * diferencia de espacios u orden de campos invalidaría una firma legítima.
     */
    @PostMapping("/provider")
    public WebhookAck provider(@RequestBody byte[] body,
            @RequestHeader(value = "X-Signature", required = false) String signature) {
        return webhooks.handle(body, signature);
    }
}
