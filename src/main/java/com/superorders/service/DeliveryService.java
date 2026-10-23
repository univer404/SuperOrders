package com.superorders.service;

import com.superorders.SuperOrders;
import com.superorders.model.BuyOrder;
import com.superorders.model.OrderStatus;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.concurrent.locks.ReentrantLock;

public final class DeliveryService {
    private final SuperOrders plugin;
    public DeliveryService(SuperOrders plugin) { this.plugin=plugin; }

    public double deliver(Player seller, long orderId, int quantity) throws Exception {
        if (quantity < 1) throw new IllegalArgumentException("Quantity must be positive.");
        if (quantity > plugin.getConfig().getInt("delivery.max-items-per-delivery", 23040)) throw new IllegalArgumentException("That delivery exceeds the configured maximum.");
        ReentrantLock lock=plugin.orderService().lockFor(orderId);
        lock.lock();
        try {
            BuyOrder order=plugin.repository().find(orderId);
            if(order==null||order.status()!=OrderStatus.ACTIVE||order.expiresAt()<=System.currentTimeMillis())throw new IllegalArgumentException("Order is no longer active.");
            if(order.ownerId().equals(seller.getUniqueId()))throw new IllegalArgumentException("You cannot fulfill your own order.");
            if(plugin.repository().hasUnresolvedDelivery(orderId))throw new IllegalStateException("This order has an interrupted delivery requiring administrator review.");
            if(quantity>order.remainingQuantity())throw new IllegalArgumentException("Quantity exceeds the remaining order amount.");
            double amount=order.unitPrice()*quantity;
            if(!Double.isFinite(amount)||amount<=0||amount>order.remainingEscrow()+1e-7)throw new IllegalStateException("Escrow validation failed.");
            Material material=order.material();
            ItemStack template=new ItemStack(material);
            int found=0;
            for(ItemStack stack:seller.getInventory().getStorageContents())if(stack!=null&&stack.isSimilar(template))found+=stack.getAmount();
            if(found<quantity)throw new IllegalArgumentException("You do not have enough plain "+material+" items. Named or modified items are not accepted.");
            String tx=plugin.repository().recordTransaction(seller.getUniqueId(),orderId,quantity,amount,"DELIVERY_PREPARED","seller="+seller.getUniqueId()+"; material="+material.name());
            removeItems(seller,template,quantity);
            plugin.repository().setTransactionState(tx,"ITEMS_RESERVED","Removed "+quantity+" "+material+" from seller inventory.");
            plugin.repository().setTransactionState(tx,"PAYMENT_PENDING","Payout attempt about to be made; uncertain outcomes require manual review.");
            var payout=plugin.economy().depositPlayer(seller,amount);
            if(!payout.transactionSuccess()){
                returnItems(seller,material,quantity);
                plugin.repository().setTransactionState(tx,"PAYOUT_FAILED_RETURNED","Economy rejected payout; items returned. "+payout.errorMessage);
                throw new IllegalStateException("Payment failed and the items were returned.");
            }
            try {
                plugin.repository().finishDelivery(orderId,quantity,amount,tx);
            } catch(Exception finalizeFailure) {
                plugin.getLogger().severe("CRITICAL: payout succeeded but delivery finalization failed. transaction="+tx+" order="+orderId+" seller="+seller.getUniqueId()+" amount="+amount+"; order locked for manual review.");
                throw new IllegalStateException("Payment was sent, but order finalization needs administrator review. Transaction "+tx,finalizeFailure);
            }
            seller.sendMessage("Delivered "+quantity+" "+material+" for $"+amount+" (order #"+orderId+").");
            Player owner=plugin.getServer().getPlayer(order.ownerId());
            if(owner!=null)owner.sendMessage(seller.getName()+" delivered "+quantity+" "+material+" to order #"+orderId+".");
            return amount;
        } finally { lock.unlock(); }
    }

    private void removeItems(Player player,ItemStack template,int quantity) {
        int left=quantity;
        for(int slot=0;slot<player.getInventory().getStorageContents().length&&left>0;slot++){
            ItemStack stack=player.getInventory().getItem(slot);
            if(stack==null||!stack.isSimilar(template))continue;
            int take=Math.min(left,stack.getAmount());stack.setAmount(stack.getAmount()-take);
            if(stack.getAmount()<=0)player.getInventory().setItem(slot,null);
            left-=take;
        }
        if(left!=0)throw new IllegalStateException("Inventory changed while reserving items.");
    }
    private void returnItems(Player player,Material material,int quantity) {
        ItemStack remaining=new ItemStack(material,quantity);
        var leftovers=player.getInventory().addItem(remaining);
        for(ItemStack item:leftovers.values())player.getWorld().dropItemNaturally(player.getLocation(),item);
    }
}
