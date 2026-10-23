package com.superorders.gui;

import com.superorders.SuperOrders;
import com.superorders.model.BuyOrder;
import com.superorders.model.OrderStatus;
import com.superorders.service.OrderService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
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
        if(slot==configuredSlot("previous-slot",size,9)){ if(holder.page>0)openMarket(p,holder.page-1,holder.query,holder.sort); return; }
        if(slot==configuredSlot("next-slot",size,1)){ openMarket(p,holder.page+1,holder.query,holder.sort); return; }
        if(slot==configuredSlot("search-slot",size,8)){inputs.put(p.getUniqueId(),new InputState("market-search",null,holder.page,0,holder.sort.ordinal(),System.currentTimeMillis()+60_000));p.closeInventory();p.sendMessage("Search by item material, owner name, or order ID. Type a search term, or type cancel to stop.");return;}
        if(slot==configuredSlot("sort-slot",size,7)){openMarket(p,0,holder.query,MarketSort.values()[(holder.sort.ordinal()+1)%MarketSort.values().length]);return;}
        if(slot==configuredSlot("mine-slot",size,4)){if(p.hasPermission("superorders.mine"))openMine(p);else msgNoPermission(p);return;}
        if(slot==configuredSlot("history-slot",size,3)){if(p.hasPermission("superorders.history"))openHistory(p);else msgNoPermission(p);return;}
        if(slot==configuredSlot("create-slot",size,5)){e.setCancelled(true);if(!p.hasPermission("superorders.create")){p.sendMessage("You do not have permission to create orders.");return;}p.closeInventory();beginCreate(p);return;}
        ItemStack item=e.getCurrentItem();if(item==null||item.getType().isAir()||!item.hasItemMeta())return;
        var lore=item.getItemMeta().lore();if(lore==null)return;
        for(var line:lore){String plain=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line);if(plain.startsWith("Order #"))try{beginDelivery(p,Long.parseLong(plain.substring(7)));return;}catch(NumberFormatException ignored){}}
    }
    @EventHandler public void onHistoryClick(InventoryClickEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof HistoryHolder) e.setCancelled(true);
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
        if(slot==10){openDelivery(p,holder.orderId,holder.material,holder.maxQuantity,Math.max(1,holder.quantity-1));return;}
        if(slot==16){openDelivery(p,holder.orderId,holder.material,holder.maxQuantity,Math.min(holder.maxQuantity,holder.quantity+1));return;}
        if(slot==22){
            BuyOrder order=findOrderQuietly(holder.orderId);
            if(order==null||order.status()!=OrderStatus.ACTIVE||holder.quantity>order.remainingQuantity()){p.sendMessage("Order changed. Reopen the marketplace and try again.");p.closeInventory();return;}
            double total=order.unitPrice()*holder.quantity;
            inputs.put(p.getUniqueId(),new InputState("deliver-confirm",holder.material,holder.orderId,holder.quantity,total,System.currentTimeMillis()+60_000));
            p.closeInventory();
            p.sendMessage("Deliver "+holder.quantity+" "+holder.material+" for $"+total+"? Type confirm or cancel. Keep the items in your inventory until confirmed.");
        }
    }
    @EventHandler public void onDrag(InventoryDragEvent e){InventoryHolder holder=e.getView().getTopInventory().getHolder();if(holder instanceof MarketHolder||holder instanceof DeliveryHolder||holder instanceof ManageHolder||holder instanceof HistoryHolder||holder instanceof CreateHolder)e.setCancelled(true);}
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
            int size=Math.max(27,Math.min(54,plugin.guiConfig().getInt("marketplace.size",plugin.getConfig().getInt("marketplace.size",54))));
            size=((size+8)/9)*9;
            int contentSlots=size-9;
            int maxPage=Math.max(0,(list.size()-1)/contentSlots);
            int page=Math.max(0,Math.min(requestedPage,maxPage));
            Inventory inv=Bukkit.createInventory(new MarketHolder(page,normalized,sort),size,
                    text(plugin.guiConfig().getString("marketplace.title",plugin.getConfig().getString("marketplace.title","<dark_purple>SuperOrders</dark_purple>"))));
            // Keep the marketplace visually organized: a compact item grid above a dedicated control bar.
            for(int i=contentSlots;i<size;i++) inv.setItem(i,named(Material.GRAY_STAINED_GLASS_PANE,"<dark_gray> "));
            int start=page*contentSlots;
            int end=Math.min(list.size(),start+contentSlots);
            int slot=0;
            for(int index=start;index<end;index++){
                BuyOrder o=list.get(index);
                ItemStack icon=new ItemStack(o.material());
                ItemMeta m=icon.getItemMeta();
                m.displayName(mm.deserialize("<aqua><bold>"+o.material().name().replace('_',' ')+"</bold></aqua>"));
                m.lore(List.of(mm.deserialize("<gray>Owner: <white>"+o.ownerName()),
                        mm.deserialize("<gray>Remaining: <white>"+o.remainingQuantity()),
                        mm.deserialize("<gray>Unit price: <green>"+o.unitPrice()),
                        mm.deserialize("<gray>Order value: <green>"+o.remainingValue()),
                        mm.deserialize("<dark_gray>Order #"+o.id()),
                        mm.deserialize("<yellow>Click to deliver items")));
                icon.setItemMeta(m);
                inv.setItem(slot++,icon);
            }
            if(list.isEmpty())inv.setItem(size/2,named(Material.PAPER,normalized.isEmpty()?"<gray>No active orders":"<gray>No orders match your search"));
            if(page>0)inv.setItem(configuredSlot("previous-slot",size,9),named(Material.ARROW,"<yellow>Previous Page"));
            inv.setItem(configuredSlot("search-slot",size,8),named(Material.COMPASS,"<aqua>Search"));
            inv.setItem(configuredSlot("sort-slot",size,7),named(Material.HOPPER,"<aqua>Sort: "+sort.label));
            inv.setItem(configuredSlot("create-slot",size,5),named(Material.EMERALD,"<green>Create Order"));
            inv.setItem(configuredSlot("mine-slot",size,4),named(Material.BOOK,"<yellow>My Orders"));
            inv.setItem(configuredSlot("history-slot",size,3),named(Material.WRITABLE_BOOK,"<yellow>History"));
            if(page<maxPage)inv.setItem(configuredSlot("next-slot",size,1),named(Material.ARROW,"<yellow>Next Page"));
            p.openInventory(inv);
        } catch(Exception ex){plugin.getLogger().severe("Marketplace query failed: "+ex.getMessage());p.sendMessage("Marketplace could not be loaded.");}
    }
    private int configuredSlot(String key,int size,int fallbackOffset) {
        int fallback=size-fallbackOffset;
        int configured=plugin.guiConfig().getInt("marketplace."+key,fallback);
        return configured>=size-9&&configured<size ? configured : fallback;
    }
    private void msgNoPermission(Player p){p.sendMessage("You do not have permission to use that menu.");}
    public void openHistory(Player p) {
        try {
            var records=plugin.repository().historyFor(p.getUniqueId(),45);
            Inventory inv=Bukkit.createInventory(new HistoryHolder(),54,mm.deserialize("<dark_gray>Orders History</dark_gray>"));
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
            Inventory inv=Bukkit.createInventory(new ManageHolder(p.getUniqueId()),size,mm.deserialize("<dark_gray>My Buy Orders</dark_gray>"));
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

    public void beginCreate(Player p){ openMaterialPicker(p,0); }
    private void openMaterialPicker(Player p,int requestedPage){
        List<Material> materials=Arrays.stream(Material.values()).filter(Material::isItem).filter(m->!m.isAir()).sorted(Comparator.comparing(Material::name)).toList();
        int perPage=45, maxPage=Math.max(0,(materials.size()-1)/perPage), page=Math.max(0,Math.min(requestedPage,maxPage));
        Inventory inv=Bukkit.createInventory(new CreateHolder("picker",page,null,16,1.0),54,mm.deserialize("<dark_gray>Select Item</dark_gray>"));
        int start=page*perPage,end=Math.min(materials.size(),start+perPage);
        for(int i=start;i<end;i++){Material material=materials.get(i);ItemStack icon=new ItemStack(material);ItemMeta meta=icon.getItemMeta();meta.displayName(mm.deserialize("<light_purple>"+material.name().replace('_',' ')+"</light_purple>"));meta.lore(List.of(mm.deserialize("<gray>Click to select this item")));icon.setItemMeta(meta);inv.setItem(i-start,icon);}
        if(page>0)inv.setItem(45,named(Material.ARROW,"<yellow>Previous Items"));
        inv.setItem(49,named(Material.BARRIER,"<red>Cancel"));
        if(page<maxPage)inv.setItem(53,named(Material.ARROW,"<yellow>Next Items"));
        p.openInventory(inv);
    }
    private void openCreateSettings(Player p,Material material,int quantity,double price){
        Inventory inv=Bukkit.createInventory(new CreateHolder("settings",0,material,quantity,price),27,mm.deserialize("<dark_gray>Configure Order</dark_gray>"));
        inv.setItem(4,named(material,"<light_purple>"+material.name().replace('_',' ')+"</light_purple>"));
        inv.setItem(10,named(Material.REDSTONE,"<red>Decrease Quantity"));
        inv.setItem(11,named(Material.LIME_DYE,"<green>Increase Quantity"));
        inv.setItem(13,named(Material.PAPER,"<yellow>Quantity: "+quantity+"<gray> (click +/-)"));
        inv.setItem(15,named(Material.GOLD_NUGGET,"<red>Lower Unit Price"));
        inv.setItem(16,named(Material.GOLD_INGOT,"<green>Raise Unit Price"));
        inv.setItem(22,named(Material.EMERALD,"<green>Confirm Order"));
        inv.setItem(26,named(Material.BARRIER,"<red>Cancel"));
        ItemStack priceInfo=named(Material.GOLD_INGOT,"<yellow>Unit price: "+price);
        ItemMeta meta=priceInfo.getItemMeta();meta.lore(List.of(mm.deserialize("<gray>Total escrow: "+(quantity*price)),mm.deserialize("<gray>Use +/- to adjust quantity and price.")));priceInfo.setItemMeta(meta);inv.setItem(14,priceInfo);
        p.openInventory(inv);
    }
    @EventHandler public void onCreateClick(InventoryClickEvent e){
        if(!(e.getWhoClicked() instanceof Player p)||!(e.getView().getTopInventory().getHolder() instanceof CreateHolder holder))return;
        e.setCancelled(true);if(e.getClickedInventory()!=e.getView().getTopInventory())return;
        int slot=e.getRawSlot();
        if(holder.step.equals("picker")){
            if(slot==49){p.closeInventory();return;}
            if(slot==45&&holder.page>0){openMaterialPicker(p,holder.page-1);return;}
            if(slot==53){openMaterialPicker(p,holder.page+1);return;}
            ItemStack selected=e.getCurrentItem();if(selected==null||selected.getType().isAir()||!selected.getType().isItem())return;
            openCreateSettings(p,selected.getType(),16,plugin.getConfig().getDouble("orders.min-unit-price",0.01));return;
        }
        if(slot==26){p.closeInventory();p.sendMessage("Order creation cancelled.");return;}
        int quantity=holder.quantity;double price=holder.price;
        int max=plugin.getConfig().getInt("orders.max-quantity",23040);
        double step=plugin.getConfig().getDouble("orders.gui-price-step",1.0);
        if(slot==10)quantity=Math.max(1,quantity-1);
        else if(slot==11)quantity=Math.min(max,quantity+1);
        else if(slot==15)price=Math.max(plugin.getConfig().getDouble("orders.min-unit-price",0.01),Math.round((price-step)*100.0)/100.0);
        else if(slot==16)price=Math.min(plugin.getConfig().getDouble("orders.max-unit-price",1_000_000_000.0),Math.round((price+step)*100.0)/100.0);
        else if(slot==22){
            try{long id=orders.create(p,holder.material,holder.quantity,holder.price);p.closeInventory();p.sendMessage("Order #"+id+" created; escrow reserved.");}
            catch(Exception ex){p.sendMessage("SuperOrders: "+(ex.getMessage()==null?"Could not create order.":ex.getMessage()));}
            return;
        }else return;
        openCreateSettings(p,holder.material,quantity,price);
    }
    private void beginDelivery(Player p,long id){
        try {
            BuyOrder o=plugin.repository().find(id);
            if(o==null||o.status()!=OrderStatus.ACTIVE||o.expiresAt()<=System.currentTimeMillis()){p.sendMessage("That order is no longer available.");return;}
            if(o.ownerId().equals(p.getUniqueId())){p.sendMessage("You cannot fulfill your own order.");return;}
            openDelivery(p,id,o.material(),o.remainingQuantity(),1);
        } catch(Exception ex){p.sendMessage("Could not load order.");}
    }
    private BuyOrder findOrderQuietly(long id){
        try{return plugin.repository().find(id);}
        catch(Exception ex){plugin.getLogger().warning("Could not read order #"+id+": "+ex.getMessage());return null;}
    }
    private void openDelivery(Player p,long id,Material material,int maxQuantity,int quantity){
        BuyOrder order=findOrderQuietly(id);
        if(order==null||order.status()!=OrderStatus.ACTIVE){p.sendMessage("That order is no longer active or could not be loaded.");return;}
        int safeMax=Math.min(maxQuantity,order.remainingQuantity());
        int safeQuantity=Math.max(1,Math.min(quantity,safeMax));
        Inventory inv=Bukkit.createInventory(new DeliveryHolder(id,material,safeMax,safeQuantity),27,mm.deserialize("<dark_gray>Deliver to Order #"+id+"</dark_gray>"));
        ItemStack info=named(material,"<light_purple>"+material.name().replace('_',' ')+"</light_purple>");
        ItemMeta meta=info.getItemMeta();
        meta.lore(List.of(mm.deserialize("<gray>Requested: <white>"+order.remainingQuantity()),
                mm.deserialize("<gray>Selected: <white>"+safeQuantity),
                mm.deserialize("<gray>Price each: <white>"+order.unitPrice()),
                mm.deserialize("<gray>Estimated payout: <white>"+(order.unitPrice()*safeQuantity)),
                mm.deserialize("<yellow>Items are removed only when delivery begins.")));
        info.setItemMeta(meta);inv.setItem(13,info);
        inv.setItem(10,named(Material.REDSTONE,"<red>Decrease quantity"));
        inv.setItem(11,named(Material.PAPER,"<yellow>Selected: "+safeQuantity));
        inv.setItem(16,named(Material.LIME_DYE,"<green>Increase quantity"));
        inv.setItem(22,named(Material.EMERALD,"<green>Continue"));
        inv.setItem(26,named(Material.BARRIER,"<red>Cancel"));
        p.openInventory(inv);
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
    // Minecraft item names and lore default to italic. Disable it consistently for straight, readable GUI text.
    private Component text(String input) {
        return mm.deserialize(input).decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack named(Material material,String title){ItemStack i=new ItemStack(material);ItemMeta m=i.getItemMeta();m.displayName(mm.deserialize(title));i.setItemMeta(m);return i;}
    private enum MarketSort {
        NEWEST("Newest"), PRICE_LOW("Lowest Price"), PRICE_HIGH("Highest Price");
        private final String label;
        MarketSort(String label){this.label=label;}
    }
    private record InputState(String step,Material material,long orderId,int quantity,double price,long deadline){}
    private static final class MarketHolder implements InventoryHolder{private final int page;private final String query;private final MarketSort sort;private MarketHolder(int page,String query,MarketSort sort){this.page=page;this.query=query;this.sort=sort;}@Override public Inventory getInventory(){return null;}}
    private static final class CreateHolder implements InventoryHolder{private final String step;private final int page;private final Material material;private final int quantity;private final double price;private CreateHolder(String step,int page,Material material,int quantity,double price){this.step=step;this.page=page;this.material=material;this.quantity=quantity;this.price=price;}@Override public Inventory getInventory(){return null;}}
    private static final class HistoryHolder implements InventoryHolder{@Override public Inventory getInventory(){return null;}}
    private static final class ManageHolder implements InventoryHolder{private final UUID ownerId;private ManageHolder(UUID ownerId){this.ownerId=ownerId;}@Override public Inventory getInventory(){return null;}}
    private static final class DeliveryHolder implements InventoryHolder{private final long orderId;private final Material material;private final int maxQuantity;private final int quantity;private DeliveryHolder(long orderId,Material material,int maxQuantity,int quantity){this.orderId=orderId;this.material=material;this.maxQuantity=maxQuantity;this.quantity=quantity;}@Override public Inventory getInventory(){return null;}}
}