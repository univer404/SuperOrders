package com.superorders.model;

import org.bukkit.Material;
import java.util.UUID;

public record BuyOrder(long id, UUID ownerId, String ownerName, Material material, int originalQuantity,
                       int remainingQuantity, double unitPrice, double remainingEscrow, long createdAt,
                       long expiresAt, OrderStatus status) {
    public double remainingValue() { return unitPrice * remainingQuantity; }
    public int deliveredQuantity() { return originalQuantity - remainingQuantity; }
}