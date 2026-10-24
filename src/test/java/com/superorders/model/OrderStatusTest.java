package com.superorders.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderStatusTest {
    @Test void deliveredQuantityIsDerivedFromRemainingQuantity() {
        var order = new BuyOrder(1, java.util.UUID.randomUUID(), "Steve", org.bukkit.Material.DIAMOND, 64, 48, 100.0, 4800.0, 1L, 2L, OrderStatus.ACTIVE);
        assertEquals(16, order.deliveredQuantity());
        assertEquals(4800.0, order.remainingValue());
    }

    @Test void rejectsInconsistentQuantities() {
        assertThrows(IllegalArgumentException.class, () -> new BuyOrder(1, java.util.UUID.randomUUID(),
                "Steve", org.bukkit.Material.DIAMOND, 10, 11, 1.0, 10.0, 1L, 2L, OrderStatus.ACTIVE));
    }

    @Test void rejectsNonFinitePrice() {
        assertThrows(IllegalArgumentException.class, () -> new BuyOrder(1, java.util.UUID.randomUUID(),
                "Steve", org.bukkit.Material.DIAMOND, 10, 10, Double.NaN, 10.0, 1L, 2L, OrderStatus.ACTIVE));
    }
}