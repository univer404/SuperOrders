package com.superorders.storage;

import com.superorders.model.TransactionRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OrderRepositoryRecoveryTest {
    @TempDir
    Path tempDir;

    @Test
    void transactionCanOnlyBeClaimedFromExpectedState() throws Exception {
        try (OrderRepository repository = new OrderRepository(tempDatabase())) {
            UUID actor = UUID.randomUUID();
            String id = repository.recordTransaction(actor, 42L, 4, 12.0,
                    "ITEMS_RESERVED", "test delivery");

            assertTrue(repository.claimTransactionState(id, "ITEMS_RESERVED",
                    "ITEM_RESTORE_PENDING", "recovery started"));
            assertFalse(repository.claimTransactionState(id, "ITEMS_RESERVED",
                    "ITEM_RESTORE_PENDING", "duplicate recovery attempt"));

            TransactionRecord transaction = repository.findTransaction(id);
            assertNotNull(transaction);
            assertEquals("ITEM_RESTORE_PENDING", transaction.state());
            assertEquals("recovery started", transaction.details());
        }
    }

    @Test
    void unresolvedDeliveryStatesAreListedForRecovery() throws Exception {
        try (OrderRepository repository = new OrderRepository(tempDatabase())) {
            String id = repository.recordTransaction(UUID.randomUUID(), 99L, 2, 5.0,
                    "PAYMENT_PENDING", "payout may be ambiguous");

            assertEquals(1, repository.pendingTransactionCount());
            assertEquals(id, repository.pendingTransactions(10).get(0).transactionId());

            repository.setTransactionState(id, "COMPLETED", "finalized");
            assertEquals(0, repository.pendingTransactionCount());
            assertTrue(repository.pendingTransactions(10).isEmpty());
        }
    }

    @Test
    void interruptedReservationIsVisibleForAdministratorRecovery() throws Exception {
        try (OrderRepository repository = new OrderRepository(tempDatabase())) {
            String id = repository.recordTransaction(UUID.randomUUID(), 123L, 8, 16.0,
                    "DELIVERY_PREPARED", "reservation started");
            repository.setTransactionState(id, "FAILED_REQUIRES_REVIEW",
                    "Inventory reservation may be partial");

            assertEquals(1, repository.pendingTransactionCount());
            TransactionRecord transaction = repository.pendingTransactions(10).get(0);
            assertEquals(id, transaction.transactionId());
            assertEquals("FAILED_REQUIRES_REVIEW", transaction.state());
            assertTrue(transaction.details().contains("partial"));
        }
    }

    @Test
    void refundObligationCannotBeClaimedTwice() throws Exception {
        try (OrderRepository repository = new OrderRepository(tempDatabase())) {
            UUID player = UUID.randomUUID();
            repository.recordRefundObligation(player, 25.0, "test refund");

            var obligation = repository.pendingObligations(10).get(0);
            assertEquals("PENDING", obligation.state());
            assertTrue(repository.claimRefundObligation(obligation.id()));
            assertFalse(repository.claimRefundObligation(obligation.id()));

            repository.finishRefundObligation(obligation.id(), "REVIEW");
            assertEquals("REVIEW", repository.findObligation(obligation.id()).state());
        }
    }

    private File tempDatabase() {
        return tempDir.resolve(UUID.randomUUID() + ".sqlite").toFile();
    }
}
