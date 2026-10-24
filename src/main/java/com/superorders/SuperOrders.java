package com.superorders;

import com.superorders.command.OrdersCommand;
import com.superorders.gui.MarketInventoryListener;
import com.superorders.integration.SuperOrdersExpansion;
import com.superorders.service.OrderService;
import com.superorders.service.DeliveryService;
import com.superorders.storage.OrderRepository;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.sql.SQLException;

public final class SuperOrders extends JavaPlugin {
    private Economy economy;
    private OrderRepository repository;
    private OrderService orderService;
    private DeliveryService deliveryService;
    private MarketInventoryListener marketListener;
    private FileConfiguration guiConfig;
    private File guiFile;
    private long guiConfigLastModified = -1L;
    @Override public void onEnable() {
        saveDefaultConfig(); saveResourceIfMissing("messages.yml"); saveResourceIfMissing("gui.yml");
        reloadGuiConfig();
        if (!setupEconomy()) { getLogger().severe("Vault-compatible economy provider not found. Disabling SuperOrders."); getServer().getPluginManager().disablePlugin(this); return; }
        try { if (!getDataFolder().exists() && !getDataFolder().mkdirs()) throw new SQLException("Cannot create plugin data folder"); repository = new OrderRepository(new File(getDataFolder(), "orders.yml")); }
        catch(SQLException e){getLogger().severe("YAML storage initialization failed: "+e.getMessage());getServer().getPluginManager().disablePlugin(this);return;}
        if (getConfig().getBoolean("transactions.recovery-on-startup", true)) {
            int pendingRefunds = repository.pendingRefundCount();
            int pendingTransactions = repository.pendingTransactionCount();
            if (pendingRefunds > 0 || pendingTransactions > 0) {
                getLogger().warning("Recovery review required: "+pendingRefunds+" refund obligations and "
                        +pendingTransactions+" unresolved transactions. Use /orders admin recover; no payouts are retried automatically.");
            }
        }
        orderService = new OrderService(this);
        deliveryService = new DeliveryService(this);
        marketListener = new MarketInventoryListener(this, orderService);
        getServer().getPluginManager().registerEvents(marketListener, this);
        OrdersCommand handler = new OrdersCommand(this);
        getCommand("orders").setExecutor(handler); getCommand("orders").setTabCompleter(handler);
        getServer().getScheduler().runTaskTimer(this, () -> orderService.expireDueOrders(), 20L, 1200L);
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new SuperOrdersExpansion(this).register();
            getLogger().info("PlaceholderAPI expansion registered.");
        }
        getLogger().info("SuperOrders loaded. Review the README transaction-safety notes before production use.");
    }
    private void saveResourceIfMissing(String path) {
        File target = new File(getDataFolder(), path);
        if (target.exists()) return;
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            getLogger().severe("Could not create configuration directory for " + path);
            return;
        }
        saveResource(path, false);
    }
    private boolean setupEconomy(){if(getServer().getPluginManager().getPlugin("Vault")==null)return false;RegisteredServiceProvider<Economy> p=getServer().getServicesManager().getRegistration(Economy.class);if(p==null)return false;economy=p.getProvider();return economy!=null;}
    public Economy economy(){return economy;} public OrderRepository repository(){return repository;} public OrderService orderService(){return orderService;} public DeliveryService deliveryService(){return deliveryService;} public MarketInventoryListener marketListener(){return marketListener;} public FileConfiguration guiConfig(){
        if (guiFile == null) guiFile = new File(getDataFolder(), "gui.yml");
        if (guiFile.exists() && guiFile.lastModified() != guiConfigLastModified) reloadGuiConfig();
        return guiConfig;
    }
    public void reloadGuiConfig(){
        if (guiFile == null) guiFile = new File(getDataFolder(), "gui.yml");
        guiConfig = YamlConfiguration.loadConfiguration(guiFile);
        guiConfigLastModified = guiFile.exists() ? guiFile.lastModified() : -1L;
    }
    @Override public void onDisable(){if(repository!=null)repository.close();}
}