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
        int slot=e.getRawSlot();
        MarketHolder holder=(MarketHolder)e.getView().getTopInventory().getHolder();
        int size=e.getView().getTopInventory().getSize();
        if(slot==size-9){ if(holder.page>0)openMarket(p,holder.page-1,holder.query,holder.sort); return; }
        if(slot==size-1){ openMarket(p,holder.page+1,holder.query,holder.sort); return; }
        if(slot==size-8){inputs.put(p.getUniqueId(),new InputState("market-search",null,holder.page,0,holder.sort.ordinal(),System.currentTimeMillis()+60_000));p.closeInventory();p.sendMessage("Search by item material, owner name, or order ID. Type a search term, or type cancel to stop.");return;}
        if(slot==size-7){openMarket(p,0,holder.query,MarketSort.values()[(holder.sort.ordinal()+1)%MarketSort.values().length]);return;}
        if(slot==size-4){if(p.hasPermission("superorders.mine"))openMine(p);else msgNoPermission(p);return;}
        if(slot==size-3){if(p.hasPermission("superorders.history"))openHistory(p);else msgNoPermission(p);return;}
        if(slot==size-5){e.setCancelled(true);if(!p.hasPermission("superorders.create")){p.sendMessage("You do not have permission to create orders.");return;}p.closeInventory();beginCreate(p);return;}
        ItemStack item=e.getCurrentItem();if(item==null||item.getType().isAir()||!item.hasItemMeta())return;
        var lore=item.getItemMeta().lore();if(lore==null)return;
        for(var line:lore){String plain=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line);if(plain.startsWith("Order #"))try{beginDelivery(p,Long.parseLong(plain.substring(7)));return;}catch(NumberFormatException ignored){}}
    }
    @EventHandler public void onManageClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)
                || !(e.getView().getTopInventory().getHolder() instanceof ManageHolder holder)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        ItemStack item=e.getCurrentItem();
        if(item==null||item.getType().isAir()||!item.hasItemMeta()||item.getItemMeta().lore()==null)return;
        for(var line:item.getItemMeta().lore()) {
            String plain=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line);
            if(!plain.startsWith("Order #"))continue;
            try {
                long id=Long.parseLong(plain.substring(7));
                BuyOrder order=plugin.repository().find(id);
                if(order==null||!order.ownerId().equals(p.getUniqueId())){p.sendMessage("That order is no longer yours.");return;}
                if(order.status()!=OrderStatus.ACTIVE){p.sendMessage("Order #"+id+" is "+order.status()+".");return;}
                inputs.put(p.getUniqueId(),new InputState("cancel-order-confirm",null,id,0,0,System.currentTimeMillis()+60_000));
                p.closeInventory();
                p.sendMessage("Cancel order #"+id+" and refund its remaining escrow? Type confirm or cancel. Do not type confirm if you do not want to cancel.");
                return;
            } catch(NumberFormatException ignored) { }
            catch(Exception ex){p.sendMessage("Could not inspect that order.");return;}
        }
    }

    @EventHandler public void onDeliveryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)
                || !(e.getView().getTopInventory().getHolder() instanceof DeliveryHolder holder)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getRawSlot();
        if (slot == 26) {
            p.closeInventory();
            return;
        }
        if (slot == 22) {
            inputs.put(p.getUniqueId(), new InputState("deliver-quantity", holder.material, holder.orderId,
                    0, 0, System.currentTimeMillis() + 60_000));
            p.closeInventory();
            p.sendMessage("Enter how many "+holder.material+" you want to deliver (maximum "+holder.maxQuantity+"), or type cancel. Keep the items in your inventory until delivery is confirmed.");
        }
    }
    @EventHandler public void onDrag(InventoryDragEvent e){if(isMarket(e.getView().getTopInventory())||e.getView().getTopInventory().getHolder() instanceof DeliveryHolder)e.setCancelled(true);}
    private boolean isMarket(Inventory i){return i.getHolder() instanceof MarketHolder;}
    public void openMarket(Player p){ openMarket(p,0,"",MarketSort.NEWEST); }
    private void openMarket(Player p,int requestedPage){openMarket(p,requestedPage,"",MarketSort.NEWEST);}
    private void openMarket(Player p,int requestedPage,String query,MarketSort sort){
        try {
            String normalized=query==null?"":query.trim().toLowerCase(Locale.ROOT);
            List<BuyOrder> list=new ArrayList<>(plugin.repository().activeOrders());
            if(!normalized.isEmpty())list.removeIf(o->!o.material().name().toLowerCase(Locale.ROOT).contains(normalized)
                    &&!o.ownerName().toLowerCase(Locale.ROOT).contains(normalized)&&!Long.toString(o.id()).contains(normalized));
            Comparator<BuyOrder> comparator=switch(sort){
                case PRICE_LOW->Comparator.comparingDouble(BuyOrder::unitPrice).thenComparing(Comparator.comparingLong(BuyOrder::createdAt).reversed());
                case PRICE_HIGH->Comparator.comparingDouble(BuyOrder::unitPrice).reversed().thenComparing(Comparator.comparingLong(BuyOrder::createdAt).reversed());
                case NEWEST->Comparator.comparingLong(BuyOrder::createdAt).reversed();
            };
            list.sort(comparator);
            int size=Math.max(27,Math.min(54,plugin.getConfig().getInt("marketplace.size",54)));
            size=((size+8)/9)*9;
            int contentSlots=size-9;
            int maxPage=Math.max(0,(list.size()-1)/contentSlots);
            int page=Math.max(0,Math.min(requestedPage,maxPage));
            Inventory inv=Bukkit.createInventory(new MarketHolder(page,normalized,sort),size,
                    mm.deserialize(plugin.getConfig().getString("marketplace.title","<dark_purple>SuperOrders</dark_purple>")));
            int start=page*contentSlots;
            int end=Math.min(list.size(),start+contentSlots);
            int slot=0;
            for(int index=start;index<end;index++){
                BuyOrder o=list.get(index);
                ItemStack icon=new ItemStack(o.material());
                ItemMeta m=icon.getItemMeta();
                m.displayName(mm.deserialize("<light_purple>"+o.material().name().replace('_',' ')+"</light_purple>"));
                m.lore(List.of(mm.deserialize("<gray>Owner: <white>"+o.ownerName()),
                        mm.deserialize("<gray>Remaining: <white>"+o.remainingQuantity()),
                        mm.deserialize("<gray>Price each: <white>"+o.unitPrice()),
                        mm.deserialize("<gray>Remaining value: <white>"+o.remainingValue()),
                        mm.deserialize("<yellow>Order #"+o.id())));
                icon.setItemMeta(m);
                inv.setItem(slot++,icon);
            }
            if(list.isEmpty())inv.setItem(size/2,named(Material.PAPER,normalized.isEmpty()?"<gray>No active orders":"<gray>No orders match your search"));
            if(page>0)inv.setItem(size-9,named(Material.ARROW,"<yellow>Previous Page"));
            inv.setItem(size-8,named(Material.COMPASS,"<aqua>Search: "+(normalized.isEmpty()?"none":normalized)));
            inv.setItem(size-7,named(Material.HOPPER,"<aqua>Sort: "+sort.label+" (click to change)"));
            inv.setItem(size-5,named(Material.EMERALD,"<green>Create Order"));
            inv.setItem(size-4,named(Material.BOOK,"<yellow>My Orders"));
            inv.setItem(size-3,named(Material.WRITABLE_BOOK,"<yellow>Transaction History"));
            if(page<maxPage)inv.setItem(size-1,named(Material.ARROW,"<yellow>Next Page"));
            p.openInventory(inv);
        } catch(Exception ex){plugin.getLogger().severe("Marketplace query failed: "+ex.getMessage());p.sendMessage("Marketplace could not be loaded.");}
    }
    private void msgNoPermission(Player p){p.sendMessage("You do not have permission to use that menu.");}
    public void openHistory(Player p) {
        try {
            var records=plugin.repository().historyFor(p.getUniqueId(),45);
            Inventory inv=Bukkit.createInventory(new HistoryHolder(),54,mm.deserialize("<dark_purple>SuperOrders History</dark_purple>"));
            int slot=0;
            for(var record:records) {
                ItemStack icon=new ItemStack(Material.PAPER);
                ItemMeta meta=icon.getItemMeta();
                meta.displayName(mm.deserialize("<light_purple>"+record.state()+"</light_purple>"));
                meta.lore(List.of(mm.deserialize("<gray>Order: <white>#"+record.orderId()),
                        mm.deserialize("<gray>Quantity: <white>"+record.quantity()),
                        mm.deserialize("<gray>Amount: <white>"+record.amount()),
                        mm.deserialize("<gray>Actor: <white>"+record.actorUuid()),
                        mm.deserialize("<gray>Time: <white>"+java.time.Instant.ofEpochMilli(record.createdAt())),
                        mm.deserialize("<dark_gray>"+record.transactionId()),
                        mm.deserialize("<gray>"+record.details())));
                icon.setItemMeta(meta);
                inv.setItem(slot++,icon);
            }
            if(records.isEmpty())inv.setItem(22,named(Material.PAPER,"<gray>No transactions recorded"));
            p.openInventory(inv);
        } catch(Exception ex){plugin.getLogger().warning("Could not load transaction history: "+ex.getMessage());p.sendMessage("Could not load transaction history.");}
    }

    public void openMine(Player p) {
        try {
            List<BuyOrder> list=plugin.repository().ordersFor(p.getUniqueId());
            int size=54;
            Inventory inv=Bukkit.createInventory(new ManageHolder(p.getUniqueId()),size,mm.deserialize("<dark_purple>Your SuperOrders</dark_purple>"));
            int slot=0;
            for(BuyOrder o:list) {
                if(slot>=45)break;
                ItemStack icon=new ItemStack(o.material());
                ItemMeta meta=icon.getItemMeta();
                meta.displayName(mm.deserialize("<light_purple>Order #"+o.id()+" - "+o.material().name().replace('_',' ')+"</light_purple>"));
                meta.lore(List.of(mm.deserialize("<gray>Status: <white>"+o.status()),
                        mm.deserialize("<gray>Remaining: <white>"+o.remainingQuantity()+"/"+o.originalQuantity()),
                        mm.deserialize("<gray>Price each: <white>"+o.unitPrice()),
                        mm.deserialize("<gray>Escrow: <white>"+o.remainingEscrow()),
                        mm.deserialize("<yellow>Order #"+o.id()),
                        mm.deserialize(o.status()==OrderStatus.ACTIVE?"<red>Click to cancel and refund remaining escrow":"<gray>This order is not active")));
                icon.setItemMeta(meta);
                inv.setItem(slot++,icon);
            }
            if(list.isEmpty())inv.setItem(22,named(Material.PAPER,"<gray>You have not created any orders yet"));
            p.openInventory(inv);
        } catch(Exception ex){plugin.getLogger().warning("Could not load player orders: "+ex.getMessage());p.sendMessage("Could not load your orders.");}
    }

    public void beginCreate(Player p){inputs.put(p.getUniqueId(),new InputState("material",null,0,0,0,System.currentTimeMillis()+60_000));p.sendMessage("Type the item material (e.g. DIAMOND), or cancel:");}
    private void beginDelivery(Player p,long id){
        try {
            BuyOrder o=plugin.repository().find(id);
            if(o==null||o.status()!=OrderStatus.ACTIVE||o.expiresAt()<=System.currentTimeMillis()){p.sendMessage("That order is no longer available.");return;}
            if(o.ownerId().equals(p.getUniqueId())){p.sendMessage("You cannot fulfill your own order.");return;}
            Inventory inv=Bukkit.createInventory(new DeliveryHolder(id,o.material(),o.remainingQuantity()),27,
                    mm.deserialize("<dark_purple>Order #"+id+" Delivery</dark_purple>"));
            ItemStack info=named(o.material(),"<light_purple>"+o.material().name().replace('_',' ')+"</light_purple>");
            ItemMeta meta=info.getItemMeta();
            meta.lore(List.of(mm.deserialize("<gray>Requested: <white>"+o.remainingQuantity()),
                    mm.deserialize("<gray>Price each: <white>"+o.unitPrice()),
                    mm.deserialize("<gray>Maximum payout: <white>"+o.remainingValue()),
                    mm.deserialize("<yellow>Your items stay in your inventory until confirmed.")));
            info.setItemMeta(meta);
            inv.setItem(13,info);
            inv.setItem(22,named(Material.LIME_DYE,"<green>Enter Delivery Quantity"));
            inv.setItem(26,named(Material.RED_DYE,"<red>Close"));
            p.openInventory(inv);
        } catch(Exception ex){p.sendMessage("Could not load order.");}
    }
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
            case "market-search"->{inputs.remove(p.getUniqueId());MarketSort sort=MarketSort.values()[Math.max(0,Math.min(MarketSort.values().length-1,(int)s.price))];openMarket(p,0,msg,sort);}
            case "cancel-order-confirm"->{if(!msg.equalsIgnoreCase("confirm"))throw new IllegalArgumentException("Type confirm or cancel.");inputs.remove(p.getUniqueId());plugin.orderService().cancel(p,s.orderId,false);p.sendMessage("Order #"+s.orderId+" cancelled. Any unconfirmed refund is recorded for administrator review.");}
            default->{inputs.remove(p.getUniqueId());p.sendMessage("That operation is not available.");}
        }}catch(Exception ex){p.sendMessage("SuperOrders: "+(ex.getMessage()==null?"Input rejected.":ex.getMessage()));}
    }
    private ItemStack named(Material material,String title){ItemStack i=new ItemStack(material);ItemMeta m=i.getItemMeta();m.displayName(mm.deserialize(title));i.setItemMeta(m);return i;}
    private enum MarketSort {
        NEWEST("Newest"), PRICE_LOW("Lowest Price"), PRICE_HIGH("Highest Price");
        private final String label;
        MarketSort(String label){this.label=label;}
    }
    private record InputState(String step,Material material,long orderId,int quantity,double price,long deadline){}
    private static final class MarketHolder implements InventoryHolder{private final int page;private final String query;private final MarketSort sort;private MarketHolder(int page,String query,MarketSort sort){this.page=page;this.query=query;this.sort=sort;}@Override public Inventory getInventory(){return null;}}
    private static final class HistoryHolder implements InventoryHolder{@Override public Inventory getInventory(){return null;}}
    private static final class ManageHolder implements InventoryHolder{private final UUID ownerId;private ManageHolder(UUID ownerId){this.ownerId=ownerId;}@Override public Inventory getInventory(){return null;}}
    private static final class DeliveryHolder implements InventoryHolder{private final long orderId;private final Material material;private final int maxQuantity;private DeliveryHolder(long orderId,Material material,int maxQuantity){this.orderId=orderId;this.material=material;this.maxQuantity=maxQuantity;}@Override public Inventory getInventory(){return null;}}
}