package com.superorders.storage;

import com.superorders.model.BuyOrder;
import com.superorders.model.OrderStatus;
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
            try (PreparedStatement p=connection.prepareStatement("UPDATE orders SET status=? WHERE id=? AND status=?")) {
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
    public synchronized int pendingRefundCount() throws SQLException {
        try (PreparedStatement p=connection.prepareStatement("SELECT COUNT(*) FROM recovery_obligations WHERE state=?")) { p.setString(1,"PENDING"); try(ResultSet r=p.executeQuery()){return r.next()?r.getInt(1):0;} }
    }
    public synchronized void setStatus(long id, OrderStatus status) throws SQLException {
        try(PreparedStatement p=connection.prepareStatement("UPDATE orders SET status=? WHERE id=? AND status=?")){p.setString(1,status.name());p.setLong(2,id);p.setString(3,OrderStatus.ACTIVE.name());if(p.executeUpdate()!=1)throw new SQLException("Order is not active");}
    }
    private BuyOrder map(ResultSet r) throws SQLException {
        return new BuyOrder(r.getLong("id"),UUID.fromString(r.getString("owner_uuid")),r.getString("owner_name"),Material.valueOf(r.getString("material")),r.getInt("original_quantity"),r.getInt("remaining_quantity"),r.getDouble("unit_price"),r.getDouble("remaining_escrow"),r.getLong("created_at"),r.getLong("expires_at"),OrderStatus.valueOf(r.getString("status")));
    }
    @Override public synchronized void close() throws SQLException { connection.close(); }
}