package com.superorders.storage;

import com.superorders.model.BuyOrder;
import com.superorders.model.OrderStatus;
import com.superorders.model.TransactionRecord;
import com.superorders.model.RecoveryObligation;
import org.bukkit.Material;
import java.io.File;
import java.sql.*;
import java.util.*;

public final class OrderRepository implements AutoCloseable {
    private final Connection connection;
    public OrderRepository(File file) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA foreign_keys=ON");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS schema_version(version INTEGER NOT NULL)");
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM schema_version")) { if (rs.next() && rs.getInt(1) == 0) s.executeUpdate("INSERT INTO schema_version VALUES(1)"); }
            s.executeUpdate("CREATE TABLE IF NOT EXISTS orders(id INTEGER PRIMARY KEY AUTOINCREMENT, owner_uuid TEXT NOT NULL, owner_name TEXT NOT NULL, material TEXT NOT NULL, original_quantity INTEGER NOT NULL, remaining_quantity INTEGER NOT NULL, unit_price REAL NOT NULL, remaining_escrow REAL NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, status TEXT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS order_transactions(transaction_id TEXT PRIMARY KEY, order_id INTEGER NOT NULL, actor_uuid TEXT NOT NULL, quantity INTEGER NOT NULL, amount REAL NOT NULL, state TEXT NOT NULL, created_at INTEGER NOT NULL, details TEXT NOT NULL DEFAULT '')");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS recovery_obligations(obligation_id TEXT PRIMARY KEY, player_uuid TEXT NOT NULL, amount REAL NOT NULL, reason TEXT NOT NULL, state TEXT NOT NULL, created_at INTEGER NOT NULL)");
        }
    }
    public synchronized String recordTransaction(UUID actor, long orderId, int quantity, double amount, String state, String details) throws SQLException {
        String id=UUID.randomUUID().toString();
        try(PreparedStatement p=connection.prepareStatement("INSERT INTO order_transactions(transaction_id,order_id,actor_uuid,quantity,amount,state,created_at,details) VALUES(?,?,?,?,?,?,?,?)")) {
            p.setString(1,id);p.setLong(2,orderId);p.setString(3,actor.toString());p.setInt(4,quantity);p.setDouble(5,amount);p.setString(6,state);p.setLong(7,System.currentTimeMillis());p.setString(8,details==null?"":details);p.executeUpdate();
        }
        return id;
    }
    public synchronized boolean claimTransactionState(String id, String expectedState, String newState, String details) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement(
                "UPDATE order_transactions SET state=?,details=? WHERE transaction_id=? AND state=?")) {
            p.setString(1, newState);
            p.setString(2, details == null ? "" : details);
            p.setString(3, id);
            p.setString(4, expectedState);
            return p.executeUpdate() == 1;
        }
    }

    public synchronized void setTransactionState(String id, String state, String details) throws SQLException {
        try(PreparedStatement p=connection.prepareStatement("UPDATE order_transactions SET state=?,details=? WHERE transaction_id=?")){p.setString(1,state);p.setString(2,details==null?"":details);p.setString(3,id);if(p.executeUpdate()!=1)throw new SQLException("Transaction record missing");}
    }
    public synchronized long create(UUID owner, String name, Material material, int quantity, double price, double escrow, long created, long expires) throws SQLException {
        String sql = "INSERT INTO orders(owner_uuid,owner_name,material,original_quantity,remaining_quantity,unit_price,remaining_escrow,created_at,expires_at,status) VALUES(?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement p = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            p.setString(1,owner.toString()); p.setString(2,name); p.setString(3,material.name()); p.setInt(4,quantity); p.setInt(5,quantity); p.setDouble(6,price); p.setDouble(7,escrow); p.setLong(8,created); p.setLong(9,expires); p.setString(10,OrderStatus.ACTIVE.name()); p.executeUpdate();
            try (ResultSet keys=p.getGeneratedKeys()) { if(keys.next()) return keys.getLong(1); }
        }
        throw new SQLException("Could not obtain generated order id");
    }
    public synchronized List<BuyOrder> activeOrders() throws SQLException {
        List<BuyOrder> out = new ArrayList<>();
        try (PreparedStatement p=connection.prepareStatement("SELECT * FROM orders WHERE status=? AND remaining_quantity>0 ORDER BY created_at DESC")) {
            p.setString(1,OrderStatus.ACTIVE.name()); try(ResultSet r=p.executeQuery()) { while(r.next()) out.add(map(r)); }
        } return out;
    }
    public synchronized List<BuyOrder> expiredActiveOrders(long now) throws SQLException { List<BuyOrder> out=new ArrayList<>(); try(PreparedStatement p=connection.prepareStatement("SELECT * FROM orders WHERE status=? AND expires_at<=?")){p.setString(1,OrderStatus.ACTIVE.name());p.setLong(2,now);try(ResultSet r=p.executeQuery()){while(r.next())out.add(map(r));}}return out; }
    public synchronized int countActive(UUID owner) throws SQLException {
        try(PreparedStatement p=connection.prepareStatement("SELECT COUNT(*) FROM orders WHERE owner_uuid=? AND status=?")) { p.setString(1,owner.toString()); p.setString(2,OrderStatus.ACTIVE.name()); try(ResultSet r=p.executeQuery()){return r.next()?r.getInt(1):0;} }
    }
    public synchronized List<TransactionRecord> historyFor(UUID actor, int limit) throws SQLException {
        List<TransactionRecord> out=new ArrayList<>();
        try(PreparedStatement p=connection.prepareStatement("SELECT * FROM order_transactions WHERE actor_uuid=? OR order_id IN (SELECT id FROM orders WHERE owner_uuid=?) ORDER BY created_at DESC LIMIT ?")){p.setString(1,actor.toString());p.setString(2,actor.toString());p.setInt(3,Math.max(1,Math.min(100,limit)));try(ResultSet r=p.executeQuery()){while(r.next())out.add(new TransactionRecord(r.getString("transaction_id"),r.getLong("order_id"),r.getString("actor_uuid"),r.getInt("quantity"),r.getDouble("amount"),r.getString("state"),r.getLong("created_at"),r.getString("details")));}}
        return out;
    }
    public synchronized boolean hasUnresolvedDelivery(long orderId) throws SQLException {
        try(PreparedStatement p=connection.prepareStatement("SELECT COUNT(*) FROM order_transactions WHERE order_id=? AND state IN ('DELIVERY_PREPARED','ITEMS_RESERVED','PAYMENT_PENDING','ITEM_RESTORE_PENDING','FAILED_REQUIRES_REVIEW')")){p.setLong(1,orderId);try(ResultSet r=p.executeQuery()){return r.next()&&r.getInt(1)>0;}}
    }
    public synchronized void finishDelivery(long orderId, int quantity, double amount, String transactionId) throws SQLException {
        boolean old=connection.getAutoCommit();connection.setAutoCommit(false);
        try {
            int remaining;
            try(PreparedStatement p=connection.prepareStatement("SELECT remaining_quantity FROM orders WHERE id=? AND status=? AND remaining_quantity>=? AND remaining_escrow>=?")){p.setLong(1,orderId);p.setString(2,OrderStatus.ACTIVE.name());p.setInt(3,quantity);p.setDouble(4,amount);try(ResultSet r=p.executeQuery()){if(!r.next())throw new SQLException("Order is no longer fulfillable");remaining=r.getInt(1)-quantity;}}
            try(PreparedStatement p=connection.prepareStatement("UPDATE orders SET remaining_quantity=?,remaining_escrow=?,status=? WHERE id=? AND status=? AND remaining_quantity>=? AND remaining_escrow>=?")) {
                p.setInt(1,remaining);p.setDouble(2,Math.max(0.0,find(orderId).remainingEscrow()-amount));p.setString(3,remaining==0?OrderStatus.COMPLETED.name():OrderStatus.ACTIVE.name());p.setLong(4,orderId);p.setString(5,OrderStatus.ACTIVE.name());p.setInt(6,quantity);p.setDouble(7,amount);if(p.executeUpdate()!=1)throw new SQLException("Concurrent order update rejected");
            }
            try(PreparedStatement p=connection.prepareStatement("UPDATE order_transactions SET state=?,details=? WHERE transaction_id=? AND state=?")){p.setString(1,"COMPLETED");p.setString(2,"Delivery finalized");p.setString(3,transactionId);p.setString(4,"PAYMENT_PENDING");if(p.executeUpdate()!=1)throw new SQLException("Delivery journal state mismatch");}
            connection.commit();
        } catch(SQLException ex){connection.rollback();throw ex;} finally{connection.setAutoCommit(old);}
    }
    public synchronized List<BuyOrder> ordersFor(UUID owner) throws SQLException {
        List<BuyOrder> out=new ArrayList<>();
        try(PreparedStatement p=connection.prepareStatement("SELECT * FROM orders WHERE owner_uuid=? ORDER BY created_at DESC")){p.setString(1,owner.toString());try(ResultSet r=p.executeQuery()){while(r.next())out.add(map(r));}}
        return out;
    }
    public synchronized BuyOrder find(long id) throws SQLException {
        try(PreparedStatement p=connection.prepareStatement("SELECT * FROM orders WHERE id=?")){p.setLong(1,id);try(ResultSet r=p.executeQuery()){return r.next()?map(r):null;}}
    }
    public synchronized void recordRefundObligation(UUID player, double amount, String reason) throws SQLException {
        try (PreparedStatement p=connection.prepareStatement("INSERT INTO recovery_obligations(obligation_id,player_uuid,amount,reason,state,created_at) VALUES(?,?,?,?,?,?)")) { p.setString(1,UUID.randomUUID().toString()); p.setString(2,player.toString()); p.setDouble(3,amount); p.setString(4,reason); p.setString(5,"PENDING"); p.setLong(6,System.currentTimeMillis()); p.executeUpdate(); }
    }
    public synchronized String cancelAndRecordRefund(long orderId, OrderStatus status, UUID player, double amount, String reason) throws SQLException {
        String obligation = amount > 0 ? UUID.randomUUID().toString() : null;
        boolean oldAuto = connection.getAutoCommit(); connection.setAutoCommit(false);
        try {
            try (PreparedStatement p=connection.prepareStatement("UPDATE orders SET status=?, remaining_escrow=0 WHERE id=? AND status=?")) {
                p.setString(1,status.name()); p.setLong(2,orderId); p.setString(3,OrderStatus.ACTIVE.name());
                if(p.executeUpdate()!=1) throw new SQLException("Order is not active");
            }
            if(amount>0) try(PreparedStatement p=connection.prepareStatement("INSERT INTO recovery_obligations(obligation_id,player_uuid,amount,reason,state,created_at) VALUES(?,?,?,?,?,?)")) {
                p.setString(1,obligation);p.setString(2,player.toString());p.setDouble(3,amount);p.setString(4,reason);p.setString(5,"PENDING");p.setLong(6,System.currentTimeMillis());p.executeUpdate();
            }
            connection.commit(); return obligation;
        } catch(SQLException ex) { connection.rollback(); throw ex; }
        finally { connection.setAutoCommit(oldAuto); }
    }
    public synchronized void markRefundObligation(String id, String state) throws SQLException {
        try(PreparedStatement p=connection.prepareStatement("UPDATE recovery_obligations SET state=? WHERE obligation_id=? AND state='PENDING'")) { p.setString(1,state);p.setString(2,id);p.executeUpdate(); }
    }
    public synchronized TransactionRecord findTransaction(String id) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("SELECT * FROM order_transactions WHERE transaction_id=?")) {
            p.setString(1, id);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return null;
                return new TransactionRecord(r.getString("transaction_id"), r.getLong("order_id"),
                        r.getString("actor_uuid"), r.getInt("quantity"), r.getDouble("amount"),
                        r.getString("state"), r.getLong("created_at"), r.getString("details"));
            }
        }
    }

    public synchronized List<TransactionRecord> pendingTransactions(int limit) throws SQLException {
        List<TransactionRecord> out = new ArrayList<>();
        try (PreparedStatement p = connection.prepareStatement(
                "SELECT * FROM order_transactions WHERE state IN ('ESCROW_WITHDRAW_PENDING','DELIVERY_PREPARED','ITEMS_RESERVED','PAYMENT_PENDING','ITEM_RESTORE_PENDING','FAILED_REQUIRES_REVIEW') ORDER BY created_at ASC LIMIT ?")) {
            p.setInt(1, Math.max(1, Math.min(100, limit)));
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.add(new TransactionRecord(r.getString("transaction_id"), r.getLong("order_id"),
                        r.getString("actor_uuid"), r.getInt("quantity"), r.getDouble("amount"),
                        r.getString("state"), r.getLong("created_at"), r.getString("details")));
            }
        }
        return out;
    }

    public synchronized List<RecoveryObligation> pendingObligations(int limit) throws SQLException {
        List<RecoveryObligation> out = new ArrayList<>();
        try (PreparedStatement p = connection.prepareStatement(
                "SELECT * FROM recovery_obligations WHERE state IN ('PENDING','REVIEW','PROCESSING') ORDER BY created_at ASC LIMIT ?")) {
            p.setInt(1, Math.max(1, Math.min(100, limit)));
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.add(new RecoveryObligation(
                        r.getString("obligation_id"), UUID.fromString(r.getString("player_uuid")),
                        r.getDouble("amount"), r.getString("reason"), r.getString("state"), r.getLong("created_at")));
            }
        }
        return out;
    }

    public synchronized RecoveryObligation findObligation(String id) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("SELECT * FROM recovery_obligations WHERE obligation_id=?")) {
            p.setString(1, id);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return null;
                return new RecoveryObligation(r.getString("obligation_id"), UUID.fromString(r.getString("player_uuid")),
                        r.getDouble("amount"), r.getString("reason"), r.getString("state"), r.getLong("created_at"));
            }
        }
    }

    public synchronized boolean claimRefundObligation(String id) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement(
                "UPDATE recovery_obligations SET state='PROCESSING' WHERE obligation_id=? AND state IN ('PENDING','REVIEW')")) {
            p.setString(1, id);
            return p.executeUpdate() == 1;
        }
    }

    public synchronized void finishRefundObligation(String id, String state) throws SQLException {
        if (!List.of("PAID", "REVIEW").contains(state)) throw new IllegalArgumentException("Invalid refund state");
        try (PreparedStatement p = connection.prepareStatement(
                "UPDATE recovery_obligations SET state=? WHERE obligation_id=? AND state='PROCESSING'")) {
            p.setString(1, state);
            p.setString(2, id);
            if (p.executeUpdate() != 1) throw new SQLException("Refund obligation is no longer being processed");
        }
    }

    public synchronized int pendingTransactionCount() throws SQLException {
        try(PreparedStatement p=connection.prepareStatement("SELECT COUNT(*) FROM order_transactions WHERE state IN ('ESCROW_WITHDRAW_PENDING','DELIVERY_PREPARED','ITEMS_RESERVED','PAYMENT_PENDING','ITEM_RESTORE_PENDING','FAILED_REQUIRES_REVIEW')");ResultSet r=p.executeQuery()){return r.next()?r.getInt(1):0;}
    }
    public synchronized int pendingRefundCount() throws SQLException {
        try (PreparedStatement p=connection.prepareStatement("SELECT COUNT(*) FROM recovery_obligations WHERE state IN ('PENDING','REVIEW','PROCESSING')")) { try(ResultSet r=p.executeQuery()){return r.next()?r.getInt(1):0;} }
    }
    public synchronized void setStatus(long id, OrderStatus status) throws SQLException {
        try(PreparedStatement p=connection.prepareStatement("UPDATE orders SET status=? WHERE id=? AND status=?")){p.setString(1,status.name());p.setLong(2,id);p.setString(3,OrderStatus.ACTIVE.name());if(p.executeUpdate()!=1)throw new SQLException("Order is not active");}
    }
    private BuyOrder map(ResultSet r) throws SQLException {
        return new BuyOrder(r.getLong("id"),UUID.fromString(r.getString("owner_uuid")),r.getString("owner_name"),Material.valueOf(r.getString("material")),r.getInt("original_quantity"),r.getInt("remaining_quantity"),r.getDouble("unit_price"),r.getDouble("remaining_escrow"),r.getLong("created_at"),r.getLong("expires_at"),OrderStatus.valueOf(r.getString("status")));
    }
    @Override public synchronized void close() throws SQLException { connection.close(); }
}