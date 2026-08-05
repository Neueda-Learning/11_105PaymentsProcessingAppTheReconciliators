package com.paymentProccessing.backend.service;

import com.paymentProccessing.backend.dto.CreatePaymentRequest;
import com.paymentProccessing.backend.dto.PageResponse;
import com.paymentProccessing.backend.dto.PaymentResponse;
import com.paymentProccessing.backend.dto.StatusHistoryResponse;
import com.paymentProccessing.backend.entity.FraudValidation;
import com.paymentProccessing.backend.entity.Payment;
import com.paymentProccessing.backend.entity.PaymentStatusHistory;
import com.paymentProccessing.backend.enums.ErrorCode;
import com.paymentProccessing.backend.enums.FraudStatus;
import com.paymentProccessing.backend.enums.PaymentMethod;
import com.paymentProccessing.backend.enums.PaymentStatus;
import com.paymentProccessing.backend.enums.RiskLevel;
import com.paymentProccessing.backend.exception.FraudRiskBlockedException;
import com.paymentProccessing.backend.exception.InvalidStatusTransitionException;
import com.paymentProccessing.backend.exception.PaymentNotFoundException;
import com.paymentProccessing.backend.repository.FraudValidationRepository;
import com.paymentProccessing.backend.repository.PaymentRepository;
import com.paymentProccessing.backend.repository.PaymentStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentStatusHistoryRepository historyRepository;
    private final PaymentValidationService validationService;
    private final PaymentSimulationService simulationService;
    private final RiskAssessmentService riskAssessmentService;
    private final FraudValidationRepository fraudValidationRepository;

    /**
     * Creates a new payment. If an idempotencyKey is supplied and a payment
     * already exists with that key, the EXISTING payment is returned instead
     * of creating a duplicate (idempotent create).
     */
    @Transactional
    public PaymentResponse createPayment(CreatePaymentRequest request) {
        if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
            var existing = paymentRepository.findByIdempotencyKey(request.getIdempotencyKey());
            if (existing.isPresent()) {
                return PaymentResponse.from(existing.get());
            }
        }

        validationService.validate(request);

        // ---- Fraud / risk validation (server-side, cannot be bypassed by the client) ----
        RiskAssessmentResult risk = riskAssessmentService.assess(request);
        String candidateId = java.util.UUID.randomUUID().toString();

        if (risk.getRiskLevel() == RiskLevel.HIGH) {
            saveFraudValidation(candidateId, risk);
            throw new FraudRiskBlockedException(risk.getRiskScore(), risk.getRiskLevel().name(), risk.getReasons());
        }

        Payment payment = Payment.builder()
                .id(candidateId)
                .idempotencyKey(request.getIdempotencyKey())
                .amount(request.getAmount())
                .currency(request.getCurrency().toUpperCase())
                .sourceAccount(request.getSourceAccount())
                .destinationAccount(request.getDestinationAccount())
                .paymentMethod(request.getPaymentMethod())
                .status(PaymentStatus.CREATED)
                .reference(request.getReference())
                .riskScore(risk.getRiskScore())
                .riskLevel(risk.getRiskLevel())
                .fraudStatus(risk.getRiskLevel() == RiskLevel.MEDIUM ? FraudStatus.FLAGGED : FraudStatus.CLEARED)
                .build();

        applyMethodDetails(payment, request);

        payment = paymentRepository.save(payment);

        recordHistory(payment, null, PaymentStatus.CREATED, "USER", "Payment created", "Payment Created");

        Payment saved = paymentRepository.save(payment);

        saveFraudValidation(saved.getId(), risk);

        simulationService.scheduleProcessing(saved.getId());

        return PaymentResponse.from(saved);
    }

    /**
     * Runs the same fraud/risk assessment used during payment creation without
     * persisting anything - used by the POST /payments/validate-risk pre-check
     * endpoint so the UI can show the risk result before the user confirms.
     */
    @Transactional(readOnly = true)
    public RiskAssessmentResult assessRisk(CreatePaymentRequest request) {
        return riskAssessmentService.assess(request);
    }

    private void saveFraudValidation(String paymentId, RiskAssessmentResult risk) {
        if (risk.getReasons().isEmpty()) {
            return;
        }
        for (String reason : risk.getReasons()) {
            fraudValidationRepository.save(FraudValidation.builder()
                    .paymentId(paymentId)
                    .riskScore(risk.getRiskScore())
                    .riskLevel(risk.getRiskLevel())
                    .reason(reason)
                    .build());
        }
    }

    private void applyMethodDetails(Payment payment, CreatePaymentRequest request) {
        if (request.getPaymentMethod() == PaymentMethod.UPI && request.getUpiDetails() != null) {
            payment.setUpiId(request.getUpiDetails().getUpiId());
        } else if (request.getPaymentMethod() == PaymentMethod.CARD && request.getCardDetails() != null) {
            String number = request.getCardDetails().getCardNumber();
            payment.setCardNumberMasked(maskCard(number));
            payment.setCardHolderName(request.getCardDetails().getCardHolderName());
            payment.setCardExpiry(request.getCardDetails().getCardExpiry());
            payment.setCardNetwork(detectCardNetwork(number));
        } else if (request.getPaymentMethod() == PaymentMethod.NETBANKING && request.getNetBankingDetails() != null) {
            payment.setBankName(request.getNetBankingDetails().getBankName());
            payment.setBankAccountType(request.getNetBankingDetails().getBankAccountType());
        } else if ((request.getPaymentMethod() == PaymentMethod.NEFT
                || request.getPaymentMethod() == PaymentMethod.RTGS
                || request.getPaymentMethod() == PaymentMethod.IMPS)
                && request.getBankTransferDetails() != null) {
            var details = request.getBankTransferDetails();
            payment.setSenderBankName(details.getSenderBank());
            payment.setBeneficiaryBankName(details.getBeneficiaryBank());
            payment.setIfscCode(details.getIfscCode());
            payment.setMobileOrAccountNumber(details.getMobileOrAccountNumber());
        } else if ((request.getPaymentMethod() == PaymentMethod.SWIFT
                || request.getPaymentMethod() == PaymentMethod.WIRE_TRANSFER)
                && request.getInternationalTransferDetails() != null) {
            var details = request.getInternationalTransferDetails();
            payment.setSenderBankName(details.getSenderBank());
            payment.setBeneficiaryBankName(details.getBeneficiaryBank());
            payment.setSwiftBicCode(details.getSwiftBicCode());
            payment.setBeneficiaryCountry(details.getBeneficiaryCountry());
            payment.setPaymentPurpose(details.getPaymentPurpose());
            payment.setRoutingNumber(details.getRoutingNumber());
        }
    }

    private String maskCard(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 4) {
            return "****";
        }
        String last4 = cardNumber.substring(cardNumber.length() - 4);
        return "**** **** **** " + last4;
    }

    private String detectCardNetwork(String cardNumber) {
        if (cardNumber == null || cardNumber.isBlank()) return "UNKNOWN";
        if (cardNumber.startsWith("4")) return "VISA";
        if (cardNumber.matches("^5[1-5].*") || cardNumber.matches("^2[2-7].*")) return "MASTERCARD";
        if (cardNumber.startsWith("6")) return "RUPAY";
        if (cardNumber.startsWith("34") || cardNumber.startsWith("37")) return "AMEX";
        return "UNKNOWN";
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(String id) {
        Payment payment = findOrThrow(id);
        return PaymentResponse.from(payment);
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> listPayments(PaymentStatus status, String search, Pageable pageable) {
        Page<Payment> page = paymentRepository.search(status, (search == null || search.isBlank()) ? null : search, pageable);
        List<PaymentResponse> content = page.getContent().stream().map(PaymentResponse::from).toList();
        return PageResponse.<PaymentResponse>builder()
                .content(content)
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .build();
    }

    @Transactional(readOnly = true)
    public List<StatusHistoryResponse> getHistory(String id) {
        findOrThrow(id); // ensures 404 if missing
        return historyRepository.findByPaymentIdOrderByChangedAtAsc(id).stream()
                .map(StatusHistoryResponse::from)
                .toList();
    }

    /**
     * Manually / programmatically transition a payment's status, validating
     * against the allowed state machine and recording an audit entry.
     */
    @Transactional
    public PaymentResponse updateStatus(String id, PaymentStatus newStatus, String triggeredBy, String notes,
                                         ErrorCode errorCode, String errorMessage) {
        return updateStatus(id, newStatus, triggeredBy, notes, errorCode, errorMessage, null);
    }

    @Transactional
    public PaymentResponse updateStatus(String id, PaymentStatus newStatus, String triggeredBy, String notes,
                                         ErrorCode errorCode, String errorMessage, String action) {
        Payment payment = findOrThrow(id);
        PaymentStatus current = payment.getStatus();

        if (current == newStatus) {
            return PaymentResponse.from(payment); // no-op / retry safe
        }

        if (!PaymentStateMachine.isValidTransition(current, newStatus)) {
            throw new InvalidStatusTransitionException(current, newStatus);
        }

        payment.setStatus(newStatus);
        if (newStatus == PaymentStatus.FAILED) {
            payment.setErrorCode(errorCode != null ? errorCode : ErrorCode.PROCESSING_ERROR);
            payment.setErrorMessage(errorMessage != null ? errorMessage : "Payment processing failed");
        }

        Payment saved = paymentRepository.save(payment);
        recordHistory(saved, current, newStatus, triggeredBy, notes, action != null ? action : defaultActionFor(newStatus));
        return PaymentResponse.from(saved);
    }

    /**
     * Resets a FAILED payment back to CREATED and reschedules processing,
     * recording a "Retry Requested" audit event. Bypasses the normal state
     * machine transition rules (same as the initial CREATED assignment does),
     * since FAILED is otherwise a terminal state.
     */
    @Transactional
    public PaymentResponse retryPayment(String id, String triggeredBy) {
        Payment payment = findOrThrow(id);
        if (payment.getStatus() != PaymentStatus.FAILED) {
            throw new InvalidStatusTransitionException(payment.getStatus(), PaymentStatus.CREATED);
        }
        PaymentStatus previous = payment.getStatus();
        payment.setRetryCount(payment.getRetryCount() + 1);
        payment.setErrorCode(null);
        payment.setErrorMessage(null);
        payment.setStatus(PaymentStatus.CREATED);
        Payment saved = paymentRepository.save(payment);
        recordHistory(saved, previous, PaymentStatus.CREATED, triggeredBy,
                "Retry #" + saved.getRetryCount() + " requested for failed payment", "Retry Requested");
        simulationService.scheduleProcessing(saved.getId(), true);
        return PaymentResponse.from(saved);
    }

    /**
     * Checks whether a payment matching the same source/destination account,
     * amount, currency and reference was created within the last 2 minutes
     * (duplicate submission detection). Logs a "Duplicate Warning Triggered"
     * audit event against the matched payment when found.
     */
    @Transactional
    public Optional<PaymentResponse> checkDuplicate(String sourceAccount, String destinationAccount,
                                                     BigDecimal amount, String currency, String reference) {
        Instant cutoff = Instant.now().minus(2, ChronoUnit.MINUTES);
        String normalizedRef = reference == null ? "" : reference.trim();
        List<Payment> candidates = paymentRepository.findRecentMatching(
                sourceAccount, destinationAccount, amount, currency.toUpperCase(), cutoff);
        Optional<Payment> match = candidates.stream()
                .filter(p -> normalizedRef.equals(p.getReference() == null ? "" : p.getReference().trim()))
                .findFirst();
        match.ifPresent(p -> recordHistory(p, p.getStatus(), p.getStatus(), "SYSTEM",
                "New submission matched this payment's source/destination/amount/currency/reference within 2 minutes",
                "Duplicate Warning Triggered"));
        return match.map(PaymentResponse::from);
    }

    private String defaultActionFor(PaymentStatus status) {
        return switch (status) {
            case CREATED -> "Payment Created";
            case VALIDATED -> "Validated";
            case SENT -> "Sent";
            case COMPLETED -> "Completed";
            case FAILED -> "Failed";
        };
    }

    @Transactional
    public void recordHistory(Payment payment, PaymentStatus from, PaymentStatus to, String triggeredBy, String notes) {
        recordHistory(payment, from, to, triggeredBy, notes, defaultActionFor(to));
    }

    @Transactional
    public void recordHistory(Payment payment, PaymentStatus from, PaymentStatus to, String triggeredBy, String notes, String action) {
        PaymentStatusHistory history = PaymentStatusHistory.builder()
                .payment(payment)
                .fromStatus(from)
                .toStatus(to)
                .triggeredBy(triggeredBy)
                .action(action)
                .notes(notes)
                .build();
        historyRepository.save(history);
    }

    private Payment findOrThrow(String id) {
        return paymentRepository.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }
}

