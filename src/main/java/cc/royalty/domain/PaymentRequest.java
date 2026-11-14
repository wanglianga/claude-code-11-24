package cc.royalty.domain;

import java.time.OffsetDateTime;

public record PaymentRequest(Long id, Long paymentRunId, Long payableId, Long payeeId,
                             String requestId, String accountSnapshot, String bodyFingerprint,
                             long amountMinor, String currency, String status,
                             OffsetDateTime createdAt, OffsetDateTime confirmedAt) {
}
