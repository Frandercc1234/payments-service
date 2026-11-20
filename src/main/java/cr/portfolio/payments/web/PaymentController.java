package cr.portfolio.payments.web;

import cr.portfolio.payments.service.IdempotentPaymentService;
import cr.portfolio.payments.service.PaymentService;
import cr.portfolio.payments.web.dto.PaymentDtos.CreatePaymentRequest;
import cr.portfolio.payments.web.dto.PaymentDtos.PaymentResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService payments;
    private final IdempotentPaymentService idempotentPayments;

    public PaymentController(PaymentService payments, IdempotentPaymentService idempotentPayments) {
        this.payments = payments;
        this.idempotentPayments = idempotentPayments;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        var result = idempotentPayments.create(idempotencyKey, request);
        var response = ResponseEntity.status(result.status())
                .location(URI.create("/payments/" + result.body().id()));
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(result.body());
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable UUID id) {
        return PaymentResponse.from(payments.get(id));
    }
}
