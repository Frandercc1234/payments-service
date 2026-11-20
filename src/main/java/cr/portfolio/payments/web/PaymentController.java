package cr.portfolio.payments.web;

import cr.portfolio.payments.domain.Money;
import cr.portfolio.payments.service.PaymentService;
import cr.portfolio.payments.web.dto.PaymentDtos.CreatePaymentRequest;
import cr.portfolio.payments.web.dto.PaymentDtos.PaymentResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(@Valid @RequestBody CreatePaymentRequest request) {
        var payment = payments.create(request.sourceAccountId(), request.destinationAccountId(),
                new Money(request.amount(), request.currency()));
        return ResponseEntity.created(URI.create("/payments/" + payment.getId()))
                .body(PaymentResponse.from(payment));
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable UUID id) {
        return PaymentResponse.from(payments.get(id));
    }
}
