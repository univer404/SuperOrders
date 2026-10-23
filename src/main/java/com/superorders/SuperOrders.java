package com.superorders;

import com.superorders.command.OrdersCommand;
import com.superorders.gui.MarketInventoryListener;
import com.superorders.service.OrderService;
import com.superorders.storage.OrderRepository;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.sql.SQLException;

public final class SuperOrders extends JavaPlugin {
    private Economy economy;
    private OrderRepository repository;
    private OrderService orderService;
    @Override public void onEnable() {
        saveDefaultConfig(); saveResource("messages.yml", false);
        if (!setupEconomy()) { getLogger().severe("Vault-compatible economy provider not found. Disabling SuperOrders."); getServer().getPluginManager().disablePlugin(this); return; }
        try { if (!getDataFolder().exists() && !getDataFolder().mkdirs()) throw new SQLException("Cannot create plugin data folder"); repository = new OrderRepository(new File(getDataFolder(),getConfig().getString("database.file","orders.db"))); }
        catch(SQLException e){getLogger().severe("SQLite initialization failed: "+e.getMessage());getServer().getPluginManager().disablePlugin(this);return;}
        orderService = new OrderService(this);
        getServer().getPluginManager().registerEvents(new MarketInventoryListener(this, orderService), this);
        OrdersCommand handler = new OrdersCommand(this);
        getCommand("orders").setExecutor(handler); getCommand("orders").setTabCompleter(handler);
        getLogger().info("SuperOrders foundation loaded; order creation and delivery transactions are not yet production-ready.");
    }
    private boolean setupEconomy(){if(getServer().getPluginManager().getPlugin("Vault")==null)return false;RegisteredServiceProvider<Economy> p=getServer().getServicesManager().getRegistration(Economy.class);if(p==null)return false;economy=p.getProvider();return economy!=null;}
    public Economy economy(){return economy;} public OrderRepository repository(){return repository;} public OrderService orderService(){return orderService;}
    @Override public void onDisable(){if(repository!=null)try{repository.close();}catch(SQLException e){getLogger().severe("SQLite close failed: "+e.getMessage());}}
}