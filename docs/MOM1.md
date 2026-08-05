## Minutes of Meeting (MoM)

**Date:** 31/07/2026

**Project:** Payment Processing Application

**Meeting Type:** Requirement Discussion with Customer

**Customer:** Juanis

**Purpose:** Understanding customer expectations and identifying key requirements for the payment processing solution.

### Discussion Points

**1. Mock User Interface**

* The application should have a user interface through which payment-related information can be viewed.
* The UI should allow users to check the **current status of a payment**.

**2. Payment / Transaction Validation**

* The system should perform validations on transactions before or during payment processing.
* Validation/risk checks may consider parameters such as:

  * Transaction amount
  * Country/location
  * Time of transaction
  * Frequency of transactions
* These parameters can help identify unusual or potentially suspicious payment activity.

**3. Failed Payment Status**

* The system should provide a notification/indication when a payment reaches a **failed status**.
* Users should be able to understand that the transaction has failed rather than being left unaware of its status.

**4. Transaction History**

* The application should maintain a history of transactions.
* Users should be able to view previous transaction details and their corresponding statuses.

**5. Fraud Transaction Detection**

* The application should consider mechanisms for identifying potentially fraudulent transactions.
* Transaction characteristics such as amount, location/country, time, and frequency can be considered while evaluating suspicious activity.

**6. Duplicate Transaction Handling**

* The system should consider the possibility of **duplicate transactions/payments**.
* Appropriate checks should be included to avoid processing the same transaction multiple times.

### Key Takeaway

The proposed solution should be a **workflow-driven payment processing/tracking application** where a payment moves through different stages, its current status can be tracked, transactions are validated for possible fraud or duplication, and failed payment statuses are clearly communicated while balancing **security and processing efficiency**.

### Points to Explore in the Next Discussion

* Finalize the payment lifecycle and statuses.
* Define the rules for identifying failed or suspicious transactions.
* Finalize the notification mechanism.
* Confirm the expected user interface functionality.
