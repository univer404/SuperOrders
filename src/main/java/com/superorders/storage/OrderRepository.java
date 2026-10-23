package com.superorders.storage;

import com.superorders.model.BuyOrder;
import com.superorders.model.OrderStatus;
import com.superorders.model.RecoveryObligation;
import com.superorders.model.TransactionRecord;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * YAML-backed durable repository. Every public mutation is synchronized and saved to a
 * temporary file before replacing the live file, reducing the risk of a truncated save.
 */
public final class OrderRepository implements AutoCloseable {
    private final File file;
    private YamlConfiguration data;

    public OrderRepository(File file) throws SQLException {
        this.file = file.getAbsoluteFile();
        File parent = this.file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLException("Cannot create SuperOrders data folder");
        }
        try {
            data = YamlConfiguration.loadConfiguration(this.file);
            if (!data.contains("schema-version")) data.set("schema-version", 1);
            if (!data.contains("next-order-id")) data.set("next-order-id", 1L);
            save();
        } catch (IOException ex) {
            throw new SQLException("Could not initialize YAML storage at " + this.file, ex);
        }
    }

    public synchronized String recordTransaction(UUID actor, long orderId, int quantity, double amount,
                                                 String state, String details) throws SQLException {
        String id = UUID.randomUUID().toString();
        String path = "transactions." + id;
        data.set(path + ".transaction-id", id);
        data.set(path + ".order-id", orderId);
        data.set(path + ".actor-uuid", actor.toString());
        data.set(path + ".quantity", quantity);
        data.set(path + ".amount", amount);
        data.set(path + ".state", state);
        data.set(path + ".created-at", System.currentTimeMillis());
        data.set(path + ".details", details == null ? "" : details);
        save();
        return id;
    }

    public synchronized boolean claimTransactionState(String id, String expectedState, String newState,
                                                       String details) throws SQLException {
        String path = "transactions." + id;
        if (!data.contains(path) || !expectedState.equals(data.getString(path + ".state"))) return false;
        data.set(path + ".state", newState);
        data.set(path + ".details", details == null ? "" : details);
        save();
        return true;
    }

    public synchronized void setTransactionState(String id, String state, String details) throws SQLException {
        String path = "transactions." + id;
        if (!data.contains(path)) throw new SQLException("Transaction record missing");
        data.set(path + ".state", state);
        data.set(path + ".details", details == null ? "" : details);
        save();
    }

    public synchronized long create(UUID owner, String name, Material material, int quantity, double price,
                                    double escrow, long created, long expires) throws SQLException {
        long id = Math.max(1L, data.getLong("next-order-id", 1L));
        String path = "orders." + id;
        data.set(path + ".id", id);
        data.set(path + ".owner-uuid", owner.toString());
        data.set(path + ".owner-name", name);
        data.set(path + ".material", material.name());
        data.set(path + ".original-quantity", quantity);
        data.set(path + ".remaining-quantity", quantity);
        data.set(path + ".unit-price", price);
        data.set(path + ".remaining-escrow", escrow);
        data.set(path + ".created-at", created);
        data.set(path + ".expires-at", expires);
        data.set(path + ".status", OrderStatus.ACTIVE.name());
        data.set("next-order-id", id + 1);
        save();
        return id;
    }

    public synchronized List<BuyOrder> activeOrders() {
        return orders().stream().filter(o -> o.status() == OrderStatus.ACTIVE)
                .filter(o -> o.remainingQuantity() > 0 && o.expiresAt() > System.currentTimeMillis())
                .sorted(Comparator.comparingLong(BuyOrder::createdAt).reversed()).toList();
    }

    public synchronized List<BuyOrder> expiredActiveOrders(long now) {
        return orders().stream().filter(o -> o.status() == OrderStatus.ACTIVE && o.expiresAt() <= now).toList();
    }

    public synchronized int countActive(UUID owner) {
        return (int) orders().stream().filter(o -> o.ownerId().equals(owner))
                .filter(o -> o.status() == OrderStatus.ACTIVE && o.expiresAt() > System.currentTimeMillis()).count();
    }

    public synchronized List<TransactionRecord> historyFor(UUID actor, int limit) {
        return transactions().stream().filter(t -> t.actorUuid().equals(actor.toString())
                        || findOrderOwner(t.orderId(), actor))
                .sorted(Comparator.comparingLong(TransactionRecord::createdAt).reversed())
                .limit(Math.max(1, Math.min(100, limit))).toList();
    }

    private boolean findOrderOwner(long orderId, UUID owner) {
        BuyOrder order = find(orderId);
        return order != null && order.ownerId().equals(owner);
    }

    public synchronized boolean hasUnresolvedDelivery(long orderId) {
        return transactions().stream().anyMatch(t -> t.orderId() == orderId
                && List.of("DELIVERY_PREPARED", "ITEMS_RESERVED", "PAYMENT_PENDING",
                "ITEM_RESTORE_PENDING", "FAILED_REQUIRES_REVIEW").contains(t.state()));
    }

    public synchronized void finishDelivery(long orderId, int quantity, double amount, String transactionId)
            throws SQLException {
        BuyOrder order = find(orderId);
        if (order == null || order.status() != OrderStatus.ACTIVE
                || order.remainingQuantity() < quantity || order.remainingEscrow() + 1.0E-8 < amount) {
            throw new SQLException("Order is no longer fulfillable");
        }
        String txPath = "transactions." + transactionId;
        if (!data.contains(txPath) || !"PAYMENT_PENDING".equals(data.getString(txPath + ".state"))) {
            throw new SQLException("Delivery journal state mismatch");
        }
        String orderPath = "orders." + orderId;
        data.set(orderPath + ".remaining-quantity", order.remainingQuantity() - quantity);
        data.set(orderPath + ".remaining-escrow", Math.max(0.0, order.remainingEscrow() - amount));
        if (order.remainingQuantity() - quantity == 0) data.set(orderPath + ".status", OrderStatus.COMPLETED.name());
        data.set(txPath + ".state", "COMPLETED");
        data.set(txPath + ".details", "Delivery finalized");
        save();
    }

    public synchronized List<BuyOrder> ordersFor(UUID owner) {
        return orders().stream().filter(o -> o.ownerId().equals(owner))
                .sorted(Comparator.comparingLong(BuyOrder::createdAt).reversed()).toList();
    }

    public synchronized BuyOrder find(long id) {
        String path = "orders." + id;
        if (!data.contains(path)) return null;
        try {
            return new BuyOrder(data.getLong(path + ".id"), UUID.fromString(data.getString(path + ".owner-uuid")),
                    data.getString(path + ".owner-name"), Material.valueOf(data.getString(path + ".material")),
                    data.getInt(path + ".original-quantity"), data.getInt(path + ".remaining-quantity"),
                    data.getDouble(path + ".unit-price"), data.getDouble(path + ".remaining-escrow"),
                    data.getLong(path + ".created-at"), data.getLong(path + ".expires-at"),
                    OrderStatus.valueOf(data.getString(path + ".status")));
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Invalid order data for order #" + id, ex);
        }
    }

    public synchronized void recordRefundObligation(UUID player, double amount, String reason) throws SQLException {
        String id = UUID.randomUUID().toString();
        String path = "obligations." + id;
        data.set(path + ".obligation-id", id);
        data.set(path + ".player-uuid", player.toString());
        data.set(path + ".amount", amount);
        data.set(path + ".reason", reason);
        data.set(path + ".state", "PENDING");
        data.set(path + ".created-at", System.currentTimeMillis());
        save();
    }

    public synchronized String cancelAndRecordRefund(long orderId, OrderStatus status, UUID player,
                                                      double amount, String reason) throws SQLException {
        BuyOrder order = find(orderId);
        if (order == null || order.status() != OrderStatus.ACTIVE) throw new SQLException("Order is not active");
        String obligation = amount > 0 ? UUID.randomUUID().toString() : null;
        String orderPath = "orders." + orderId;
        data.set(orderPath + ".status", status.name());
        data.set(orderPath + ".remaining-escrow", 0.0);
        if (obligation != null) {
            String path = "obligations." + obligation;
            data.set(path + ".obligation-id", obligation);
            data.set(path + ".player-uuid", player.toString());
            data.set(path + ".amount", amount);
            data.set(path + ".reason", reason);
            data.set(path + ".state", "PENDING");
            data.set(path + ".created-at", System.currentTimeMillis());
        }
        save();
        return obligation;
    }

    public synchronized void markRefundObligation(String id, String state) throws SQLException {
        String path = "obligations." + id;
        if (!data.contains(path) || !"PENDING".equals(data.getString(path + ".state"))) return;
        data.set(path + ".state", state);
        save();
    }

    public synchronized TransactionRecord findTransaction(String id) {
        String path = "transactions." + id;
        return data.contains(path) ? transaction(path) : null;
    }

    public synchronized List<TransactionRecord> pendingTransactions(int limit) {
        return transactions().stream().filter(t -> isPendingTransaction(t.state()))
                .sorted(Comparator.comparingLong(TransactionRecord::createdAt))
                .limit(Math.max(1, Math.min(100, limit))).toList();
    }

    public synchronized List<RecoveryObligation> pendingObligations(int limit) {
        ConfigurationSection section = data.getConfigurationSection("obligations");
        if (section == null) return List.of();
        List<RecoveryObligation> out = new ArrayList<>();
        for (String id : section.getKeys(false)) {
            String path = "obligations." + id;
            String state = data.getString(path + ".state", "PENDING");
            if (!List.of("PENDING", "REVIEW", "PROCESSING").contains(state)) continue;
            out.add(obligation(path));
        }
        out.sort(Comparator.comparingLong(RecoveryObligation::createdAt));
        return out.stream().limit(Math.max(1, Math.min(100, limit))).toList();
    }

    public synchronized RecoveryObligation findObligation(String id) {
        String path = "obligations." + id;
        return data.contains(path) ? obligation(path) : null;
    }

    public synchronized boolean claimRefundObligation(String id) throws SQLException {
        String path = "obligations." + id;
        if (!data.contains(path) || !List.of("PENDING", "REVIEW").contains(data.getString(path + ".state"))) return false;
        data.set(path + ".state", "PROCESSING");
        save();
        return true;
    }

    public synchronized void finishRefundObligation(String id, String state) throws SQLException {
        if (!List.of("PAID", "REVIEW").contains(state)) throw new IllegalArgumentException("Invalid refund state");
        String path = "obligations." + id;
        if (!data.contains(path) || !"PROCESSING".equals(data.getString(path + ".state"))) {
            throw new SQLException("Refund obligation is no longer being processed");
        }
        data.set(path + ".state", state);
        save();
    }

    public synchronized int pendingTransactionCount() {
        return (int) transactions().stream().filter(t -> isPendingTransaction(t.state())).count();
    }

    public synchronized int pendingRefundCount() {
        ConfigurationSection section = data.getConfigurationSection("obligations");
        if (section == null) return 0;
        int count = 0;
        for (String id : section.getKeys(false)) {
            if (List.of("PENDING", "REVIEW", "PROCESSING").contains(data.getString("obligations." + id + ".state", "PENDING"))) count++;
        }
        return count;
    }

    public synchronized void setStatus(long id, OrderStatus status) throws SQLException {
        String path = "orders." + id;
        if (!data.contains(path) || !OrderStatus.ACTIVE.name().equals(data.getString(path + ".status"))) {
            throw new SQLException("Order is not active");
        }
        data.set(path + ".status", status.name());
        save();
    }

    private List<BuyOrder> orders() {
        ConfigurationSection section = data.getConfigurationSection("orders");
        if (section == null) return List.of();
        List<BuyOrder> out = new ArrayList<>();
        for (String id : section.getKeys(false)) {
            BuyOrder order = find(Long.parseLong(id));
            if (order != null) out.add(order);
        }
        return out;
    }

    private List<TransactionRecord> transactions() {
        ConfigurationSection section = data.getConfigurationSection("transactions");
        if (section == null) return List.of();
        List<TransactionRecord> out = new ArrayList<>();
        for (String id : section.getKeys(false)) out.add(transaction("transactions." + id));
        return out;
    }

    private TransactionRecord transaction(String path) {
        return new TransactionRecord(data.getString(path + ".transaction-id"), data.getLong(path + ".order-id"),
                data.getString(path + ".actor-uuid"), data.getInt(path + ".quantity"),
                data.getDouble(path + ".amount"), data.getString(path + ".state"),
                data.getLong(path + ".created-at"), data.getString(path + ".details", ""));
    }

    private RecoveryObligation obligation(String path) {
        return new RecoveryObligation(data.getString(path + ".obligation-id"),
                UUID.fromString(data.getString(path + ".player-uuid")), data.getDouble(path + ".amount"),
                data.getString(path + ".reason", ""), data.getString(path + ".state", "PENDING"),
                data.getLong(path + ".created-at"));
    }

    private boolean isPendingTransaction(String state) {
        return List.of("ESCROW_WITHDRAW_PENDING", "ESCROW_REFUND_PENDING", "DELIVERY_PREPARED",
                "ITEMS_RESERVED", "PAYMENT_PENDING", "ITEM_RESTORE_PENDING", "FAILED_REQUIRES_REVIEW").contains(state);
    }

    private void save() throws SQLException {
        Path temp = file.toPath().resolveSibling(file.getName() + ".tmp");
        try {
            data.save(temp.toFile());
            try {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
            throw new SQLException("Could not save YAML storage file " + file, ex);
        }
    }

    @Override public synchronized void close() { }
}
