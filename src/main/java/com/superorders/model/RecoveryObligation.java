package com.superorders.model;

import java.util.UUID;

/**
 * A durable record of money that may need manual recovery.
 * PROCESSING means a payout was started and must not be retried blindly after a crash.
 */
public record RecoveryObligation(
        String id,
        UUID playerId,
        double amount,
        String reason,
        String state,
        long createdAt
) {
}
