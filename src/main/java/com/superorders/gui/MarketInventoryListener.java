package com.superorders.gui;

import com.superorders.SuperOrders;
import com.superorders.model.BuyOrder;
import com.superorders.model.OrderStatus;
import com.superorders.service.OrderService;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class MarketInventoryListener implements Listener {
    private final SuperOrders plugin;
    private final OrderService orders;
    private final MiniMessage mm=MiniMessage.miniMessage();
    private final Map<UUID,InputState> inputs=new ConcurrentHashMap<>();
    public MarketInventoryListener(SuperOrders plugin,OrderService orders){this.plugin=plugin;this.orders=orders;}
    @EventHandler public void onClick(InventoryClickEvent e){
        if(!(e.getWhoClicked() instanceof Player p)||!isMarket(e.getView().getTopInventory()))return;
        e.setCancelled(true); if(e.getClickedInventory()!=e.getView().getTopInventory())return;
        int slot=e.getRawSlot(); if(slot==e.getView().getTopInventory().getSize()-5){e.setCancelled(true);if(!p.hasPermission("superorders.create")){p.sendMessage("You do not have permission to create orders.");return;}p.closeInventory();beginCreate(p);return;}
        ItemStack item=e.getCurrentItem();if(item==null||item.getType().isAir()||!item.hasItemMeta())return;
        var lore=item.getItemMeta().lore();if(lore==null)return;
        for(var line:lore){String plain=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line);if(plain.startsWith("Order #"))try{beginDelivery(p,Long.parseLong(plain.substring(7)));return;}catch(NumberFormatException ignored){}}
    }
    @EventHandler public void onDrag(InventoryDragEvent e){if(isMarket(e.getView().getTopInventory()))e.setCancelled(true);}
    private boolean isMarket(Inventory i){return i.getHolder() instanceof MarketHolder;}
    public void openMarket(Player p){
        try{List<BuyOrder> list=plugin.repository().activeOrders();int size=Math.max(27,Math.min(54,plugin.getConfig().getInt("marketplace.size",54)));size=((size+8)/9)*9;
            Inventory inv=Bukkit.createInventory(new MarketHolder(),size,mm.deserialize(plugin.getConfig().getString("marketplace.title","<dark_purple>SuperOrders</dark_purple>")));int slot=0;
            for(BuyOrder o:list){if(slot>=size-9)break;ItemStack icon=new ItemStack(o.material());ItemMeta m=icon.getItemMeta();m.displayName(mm.deserialize("<light_purple>"+o.material().name().replace('_',' ')+"</light_purple>"));m.lore(List.of(mm.deserialize("<gray>Owner: <white>"+o.ownerName()),mm.deserialize("<gray>Remaining: <white>"+o.remainingQuantity()),mm.deserialize("<gray>Price each: <white>"+o.unitPrice()),mm.deserialize("<gray>Remaining value: <white>"+o.remainingValue()),mm.deserialize("<yellow>Order #"+o.id())));icon.setItemMeta(m);inv.setItem(slot++,icon);}
            if(list.isEmpty())inv.setItem(size/2,named(Material.PAPER,"<gray>No active orders"));inv.setItem(size-5,named(Material.EMERALD,"<green>Create Order"));p.openInventory(inv);
        }catch(Exception ex){plugin.getLogger().severe("Marketplace query failed: "+ex.getMessage());p.sendMessage("Marketplace could not be loaded.");}
    }
    public void beginCreate(Player p){inputs.put(p.getUniqueId(),new InputState("material",null,0,0,0,System.currentTimeMillis()+60_000));p.sendMessage("Type the item material (e.g. DIAMOND), or cancel:");}
    private void beginDelivery(Player p,long id){try{BuyOrder o=plugin.repository().find(id);if(o==null||o.status()!=OrderStatus.ACTIVE||o.expiresAt()<=System.currentTimeMillis()){p.sendMessage("That order is no longer available.");return;}inputs.put(p.getUniqueId(),new InputState("deliver-quantity",o.material(),id,0,0,System.currentTimeMillis()+60_000));p.sendMessage("Order #"+id+" requests "+o.remainingQuantity()+" "+o.material()+" at "+o.unitPrice()+" each. Enter quantity to deliver, or cancel:");}catch(Exception ex){p.sendMessage("Could not load order.");}}
    @EventHandler public void onChat(AsyncPlayerChatEvent e){Player p=e.getPlayer();InputState s=inputs.get(p.getUniqueId());if(s==null)return;e.setCancelled(true);String msg=e.getMessage().trim();Bukkit.getScheduler().runTask(plugin,()->process(p,s,msg));}
    private void process(Player p,InputState s,String msg){
        if(!inputs.containsKey(p.getUniqueId()))return;if(System.currentTimeMillis()>s.deadline){inputs.remove(p.getUniqueId());p.sendMessage("Input expired.");return;}if(msg.equalsIgnoreCase("cancel")){inputs.remove(p.getUniqueId());p.sendMessage("Cancelled.");return;}
        try{switch(s.step){
            case "material"->{Material m=Material.matchMaterial(msg);if(m==null||m.isAir()||!m.isItem())throw new IllegalArgumentException("Invalid item.");inputs.put(p.getUniqueId(),new InputState("quantity",m,0,0,0,s.deadline));p.sendMessage("Enter quantity:");}
            case "quantity"->{int q=Integer.parseInt(msg);if(q<1||q>plugin.getConfig().getInt("orders.max-quantity",23040))throw new IllegalArgumentException("Invalid quantity.");inputs.put(p.getUniqueId(),new InputState("price",s.material,0,q,0,s.deadline));p.sendMessage("Enter price per item:");}
            case "price"->{double price=Double.parseDouble(msg);if(!Double.isFinite(price)||price<=0)throw new IllegalArgumentException("Invalid price.");double total=price*s.quantity;p.sendMessage("Create "+s.quantity+" "+s.material+" for "+total+" total? Type confirm or cancel.");inputs.put(p.getUniqueId(),new InputState("confirm",s.material,0,s.quantity,price,s.deadline));}
            case "confirm"->{if(!msg.equalsIgnoreCase("confirm"))throw new IllegalArgumentException("Type confirm or cancel.");long id=orders.create(p,s.material,s.quantity,s.price);inputs.remove(p.getUniqueId());p.sendMessage("Order #"+id+" created; escrow reserved.");}
            case "deliver-quantity"->{int q=Integer.parseInt(msg);if(q<1)throw new IllegalArgumentException("Quantity must be positive.");BuyOrder o=plugin.repository().find(s.orderId);if(o==null||o.status()!=OrderStatus.ACTIVE||q>o.remainingQuantity())throw new IllegalArgumentException("Order changed or quantity exceeds remaining amount.");double total=o.unitPrice()*q;p.sendMessage("Deliver "+q+" "+o.material()+" for $"+total+"? Make sure the items are in your inventory. Type confirm or cancel.");inputs.put(p.getUniqueId(),new InputState("deliver-confirm",s.material,s.orderId,q,total,s.deadline));}
            case "deliver-confirm"->{if(!msg.equalsIgnoreCase("confirm"))throw new IllegalArgumentException("Type confirm or cancel.");inputs.remove(p.getUniqueId());plugin.deliveryService().deliver(p,s.orderId,s.quantity); }
            default->{inputs.remove(p.getUniqueId());p.sendMessage("That operation is not available.");}
        }}catch(Exception ex){p.sendMessage("SuperOrders: "+(ex.getMessage()==null?"Input rejected.":ex.getMessage()));}
    }
    private ItemStack named(Material material,String title){ItemStack i=new ItemStack(material);ItemMeta m=i.getItemMeta();m.displayName(mm.deserialize(title));i.setItemMeta(m);return i;}
    private record InputState(String step,Material material,long orderId,int quantity,double price,long deadline){}
    private static final class MarketHolder implements InventoryHolder{@Override public Inventory getInventory(){return null;}}
}