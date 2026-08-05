package com.paymentProccessing.backend.enums;

/**
 * Supported payment methods/instruments.
 */
public enum PaymentMethod {
    UPI,
    CARD,
    NETBANKING,
    NEFT,
    RTGS,
    IMPS,
    SWIFT,
    WIRE_TRANSFER;

    public boolean isDomestic() {
        return this != SWIFT && this != WIRE_TRANSFER;
    }

    public boolean isInternational() {
        return !isDomestic();
    }
}

