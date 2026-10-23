package com.superorders.integration;

import com.superorders.SuperOrders;
import com.superorders.model.OrderStatus;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Optional PlaceholderAPI integration. */
public final class SuperOrdersExpansion extends PlaceholderExpansion {
    private final SuperOrders plugin;

    public SuperOrdersExpansion(SuperOrders plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "superorders";
    }

    @Override
    public @NotNull String getAuthor() {
        return "univer404";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        try {
            return switch (params.toLowerCase(java.util.Locale.ROOT)) {
                case "active_orders" -> Integer.toString(plugin.repository().activeOrders().size());
                case "my_active_orders" -> {
                    if (player == null || player.getUniqueId() == null) yield "0";
                    yield Long.toString(plugin.repository().ordersFor(player.getUniqueId()).stream()
                            .filter(order -> order.status() == OrderStatus.ACTIVE).count());
                }
                case "my_active_escrow" -> {
                    if (player == null || player.getUniqueId() == null) yield "0.00";
                    double escrow = plugin.repository().ordersFor(player.getUniqueId()).stream()
                            .filter(order -> order.status() == OrderStatus.ACTIVE)
                            .mapToDouble(order -> order.remainingEscrow()).sum();
                    yield String.format(java.util.Locale.US, "%.2f", escrow);
                }
                case "my_delivered_items" -> {
                    if (player == null || player.getUniqueId() == null) yield "0";
                    yield Integer.toString(plugin.repository().ordersFor(player.getUniqueId()).stream()
                            .mapToInt(order -> order.deliveredQuantity()).sum());
                }
                default -> null;
            };
        } catch (Exception ex) {
            plugin.getLogger().warning("Placeholder request failed for " + params + ": " + ex.getMessage());
            return "0";
        }
    }
}
