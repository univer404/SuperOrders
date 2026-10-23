package com.superorders.command;

import com.superorders.SuperOrders;
import com.superorders.model.BuyOrder;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.sql.SQLException;
import java.util.*;

public final class OrdersCommand implements CommandExecutor, TabCompleter {
    private final SuperOrders plugin; private final MiniMessage mm=MiniMessage.miniMessage();
    public OrdersCommand(SuperOrders plugin){this.plugin=plugin;}
    private void msg(CommandSender sender,String key){YamlConfiguration y=YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"messages.yml"));sender.sendMessage(mm.deserialize(y.getString("prefix","")+y.getString(key,"<red>Unknown message")));}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(args.length>0&&args[0].equalsIgnoreCase("admin")){if(!sender.hasPermission("superorders.admin")){msg(sender,"admin-only");return true;}if(args.length>1&&args[1].equalsIgnoreCase("reload")){plugin.reloadConfig();msg(sender,"reload");}else if(args.length>1&&args[1].equalsIgnoreCase("debug")){sender.sendMessage("SuperOrders enabled="+plugin.isEnabled()+", economy="+plugin.economy().getName()+", SQLite="+(plugin.repository()!=null));}else sender.sendMessage("Admin: /orders admin reload|debug");return true;}
        if(!(sender instanceof Player player)){msg(sender,"player-only");return true;}if(!player.hasPermission("superorders.use")){msg(player,"no-permission");return true;}
        String sub=args.length==0?"market":args[0].toLowerCase(Locale.ROOT);switch(sub){case "market"->openMarket(player);case "create"->player.sendMessage(mm.deserialize("<yellow>Order creation is not enabled yet; no funds were withdrawn."));case "mine"->player.sendMessage(mm.deserialize("<yellow>Your order management view is not enabled yet."));case "history"->player.sendMessage(mm.deserialize("<yellow>Transaction history view is not enabled yet."));case "help"->player.sendMessage(mm.deserialize("<gold>SuperOrders: /orders, /orders create, /orders mine, /orders history"));default->openMarket(player);}return true;
    }
    private void openMarket(Player player){try{List<BuyOrder> orders=plugin.repository().activeOrders();int size=Math.max(9,Math.min(54,plugin.getConfig().getInt("marketplace.size",54)));size=((size+8)/9)*9;Inventory inv=Bukkit.createInventory(player,size,mm.deserialize(plugin.getConfig().getString("marketplace.title","<dark_purple>SuperOrders</dark_purple>")));int slot=0;for(BuyOrder order:orders){if(slot>=size-9)break;ItemStack icon=new ItemStack(order.material());ItemMeta meta=icon.getItemMeta();meta.displayName(mm.deserialize("<light_purple>"+order.material().name().replace('_',' ')+"</light_purple>"));meta.lore(List.of(mm.deserialize("<gray>Owner: <white>"+order.ownerName()),mm.deserialize("<gray>Remaining: <white>"+order.remainingQuantity()),mm.deserialize("<gray>Price each: <white>"+order.unitPrice()),mm.deserialize("<gray>Remaining value: <white>"+order.remainingValue()),mm.deserialize("<yellow>Order #"+order.id())));icon.setItemMeta(meta);inv.setItem(slot++,icon);}if(orders.isEmpty())inv.setItem(size/2,named(Material.PAPER,"<gray>No active orders"));inv.setItem(size-5,named(Material.EMERALD,"<green>Create Order"));player.openInventory(inv);}catch(SQLException e){plugin.getLogger().severe("Marketplace query failed: "+e.getMessage());msg(player,"database-error");}}
    private ItemStack named(Material material,String title){ItemStack i=new ItemStack(material);ItemMeta m=i.getItemMeta();m.displayName(mm.deserialize(title));i.setItemMeta(m);return i;}
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){if(args.length==1)return List.of("market","create","mine","history","help","admin");if(args.length==2&&args[0].equalsIgnoreCase("admin"))return List.of("reload","inspect","cancel","refund","recover","debug");return List.of();}
}