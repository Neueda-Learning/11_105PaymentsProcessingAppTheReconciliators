package com.paymentProccessing.backend.controller;

import com.paymentProccessing.backend.dto.*;
import com.paymentProccessing.backend.enums.PaymentStatus;
import com.paymentProccessing.backend.service.PaymentService;
import com.paymentProccessing.backend.service.RiskAssessmentResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Create, retrieve and track payments through their lifecycle")
public class PaymentController {

    private final PaymentService paymentService;

    @Operation(summary = "Create a new payment (idempotent via idempotencyKey)")
    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(@Valid @RequestBody CreatePaymentRequest request) {
        PaymentResponse response = paymentService.createPayment(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Run fraud/risk validation checks for a prospective payment without creating it. " +
            "Returns the assigned risk level (LOW/MEDIUM/HIGH), numeric risk score and the specific reasons flagged.")
    @PostMapping("/validate-risk")
    public ResponseEntity<RiskAssessmentResponse> validateRisk(@Valid @RequestBody CreatePaymentRequest request) {
        RiskAssessmentResult result = paymentService.assessRisk(request);
        return ResponseEntity.ok(RiskAssessmentResponse.builder()
                .riskLevel(result.getRiskLevel().name())
                .riskScore(result.getRiskScore())
                .reasons(result.getReasons())
                .build());
    }

    @Operation(summary = "Get a payment by id")
    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable String id) {
        return ResponseEntity.ok(paymentService.getPayment(id));
    }

    @Operation(summary = "List / search / filter payments with pagination")
    @GetMapping
    public ResponseEntity<PageResponse<PaymentResponse>> listPayments(
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String direction) {

        Sort sort = Sort.by(Sort.Direction.fromString(direction), sortBy);
        Pageable pageable = PageRequest.of(page, size, sort);
        return ResponseEntity.ok(paymentService.listPayments(status, search, pageable));
    }

    @Operation(summary = "Get the full payment audit trail (every status transition and audit action) for a payment")
    @GetMapping("/{id}/history")
    public ResponseEntity<List<StatusHistoryResponse>> getHistory(@PathVariable String id) {
        return ResponseEntity.ok(paymentService.getHistory(id));
    }

    @Operation(summary = "Manually transition a payment's status (subject to the payment state machine)")
    @PatchMapping("/{id}/status")
    public ResponseEntity<PaymentResponse> updateStatus(@PathVariable String id,
                                                         @Valid @RequestBody StatusUpdateRequest request) {
        PaymentResponse response = paymentService.updateStatus(id, request.getStatus(), "USER", request.getNotes(), null, null);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Retry a FAILED payment: resets it to CREATED and reschedules processing, recording a Retry Requested audit event")
    @PostMapping("/{id}/retry")
    public ResponseEntity<PaymentResponse> retryPayment(@PathVariable String id) {
        return ResponseEntity.ok(paymentService.retryPayment(id, "USER"));
    }

    @Operation(summary = "Check whether a payment with the same source/destination account, amount, currency and reference " +
            "was created in the last 2 minutes (duplicate submission detection)")
    @GetMapping("/duplicate-check")
    public ResponseEntity<PaymentResponse> checkDuplicate(
            @RequestParam String sourceAccount,
            @RequestParam String destinationAccount,
            @RequestParam BigDecimal amount,
            @RequestParam String currency,
            @RequestParam(required = false) String reference) {
        return paymentService.checkDuplicate(sourceAccount, destinationAccount, amount, currency, reference)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }
}

