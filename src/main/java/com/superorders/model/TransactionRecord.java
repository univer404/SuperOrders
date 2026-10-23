package com.superorders.model;

public record TransactionRecord(String transactionId, long orderId, String actorUuid, int quantity,
                                double amount, String state, long createdAt, String details) { }
