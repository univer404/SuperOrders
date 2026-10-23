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
        if(args.length>0&&args[0].equalsIgnoreCase("admin")){if(!sender.hasPermission("superorders.admin")){msg(sender,"admin-only");return true;}if(args.length>1&&args[1].equalsIgnoreCase("reload")){if(!sender.hasPermission("superorders.admin.reload")){msg(sender,"no-permission");return true;}plugin.reloadConfig();plugin.reloadGuiConfig();msg(sender,"reload");}
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
                    var transactions=plugin.repository().pendingTransactions(20);
                    if(transactions.isEmpty()) sender.sendMessage("No unresolved transaction journal entries.");
                    for(var tx:transactions) sender.sendMessage("Transaction "+tx.transactionId()+" | order="+tx.orderId()
                            +" | actor="+tx.actorUuid()+" | state="+tx.state()+" | amount="+tx.amount()+" | details="+tx.details());
                    sender.sendMessage("IMPORTANT: verify economy-provider history before manually paying or restoring anything. PROCESSING entries and ambiguous payouts must not be retried without reconciliation.");
                } catch(Exception ex){sender.sendMessage("Recovery inspection failed: "+ex.getMessage());}
            }
            else if(args.length>2&&args[1].equalsIgnoreCase("escrow-refund")) {
                if(!sender.hasPermission("superorders.admin.recover")){msg(sender,"no-permission");return true;}
                String transactionId=args[2];
                try {
                    var tx=plugin.repository().findTransaction(transactionId);
                    if(tx==null){sender.sendMessage("Transaction not found: "+transactionId);return true;}
                    if(!tx.state().equals("ESCROW_WITHDRAW_PENDING") && !tx.state().equals("FAILED_REQUIRES_REVIEW")) {
                        sender.sendMessage("This action only applies to an unresolved order-creation escrow transaction. Current state: "+tx.state());
                        return true;
                    }
                    if(tx.orderId()!=0) {
                        sender.sendMessage("This transaction is linked to order #"+tx.orderId()+". Use the normal order recovery procedure instead.");
                        return true;
                    }
                    java.util.UUID ownerId=java.util.UUID.fromString(tx.actorUuid());
                    sender.sendMessage("DANGER: verify the economy provider history and confirm the original escrow withdrawal succeeded. Otherwise this refund could create money.");
                    if(!plugin.repository().claimTransactionState(transactionId,tx.state(),"ESCROW_REFUND_PENDING","Administrator initiated manual escrow compensation. Payout outcome must be reconciled; do not retry blindly.")) {
                        sender.sendMessage("Transaction state changed; inspect /orders admin recover.");
                        return true;
                    }
                    try {
                        var response=plugin.economy().depositPlayer(plugin.getServer().getOfflinePlayer(ownerId),tx.amount());
                        if(response.transactionSuccess()) {
                            plugin.repository().setTransactionState(transactionId,"ESCROW_REFUNDED_MANUALLY","Administrator confirmed original withdrawal and issued compensation. Verify economy history.");
                            sender.sendMessage("Compensation reported successful. Verify provider history before considering this case closed.");
                        } else {
                            sender.sendMessage("Compensation was not confirmed. The transaction remains ESCROW_REFUND_PENDING; do not retry until provider history is reconciled.");
                            plugin.getLogger().severe("Escrow compensation not confirmed for "+transactionId+"; manual reconciliation required.");
                        }
                    } catch(Exception payoutError) {
                        sender.sendMessage("Compensation outcome is uncertain. Transaction remains ESCROW_REFUND_PENDING; do not retry until provider history is checked.");
                        plugin.getLogger().severe("Escrow compensation outcome uncertain for "+transactionId+": "+payoutError.getMessage());
                    }
                } catch(Exception ex) {
                    sender.sendMessage("Escrow recovery failed: "+ex.getMessage());
                    plugin.getLogger().severe("Escrow recovery failed for "+transactionId+": "+ex.getMessage());
                }
            }
            else if(args.length>3&&args[1].equalsIgnoreCase("resolve")) {
                if(!sender.hasPermission("superorders.admin.recover")){msg(sender,"no-permission");return true;}
                String transactionId=args[2];
                String action=args[3].toLowerCase(Locale.ROOT);
                try {
                    var tx=plugin.repository().findTransaction(transactionId);
                    if(tx==null){sender.sendMessage("Transaction not found: "+transactionId);return true;}
                    if(action.equals("paid")) {
                        if(!tx.state().equals("PAYMENT_PENDING")) {
                            sender.sendMessage("Only PAYMENT_PENDING transactions can be finalized as paid. Current state: "+tx.state());
                            return true;
                        }
                        sender.sendMessage("Verify the economy provider's history first. Finalizing will consume escrow for order #"+tx.orderId()+" and will not send another payout.");
                        plugin.repository().finishDelivery(tx.orderId(),tx.quantity(),tx.amount(),tx.transactionId());
                        sender.sendMessage("Transaction finalized as paid; no new economy payout was sent.");
                        return true;
                    }
                    if(action.equals("restore")) {
                        if(!tx.state().equals("ITEMS_RESERVED")) {
                            sender.sendMessage("Only ITEMS_RESERVED transactions can use this restore action. Current state: "+tx.state());
                            return true;
                        }
                        java.util.UUID sellerId=java.util.UUID.fromString(tx.actorUuid());
                        Player seller=plugin.getServer().getPlayer(sellerId);
                        if(seller==null){sender.sendMessage("Seller must be online to restore items safely.");return true;}
                        var order=plugin.repository().find(tx.orderId());
                        if(order==null){sender.sendMessage("Order record missing; cannot determine the item material.");return true;}
                        if(!plugin.repository().claimTransactionState(transactionId,"ITEMS_RESERVED","ITEM_RESTORE_PENDING","Administrator started item restoration; do not retry without checking inventory.")) {
                            sender.sendMessage("Transaction state changed; inspect /orders admin recover.");
                            return true;
                        }
                        org.bukkit.inventory.ItemStack items=new org.bukkit.inventory.ItemStack(order.material(),tx.quantity());
                        var leftovers=seller.getInventory().addItem(items);
                        for(var leftover:leftovers.values())seller.getWorld().dropItemNaturally(seller.getLocation(),leftover);
                        plugin.repository().setTransactionState(transactionId,"ITEMS_RESTORED","Administrator restored "+tx.quantity()+" "+order.material()+" to seller "+sellerId+".");
                        sender.sendMessage("Items restored to "+seller.getName()+". Verify their inventory and transaction journal.");
                        return true;
                    }
                    sender.sendMessage("Usage: /orders admin resolve <transaction-id> <paid|restore>");
                } catch(Exception ex) {
                    sender.sendMessage("Transaction resolution needs manual review: "+ex.getMessage());
                    plugin.getLogger().severe("Recovery failed for transaction "+transactionId+": "+ex.getMessage());
                }
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
            else sender.sendMessage("Admin: /orders admin reload|inspect <id>|cancel <id>|recover|resolve <transaction-id> <paid|restore>|escrow-refund <transaction-id>|refund <obligation-id>|debug");return true;}
        if(!(sender instanceof Player player)){msg(sender,"player-only");return true;}if(!player.hasPermission("superorders.use")){msg(player,"no-permission");return true;}
        String sub=args.length==0?"market":args[0].toLowerCase(Locale.ROOT);switch(sub){case "market"->{if(!player.hasPermission("superorders.market")){msg(player,"no-permission");}else openMarket(player);}case "create"->{if(!player.hasPermission("superorders.create")){msg(player,"no-permission");}else{plugin.marketListener().beginCreate(player);}}case "mine"->{if(!player.hasPermission("superorders.mine")){msg(player,"no-permission");}else plugin.marketListener().openMine(player);}case "history"->{if(!player.hasPermission("superorders.history")){msg(player,"no-permission");}else plugin.marketListener().openHistory(player);}case "cancel"->{if(!player.hasPermission("superorders.cancel")){msg(player,"no-permission");}else if(args.length<2){player.sendMessage("Usage: /orders cancel <orderId>");}else try{long id=Long.parseLong(args[1]);plugin.orderService().cancel(player,id,false);player.sendMessage("Order #"+id+" cancelled.");}catch(Exception ex){player.sendMessage("Could not cancel order: "+ex.getMessage());}}case "help"->player.sendMessage(mm.deserialize("<gold>SuperOrders: /orders, /orders create, /orders mine, /orders cancel <id>, /orders history"));default->{if(!player.hasPermission("superorders.market"))msg(player,"no-permission");else openMarket(player);}}return true;
    }
    private void openMarket(Player player){ plugin.marketListener().openMarket(player); }
    private ItemStack named(Material material,String title){ItemStack i=new ItemStack(material);ItemMeta m=i.getItemMeta();m.displayName(mm.deserialize(title));i.setItemMeta(m);return i;}
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){if(args.length==1)return List.of("market","create","mine","cancel","history","help","admin");if(args.length==2&&args[0].equalsIgnoreCase("admin"))return List.of("reload","inspect","cancel","refund","recover","resolve","debug");return List.of();}
}