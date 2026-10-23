package com.superorders.service;

import com.superorders.SuperOrders;
import com.superorders.model.BuyOrder;
import com.superorders.model.OrderStatus;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class OrderService {
    private final SuperOrders plugin;
    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();
    public OrderService(SuperOrders plugin) { this.plugin = plugin; }

    public synchronized long create(Player player, Material material, int quantity, double unitPrice) throws Exception {
        if (material == null || material.isAir() || !material.isItem()) throw new IllegalArgumentException("That item cannot be ordered.");
        if (quantity < 1 || quantity > plugin.getConfig().getInt("orders.max-quantity", 23040)) throw new IllegalArgumentException("Quantity is outside the allowed range.");
        if (!Double.isFinite(unitPrice) || unitPrice < plugin.getConfig().getDouble("orders.min-unit-price", 0.01) || unitPrice > plugin.getConfig().getDouble("orders.max-unit-price", 1_000_000_000.0)) throw new IllegalArgumentException("Price is outside the allowed range.");
        double total = unitPrice * quantity;
        if (!Double.isFinite(total) || total > plugin.getConfig().getDouble("orders.max-total-value", 1_000_000_000.0)) throw new IllegalArgumentException("Total order value is too high.");
        if (plugin.repository().countActive(player.getUniqueId()) >= plugin.getConfig().getInt("orders.max-active-per-player", 10)) throw new IllegalArgumentException("You already have the maximum number of active orders.");
        var allowed = plugin.getConfig().getStringList("orders.allowed-materials");
        var blocked = plugin.getConfig().getStringList("orders.blocked-materials");
        if (!allowed.isEmpty() && !allowed.contains(material.name())) throw new IllegalArgumentException("That material is not allowed.");
        if (blocked.contains(material.name())) throw new IllegalArgumentException("That material is blocked.");
        if (plugin.economy().getBalance(player) + 1e-8 < total) throw new IllegalArgumentException("You do not have enough money.");
        var response = plugin.economy().withdrawPlayer(player, total);
        if (!response.transactionSuccess()) throw new IllegalStateException("Economy withdrawal failed: " + response.errorMessage);
        long now = System.currentTimeMillis();
        long lifetime = plugin.getConfig().getLong("orders.expiration.default-lifetime-hours", 168) * 3_600_000L;
        try {
            return plugin.repository().create(player.getUniqueId(), player.getName(), material, quantity, unitPrice, total, now,
                    plugin.getConfig().getBoolean("orders.expiration.enabled", true) ? now + lifetime : Long.MAX_VALUE);
        } catch (Exception dbFailure) {
            var refund = plugin.economy().depositPlayer(player, total);
            if (!refund.transactionSuccess()) plugin.getLogger().severe("CRITICAL: failed to compensate order creation withdrawal for " + player.getUniqueId() + " amount=" + total);
            throw dbFailure;
        }
    }

    public boolean cancel(Player actor, long orderId, boolean admin) throws Exception {
        ReentrantLock lock = locks.computeIfAbsent(orderId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            BuyOrder order = plugin.repository().find(orderId);
            if (order == null || order.status() != OrderStatus.ACTIVE) throw new IllegalArgumentException("That order is not active.");
            if (!admin && !order.ownerId().equals(actor.getUniqueId())) throw new IllegalArgumentException("You do not own that order.");
            plugin.repository().setStatus(orderId, OrderStatus.CANCELLED);
            double refundAmount = order.remainingEscrow();
            if (refundAmount > 0) {
                var response = plugin.economy().depositPlayer(order.ownerId().toString().equals(actor.getUniqueId().toString()) ? actor : plugin.getServer().getOfflinePlayer(order.ownerId()), refundAmount);
                if (!response.transactionSuccess()) {
                    plugin.repository().recordRefundObligation(order.ownerId(), refundAmount, "Order cancellation #" + orderId);
                    actor.sendMessage("Refund could not be delivered immediately; it has been recorded for administrator recovery.");
                }
            }
            return true;
        } finally { lock.unlock(); }
    }

    public ReentrantLock lockFor(long id) { return locks.computeIfAbsent(id, ignored -> new ReentrantLock()); }
}