package com.superorders.model;

import org.bukkit.Material;
import java.util.UUID;

public record BuyOrder(long id, UUID ownerId, String ownerName, Material material, int originalQuantity,
                       int remainingQuantity, double unitPrice, double remainingEscrow, long createdAt,
                       long expiresAt, OrderStatus status) {
    public BuyOrder {
        if (id < 0) throw new IllegalArgumentException("Order ID cannot be negative.");
        java.util.Objects.requireNonNull(ownerId, "ownerId");
        if (ownerName == null || ownerName.isBlank()) throw new IllegalArgumentException("Owner name is required.");
        java.util.Objects.requireNonNull(material, "material");
        java.util.Objects.requireNonNull(status, "status");
        if (originalQuantity < 1 || remainingQuantity < 0 || remainingQuantity > originalQuantity) {
            throw new IllegalArgumentException("Order quantities are inconsistent.");
        }
        if (!Double.isFinite(unitPrice) || unitPrice <= 0) throw new IllegalArgumentException("Unit price must be finite and positive.");
        if (!Double.isFinite(remainingEscrow) || remainingEscrow < 0) throw new IllegalArgumentException("Escrow must be finite and non-negative.");
        if (createdAt < 0 || expiresAt < createdAt) throw new IllegalArgumentException("Order timestamps are inconsistent.");
    }

    public double remainingValue() { return unitPrice * remainingQuantity; }
    public int deliveredQuantity() { return originalQuantity - remainingQuantity; }
}