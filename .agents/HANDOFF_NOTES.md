# QA Rejection Notes

## RFC-TKT001-02

### Defect 1: Payment Confirmation contract does not accept RFC-defined snake_case payload

- Severity: MAJOR
- Category: API contract regression
- Evidence:
  - Test: `com.ticket.core.integration.PaymentConfirmationApiIT.confirmPayment_rfcSnakeCaseContract_returnsSnakeCasePayload`
  - Surefire report: `target/surefire-reports/com.ticket.core.integration.PaymentConfirmationApiIT.txt`
- Reproduction:
  1. Start the application with the current codebase and MySQL/Testcontainers-backed integration test context.
  2. Send `POST /payments/confirmations` with header `Idempotency-Key: any-value`.
  3. Use the RFC-defined JSON field names:
     - `external_trade_no`
     - `payment_provider`
     - `provider_event_id`
     - `provider_payment_id`
     - `confirmed_at`
     - `channel_context`
  4. Observe the response status.
- Expected:
  - HTTP `200 OK`
  - Request is accepted because RFC 4.2.3 defines `snake_case` fields as the canonical contract.
  - Response payload fields are also emitted as RFC-defined `snake_case`.
- Actual:
  - HTTP `400 BAD_REQUEST`
  - Server-side validation treats `confirmedAt` / `providerEventId` as missing, which indicates request binding is expecting `camelCase` Java property names instead of the RFC contract.
- Impact:
  - Any upstream channel or gateway implemented against RFC 4.2.3 will fail to confirm payments.
  - This is a release blocker because it breaks the externally documented ingress contract.

### Verified non-defect

- Concurrent confirmations with different `Idempotency-Key` values preserved the core invariant:
  - exactly one `Fulfillment` row created
  - the competing request returned either stable success or `PAYMENT_CONFIRMATION_IN_PROGRESS`
