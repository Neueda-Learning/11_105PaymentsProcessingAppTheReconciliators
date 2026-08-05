package com.paymentProccessing.backend.enums;

/**
 * Outcome of fraud/risk validation recorded against a payment.
 * CLEARED   - LOW risk, created normally.
 * FLAGGED   - MEDIUM risk, user confirmed and payment was created anyway.
 * BLOCKED   - HIGH risk, payment creation was rejected (never persisted as a Payment,
 *             but the attempt is still logged in the fraud_validations table).
 */
public enum FraudStatus {
    CLEARED,
    FLAGGED,
    BLOCKED
}
