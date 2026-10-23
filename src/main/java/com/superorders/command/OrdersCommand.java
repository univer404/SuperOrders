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
        if(args.length>0&&args[0].equalsIgnoreCase("admin")){if(!sender.hasPermission("superorders.admin")){msg(sender,"admin-only");return true;}if(args.length>1&&args[1].equalsIgnoreCase("reload")){if(!sender.hasPermission("superorders.admin.reload")){msg(sender,"no-permission");return true;}plugin.reloadConfig();msg(sender,"reload");}
            else if(args.length>1&&args[1].equalsIgnoreCase("debug")){if(!sender.hasPermission("superorders.admin.debug")){msg(sender,"no-permission");return true;}try{sender.sendMessage("SuperOrders enabled="+plugin.isEnabled()+", economy="+plugin.economy().getName()+", SQLite="+(plugin.repository()!=null)+", pendingRefunds="+plugin.repository().pendingRefundCount()+", interruptedTransactions="+plugin.repository().pendingTransactionCount());}catch(Exception ex){sender.sendMessage("Diagnostic failed: "+ex.getMessage());}}
            else if(args.length>2&&args[1].equalsIgnoreCase("inspect")){if(!sender.hasPermission("superorders.admin.inspect")){msg(sender,"no-permission");return true;}try{long id=Long.parseLong(args[2]);BuyOrder o=plugin.repository().find(id);sender.sendMessage(o==null?"Order not found.":"Order #"+o.id()+" owner="+o.ownerName()+" item="+o.material()+" remaining="+o.remainingQuantity()+" unitPrice="+o.unitPrice()+" escrow="+o.remainingEscrow()+" status="+o.status()+" expiresAt="+o.expiresAt());}catch(Exception ex){sender.sendMessage("Inspect failed: "+ex.getMessage());}}
            else if(args.length>2&&args[1].equalsIgnoreCase("cancel")){if(!sender.hasPermission("superorders.admin.cancel")){msg(sender,"no-permission");return true;}try{long id=Long.parseLong(args[2]);if(!(sender instanceof Player actor)){sender.sendMessage("Run this from in-game for now.");return true;}plugin.orderService().cancel(actor,id,true);sender.sendMessage("Cancellation processed for order #"+id+".");}catch(Exception ex){sender.sendMessage("Cancel failed: "+ex.getMessage());}}
            else if(args.length>1&&args[1].equalsIgnoreCase("recover")) {
                if(!sender.hasPermission("superorders.admin.recover")){msg(sender,"no-permission");return true;}
                try {
                    sender.sendMessage("Pending refund obligations: "+plugin.repository().pendingRefundCount()
                            +", interrupted transaction records: "+plugin.repository().pendingTransactionCount()+".");
                    var obligations=plugin.repository().pendingObligations(20);
                    if(obligations.isEmpty()) sender.sendMessage("No open refund obligations.");
                    for(var obligation:obligations) sender.sendMessage("Refund "+obligation.id()+" | player="+obligation.playerId()
                            +" | amount="+obligation.amount()+" | state="+obligation.state()+" | reason="+obligation.reason());
                    sender.sendMessage("IMPORTANT: verify the economy transaction history before manually paying an obligation. PROCESSING entries must not be retried without reconciliation.");
                } catch(Exception ex){sender.sendMessage("Recovery inspection failed: "+ex.getMessage());}
            }
            else if(args.length>2&&args[1].equalsIgnoreCase("refund")) {
                if(!sender.hasPermission("superorders.admin.refund")){msg(sender,"no-permission");return true;}
                String id=args[2];
                try {
                    var obligation=plugin.repository().findObligation(id);
                    if(obligation==null){sender.sendMessage("Refund obligation not found: "+id);return true;}
                    if(!List.of("PENDING","REVIEW").contains(obligation.state())) {
                        sender.sendMessage("Cannot process obligation in state "+obligation.state()+". PROCESSING or PAID entries are not retryable.");
                        return true;
                    }
                    if(!plugin.repository().claimRefundObligation(id)) {
                        sender.sendMessage("Another operation already claimed this obligation. Check /orders admin recover.");
                        return true;
                    }
                    sender.sendMessage("Processing refund obligation "+id+" for "+obligation.amount()+" to "+obligation.playerId()+". Verify the account history first to avoid a duplicate payout.");
                    try {
                        var response=plugin.economy().depositPlayer(plugin.getServer().getOfflinePlayer(obligation.playerId()),obligation.amount());
                        if(response.transactionSuccess()) {
                            plugin.repository().finishRefundObligation(id,"PAID");
                            sender.sendMessage("Refund marked PAID.");
                        } else {
                            plugin.repository().finishRefundObligation(id,"REVIEW");
                            sender.sendMessage("Economy did not confirm the payout. Obligation moved to REVIEW; reconcile provider history before retrying.");
                        }
                    } catch(Exception payoutError) {
                        sender.sendMessage("Payout outcome is uncertain. The obligation remains PROCESSING and will not be retried automatically. Check provider history before any manual action.");
                        plugin.getLogger().severe("Refund payout outcome uncertain for obligation "+id+": "+payoutError.getMessage());
                    }
                } catch(Exception ex){sender.sendMessage("Refund processing failed: "+ex.getMessage());}
            }
            else sender.sendMessage("Admin: /orders admin reload|inspect <id>|cancel <id>|recover|refund <obligation-id>|debug");return true;}
        if(!(sender instanceof Player player)){msg(sender,"player-only");return true;}if(!player.hasPermission("superorders.use")){msg(player,"no-permission");return true;}
        String sub=args.length==0?"market":args[0].toLowerCase(Locale.ROOT);switch(sub){case "market"->{if(!player.hasPermission("superorders.market")){msg(player,"no-permission");}else openMarket(player);}case "create"->{if(!player.hasPermission("superorders.create")){msg(player,"no-permission");}else{plugin.marketListener().beginCreate(player);}}case "mine"->{if(!player.hasPermission("superorders.mine")){msg(player,"no-permission");}else plugin.marketListener().openMine(player);}case "history"->{if(!player.hasPermission("superorders.history")){msg(player,"no-permission");break;}try{var history=plugin.repository().historyFor(player.getUniqueId(),20);if(history.isEmpty())player.sendMessage("No transactions recorded.");else for(var t:history)player.sendMessage("#"+t.orderId()+" "+t.state()+" qty="+t.quantity()+" amount="+t.amount()+" at="+java.time.Instant.ofEpochMilli(t.createdAt()));}catch(Exception ex){player.sendMessage("Could not load transaction history.");}}case "cancel"->{if(!player.hasPermission("superorders.cancel")){msg(player,"no-permission");}else if(args.length<2){player.sendMessage("Usage: /orders cancel <orderId>");}else try{long id=Long.parseLong(args[1]);plugin.orderService().cancel(player,id,false);player.sendMessage("Order #"+id+" cancelled.");}catch(Exception ex){player.sendMessage("Could not cancel order: "+ex.getMessage());}}case "help"->player.sendMessage(mm.deserialize("<gold>SuperOrders: /orders, /orders create, /orders mine, /orders cancel <id>, /orders history"));default->{if(!player.hasPermission("superorders.market"))msg(player,"no-permission");else openMarket(player);}}return true;
    }
    private void openMarket(Player player){ plugin.marketListener().openMarket(player); }
    private ItemStack named(Material material,String title){ItemStack i=new ItemStack(material);ItemMeta m=i.getItemMeta();m.displayName(mm.deserialize(title));i.setItemMeta(m);return i;}
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){if(args.length==1)return List.of("market","create","mine","cancel","history","help","admin");if(args.length==2&&args[0].equalsIgnoreCase("admin"))return List.of("reload","inspect","cancel","refund","recover","debug");return List.of();}
}