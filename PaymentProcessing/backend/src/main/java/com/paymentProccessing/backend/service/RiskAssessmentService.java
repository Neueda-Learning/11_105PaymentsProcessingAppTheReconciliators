package com.paymentProccessing.backend.service;

import com.paymentProccessing.backend.dto.CreatePaymentRequest;
import com.paymentProccessing.backend.enums.RiskLevel;
import com.paymentProccessing.backend.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Rule-based fraud/risk validation engine, run before every payment is
 * created. Analyzes destination country, time of day, submission frequency
 * and transaction amount against the payment's own history and assigns a
 * risk score (0-100), a risk level (LOW/MEDIUM/HIGH) and a list of
 * human-readable reasons - nothing here is UI-only, every rule is evaluated
 * server-side against real persisted payment data.
 */
@Service
@RequiredArgsConstructor
public class RiskAssessmentService {

    // ---- Scoring weights ----
    private static final int COUNTRY_ANOMALY_SCORE = 30;
    private static final int UNUSUAL_TIME_SCORE = 20;
    private static final int HIGH_FREQUENCY_SCORE = 30;
    private static final int LARGE_AMOUNT_SCORE = 20;

    // ---- Rule thresholds ----
    private static final int FREQUENCY_WINDOW_MINUTES = 2;
    private static final int FREQUENCY_THRESHOLD = 5;
    private static final int UNUSUAL_HOUR_START = 22; // 10 PM
    private static final int UNUSUAL_HOUR_END = 6;     // 6 AM
    private static final BigDecimal LARGE_AMOUNT_MULTIPLIER = BigDecimal.valueOf(5);

    // ---- Risk level classification bands ----
    private static final int LOW_MAX = 30;
    private static final int MEDIUM_MAX = 60;

    private final PaymentRepository paymentRepository;

    @Transactional(readOnly = true)
    public RiskAssessmentResult assess(CreatePaymentRequest request) {
        List<String> reasons = new ArrayList<>();
        int score = 0;

        score += evaluateCountryRisk(request, reasons);
        score += evaluateTimeRisk(reasons);
        score += evaluateFrequencyRisk(request, reasons);
        score += evaluateAmountRisk(request, reasons);

        score = Math.min(score, 100);
        return new RiskAssessmentResult(score, classify(score), reasons);
    }

    /** 1. Country based validation: flag international payments to a country never used before by this source account. */
    private int evaluateCountryRisk(CreatePaymentRequest request, List<String> reasons) {
        if (request.getPaymentMethod() == null || !request.getPaymentMethod().isInternational()) {
            return 0; // domestic payments within the same country are low risk
        }
        String country = request.getInternationalTransferDetails() != null
                ? request.getInternationalTransferDetails().getBeneficiaryCountry()
                : null;
        if (country == null || country.isBlank()) {
            return 0;
        }
        List<String> priorCountries = paymentRepository.findDistinctBeneficiaryCountriesBySourceAccount(request.getSourceAccount());
        boolean seenBefore = priorCountries.stream().anyMatch(c -> c.equalsIgnoreCase(country));
        if (!priorCountries.isEmpty() && !seenBefore) {
            reasons.add("Unusual destination country detected");
            return COUNTRY_ANOMALY_SCORE;
        }
        return 0;
    }

    /** 2. Time based validation: flag payments initiated outside normal operating hours (10 PM - 6 AM). */
    private int evaluateTimeRisk(List<String> reasons) {
        int hour = Instant.now().atZone(ZoneOffset.UTC).getHour();
        if (hour >= UNUSUAL_HOUR_START || hour < UNUSUAL_HOUR_END) {
            reasons.add("Payment initiated during unusual hours");
            return UNUSUAL_TIME_SCORE;
        }
        return 0;
    }

    /** 3. Frequency based validation: flag excessive attempts between the same source/destination pair in a short window. */
    private int evaluateFrequencyRisk(CreatePaymentRequest request, List<String> reasons) {
        Instant cutoff = Instant.now().minus(FREQUENCY_WINDOW_MINUTES, ChronoUnit.MINUTES);
        long recentCount = paymentRepository.countBySourceAccountAndDestinationAccountAndCreatedAtAfter(
                request.getSourceAccount(), request.getDestinationAccount(), cutoff);
        if (recentCount >= FREQUENCY_THRESHOLD) {
            reasons.add("Multiple payment attempts detected within a short duration");
            return HIGH_FREQUENCY_SCORE;
        }
        return 0;
    }

    /** 4. Amount based validation: flag transactions that significantly exceed the source account's historical average. */
    private int evaluateAmountRisk(CreatePaymentRequest request, List<String> reasons) {
        BigDecimal avgAmount = paymentRepository.findAverageAmountBySourceAccount(request.getSourceAccount());
        if (avgAmount == null || avgAmount.compareTo(BigDecimal.ZERO) <= 0 || request.getAmount() == null) {
            return 0; // no history to compare against yet
        }
        if (request.getAmount().compareTo(avgAmount.multiply(LARGE_AMOUNT_MULTIPLIER)) > 0) {
            reasons.add("Transaction amount exceeds normal payment pattern");
            return LARGE_AMOUNT_SCORE;
        }
        return 0;
    }

    private RiskLevel classify(int score) {
        if (score <= LOW_MAX) return RiskLevel.LOW;
        if (score <= MEDIUM_MAX) return RiskLevel.MEDIUM;
        return RiskLevel.HIGH;
    }
}
