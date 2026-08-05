package com.paymentProccessing.backend.service;

import com.paymentProccessing.backend.enums.RiskLevel;
import lombok.Getter;

import java.util.List;

/**
 * Result of a fraud/risk assessment for a prospective payment.
 */
@Getter
public class RiskAssessmentResult {

    private final int riskScore;
    private final RiskLevel riskLevel;
    private final List<String> reasons;

    public RiskAssessmentResult(int riskScore, RiskLevel riskLevel, List<String> reasons) {
        this.riskScore = riskScore;
        this.riskLevel = riskLevel;
        this.reasons = reasons;
    }
}
