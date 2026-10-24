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
        if (slot == guiSlot("delivery.cancel-slot",26,inv.getSize())) {
            p.closeInventory();
            return;
        }
        if(slot==guiSlot("delivery.decrease-slot",10,inv.getSize())){openDelivery(p,holder.orderId,holder.material,holder.maxQuantity,Math.max(1,holder.quantity-1));return;}
        if(slot==guiSlot("delivery.increase-slot",16,inv.getSize())){openDelivery(p,holder.orderId,holder.material,holder.maxQuantity,Math.min(holder.maxQuantity,holder.quantity+1));return;}
        if(slot==guiSlot("delivery.continue-slot",22,inv.getSize())){
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
            int size=guiSize("marketplace",54);
            size=((size+8)/9)*9;
            int contentSlots=size-9;
            int maxPage=Math.max(0,(list.size()-1)/contentSlots);
            int page=Math.max(0,Math.min(requestedPage,maxPage));
            Inventory inv=Bukkit.createInventory(new MarketHolder(page,normalized,sort),size,
                    text(guiString("marketplace.title","<dark_purple>SuperOrders</dark_purple>")));
            // Keep the marketplace visually organized: a compact item grid above a dedicated control bar.
            for(int i=contentSlots;i<size;i++) inv.setItem(i,configuredItem("marketplace.filler",Material.GRAY_STAINED_GLASS_PANE,"<dark_gray> ",Map.of()));
            int start=page*contentSlots;
            int end=Math.min(list.size(),start+contentSlots);
            int slot=0;
            for(int index=start;index<end;index++){
                BuyOrder o=list.get(index);
                Map<String,String> values=Map.of("%material%",o.material().name().replace('_',' '),"%owner%",o.ownerName(),
                        "%remaining%",String.valueOf(o.remainingQuantity()),"%unit_price%",String.valueOf(o.unitPrice()),
                        "%order_value%",String.valueOf(o.remainingValue()),"%order_id%",String.valueOf(o.id()));
                ItemStack configured = configuredItem("marketplace.order-item",o.material(),"<aqua><bold>%material%</bold></aqua>",values);
                inv.setItem(slot++,configured);
            }
            if(list.isEmpty())inv.setItem(size/2,configuredItem("marketplace.empty",Material.PAPER,normalized.isEmpty()?"<gray>No active orders":"<gray>No orders match your search",Map.of()));
            if(page>0)inv.setItem(configuredSlot("previous-slot",size,9),configuredItem("marketplace.buttons.previous",Material.ARROW,"<yellow>Previous Page",Map.of()));
            inv.setItem(configuredSlot("search-slot",size,8),configuredItem("marketplace.buttons.search",Material.COMPASS,"<aqua>Search",Map.of()));
            inv.setItem(configuredSlot("sort-slot",size,7),configuredItem("marketplace.buttons.sort",Material.HOPPER,"<aqua>Sort: %sort%",Map.of("%sort%",sort.label)));
            inv.setItem(configuredSlot("create-slot",size,5),configuredItem("marketplace.buttons.create",Material.EMERALD,"<green>Create Order",Map.of()));
            inv.setItem(configuredSlot("mine-slot",size,4),configuredItem("marketplace.buttons.mine",Material.BOOK,"<yellow>My Orders",Map.of()));
            inv.setItem(configuredSlot("history-slot",size,3),configuredItem("marketplace.buttons.history",Material.WRITABLE_BOOK,"<yellow>History",Map.of()));
            if(page<maxPage)inv.setItem(configuredSlot("next-slot",size,1),configuredItem("marketplace.buttons.next",Material.ARROW,"<yellow>Next Page",Map.of()));
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
            Inventory inv=Bukkit.createInventory(new HistoryHolder(),guiSize("history",54),text(guiString("history.title","<dark_gray>Orders History</dark_gray>")));
            int slot=0;
            for(var record:records) {
                if (slot >= inv.getSize()) break;
                Map<String,String> values=Map.of("%state%",record.state(),"%order_id%",String.valueOf(record.orderId()),
                        "%quantity%",String.valueOf(record.quantity()),"%amount%",String.valueOf(record.amount()),"%actor%",String.valueOf(record.actorUuid()),
                        "%time%",String.valueOf(java.time.Instant.ofEpochMilli(record.createdAt())),"%transaction_id%",record.transactionId(),"%details%",record.details());
                ItemStack icon=configuredItem("history.item",Material.PAPER,"<light_purple>%state%</light_purple>",values);
                inv.setItem(slot++,icon);
            }
            if(records.isEmpty())inv.setItem(guiSlot("history.empty.slot",22,inv.getSize()),configuredItem("history.empty",Material.PAPER,"<gray>No transactions recorded",Map.of()));
            p.openInventory(inv);
        } catch(Exception ex){plugin.getLogger().warning("Could not load transaction history: "+ex.getMessage());p.sendMessage("Could not load transaction history.");}
    }

    public void openMine(Player p) {
        try {
            List<BuyOrder> list=plugin.repository().ordersFor(p.getUniqueId());
            int size=guiSize("mine",54);
            int configuredContentSlots=Math.max(1,Math.min(guiInt("mine.content-slots",45),size-9));
            Inventory inv=Bukkit.createInventory(new ManageHolder(p.getUniqueId()),size,text(guiString("mine.title","<dark_gray>My Buy Orders</dark_gray>")));
            int slot=0;
            for(BuyOrder o:list) {
                if(slot>=configuredContentSlots||slot>=size-9)break;
                Map<String,String> values=Map.of("%order_id%",String.valueOf(o.id()),"%material%",o.material().name().replace('_',' '),
                        "%status%",String.valueOf(o.status()),"%remaining%",String.valueOf(o.remainingQuantity()),"%original%",String.valueOf(o.originalQuantity()),
                        "%unit_price%",String.valueOf(o.unitPrice()),"%escrow%",String.valueOf(o.remainingEscrow()),
                        "%action_lore%",o.status()==OrderStatus.ACTIVE?"<red>Click to cancel and refund remaining escrow":"<gray>This order is not active");
                ItemStack icon=configuredItem("mine.item",o.material(),"<light_purple>Order #%order_id% - %material%</light_purple>",values);
                inv.setItem(slot++,icon);
            }
            if(list.isEmpty())inv.setItem(guiSlot("mine.empty.slot",22,inv.getSize()),configuredItem("mine.empty",Material.PAPER,"<gray>You have not created any orders yet",Map.of()));
            p.openInventory(inv);
        } catch(Exception ex){plugin.getLogger().warning("Could not load player orders: "+ex.getMessage());p.sendMessage("Could not load your orders.");}
    }

    public void beginCreate(Player p){ openMaterialPicker(p,0); }
    private void openMaterialPicker(Player p,int requestedPage){
        List<Material> materials=Arrays.stream(Material.values()).filter(Material::isItem).filter(m->!m.isAir()).sorted(Comparator.comparing(Material::name)).toList();
        int perPage=Math.max(1,Math.min(guiInt("picker.items-per-page",45),guiSize("picker",54)-9)), maxPage=Math.max(0,(materials.size()-1)/perPage), page=Math.max(0,Math.min(requestedPage,maxPage));
        Inventory inv=Bukkit.createInventory(new CreateHolder("picker",page,null,16,1.0),guiSize("picker",54),text(guiString("picker.title","<dark_gray>Select Item</dark_gray>")));
        int start=page*perPage,end=Math.min(materials.size(),start+perPage);
        for(int i=start;i<end;i++){Material material=materials.get(i);ItemStack icon=configuredItem("picker.item",material,"<light_purple>%material%</light_purple>",Map.of("%material%",material.name().replace('_',' ')));inv.setItem(i-start,icon);}
        int previous=guiSlot("picker.previous-slot",45,e.getView().getTopInventory().getSize()), cancel=guiSlot("picker.cancel-slot",49,e.getView().getTopInventory().getSize()), next=guiSlot("picker.next-slot",53,e.getView().getTopInventory().getSize());
        if(page>0&&previous>=0&&previous<inv.getSize())inv.setItem(previous,configuredItem("picker.previous",Material.ARROW,"<yellow>Previous Items",Map.of()));
        if(cancel>=0&&cancel<inv.getSize())inv.setItem(cancel,configuredItem("picker.cancel",Material.BARRIER,"<red>Cancel",Map.of()));
        if(page<maxPage&&next>=0&&next<inv.getSize())inv.setItem(next,configuredItem("picker.next",Material.ARROW,"<yellow>Next Items",Map.of()));
        p.openInventory(inv);
    }
    private void openCreateSettings(Player p,Material material,int quantity,double price){
        Inventory inv=Bukkit.createInventory(new CreateHolder("settings",0,material,quantity,price),guiSize("create",27),text(guiString("create.title","<dark_gray>Configure Order</dark_gray>")));
        inv.setItem(guiSlot("create.item-slot",4,inv.getSize()),configuredItem("create.item",material,"<light_purple>%material%</light_purple>",Map.of("%material%",material.name().replace('_',' '))));
        inv.setItem(guiSlot("create.decrease-quantity-slot",10,inv.getSize()),configuredItem("create.decrease-quantity",Material.REDSTONE,"<red>Decrease Quantity",Map.of()));
        inv.setItem(guiSlot("create.increase-quantity-slot",11,inv.getSize()),configuredItem("create.increase-quantity",Material.LIME_DYE,"<green>Increase Quantity",Map.of()));
        inv.setItem(guiSlot("create.quantity-slot",13,inv.getSize()),configuredItem("create.quantity",Material.PAPER,"<yellow>Quantity: %quantity%<gray> (click +/-)",Map.of("%quantity%",String.valueOf(quantity))));
        inv.setItem(guiSlot("create.decrease-price-slot",15,inv.getSize()),configuredItem("create.decrease-price",Material.GOLD_NUGGET,"<red>Lower Unit Price",Map.of()));
        inv.setItem(guiSlot("create.increase-price-slot",16,inv.getSize()),configuredItem("create.increase-price",Material.GOLD_INGOT,"<green>Raise Unit Price",Map.of()));
        inv.setItem(guiSlot("create.confirm-slot",22,inv.getSize()),configuredItem("create.confirm",Material.EMERALD,"<green>Confirm Order",Map.of()));
        inv.setItem(guiSlot("create.cancel-slot",26,inv.getSize()),configuredItem("create.cancel",Material.BARRIER,"<red>Cancel",Map.of()));
        ItemStack priceInfo=configuredItem("create.price-info",Material.GOLD_INGOT,"<yellow>Unit price: %price%",Map.of("%price%",String.valueOf(price),"%total%",String.valueOf(quantity*price)));inv.setItem(guiSlot("create.price-info-slot",14,inv.getSize()),priceInfo);
        p.openInventory(inv);
    }
    @EventHandler public void onCreateClick(InventoryClickEvent e){
        if(!(e.getWhoClicked() instanceof Player p)||!(e.getView().getTopInventory().getHolder() instanceof CreateHolder holder))return;
        e.setCancelled(true);if(e.getClickedInventory()!=e.getView().getTopInventory())return;
        int slot=e.getRawSlot();
        if(holder.step.equals("picker")){
            if(slot==guiInt("picker.cancel-slot",49)){p.closeInventory();return;}
            if(slot==guiInt("picker.previous-slot",45)&&holder.page>0){openMaterialPicker(p,holder.page-1);return;}
            if(slot==guiInt("picker.next-slot",53)){openMaterialPicker(p,holder.page+1);return;}
            ItemStack selected=e.getCurrentItem();if(selected==null||selected.getType().isAir()||!selected.getType().isItem())return;
            openCreateSettings(p,selected.getType(),16,plugin.getConfig().getDouble("orders.min-unit-price",0.01));return;
        }
        if(slot==guiSlot("create.cancel-slot",26,e.getView().getTopInventory().getSize())){p.closeInventory();p.sendMessage("Order creation cancelled.");return;}
        int quantity=holder.quantity;double price=holder.price;
        int max=plugin.getConfig().getInt("orders.max-quantity",23040);
        double step=plugin.getConfig().getDouble("orders.gui-price-step",1.0);
        if(slot==guiSlot("create.decrease-quantity-slot",10,e.getView().getTopInventory().getSize()))quantity=Math.max(1,quantity-1);
        else if(slot==guiSlot("create.increase-quantity-slot",11,e.getView().getTopInventory().getSize()))quantity=Math.min(max,quantity+1);
        else if(slot==guiSlot("create.decrease-price-slot",15,e.getView().getTopInventory().getSize()))price=Math.max(plugin.getConfig().getDouble("orders.min-unit-price",0.01),Math.round((price-step)*100.0)/100.0);
        else if(slot==guiSlot("create.increase-price-slot",16,e.getView().getTopInventory().getSize()))price=Math.min(plugin.getConfig().getDouble("orders.max-unit-price",1_000_000_000.0),Math.round((price+step)*100.0)/100.0);
        else if(slot==guiSlot("create.confirm-slot",22,e.getView().getTopInventory().getSize())){
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
        Inventory inv=Bukkit.createInventory(new DeliveryHolder(id,material,safeMax,safeQuantity),guiSize("delivery",27),text(guiString("delivery.title","<dark_gray>Deliver to Order #%order_id%</dark_gray>").replace("%order_id%",String.valueOf(id))));
        Map<String,String> values=Map.of("%material%",material.name().replace('_',' '),"%requested%",String.valueOf(order.remainingQuantity()),"%quantity%",String.valueOf(safeQuantity),"%unit_price%",String.valueOf(order.unitPrice()),"%payout%",String.valueOf(order.unitPrice()*safeQuantity));
        inv.setItem(guiSlot("delivery.info-slot",13,inv.getSize()),configuredItem("delivery.info",material,"<light_purple>%material%</light_purple>",values));
        inv.setItem(guiSlot("delivery.decrease-slot",10,e.getView().getTopInventory().getSize()),configuredItem("delivery.decrease",Material.REDSTONE,"<red>Decrease quantity",Map.of()));
        inv.setItem(guiSlot("delivery.quantity-slot",11,inv.getSize()),configuredItem("delivery.quantity",Material.PAPER,"<yellow>Selected: %quantity%",values));
        inv.setItem(guiSlot("delivery.increase-slot",16,e.getView().getTopInventory().getSize()),configuredItem("delivery.increase",Material.LIME_DYE,"<green>Increase quantity",Map.of()));
        inv.setItem(guiSlot("delivery.continue-slot",22,e.getView().getTopInventory().getSize()),configuredItem("delivery.continue",Material.EMERALD,"<green>Continue",Map.of()));
        inv.setItem(guiSlot("delivery.cancel-slot",26,e.getView().getTopInventory().getSize()),configuredItem("delivery.cancel",Material.BARRIER,"<red>Cancel",Map.of()));
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

    private String guiString(String path,String fallback){return plugin.guiConfig().getString(path,fallback);}
    private int guiInt(String path,int fallback){return plugin.guiConfig().getInt(path,fallback);}
    private int guiSize(String path,int fallback){int size=guiInt(path+".size",fallback);return size>=9&&size<=54&&size%9==0?size:fallback;}
    private int guiSlot(String path,int fallback,int size){int slot=guiInt(path,fallback);return slot>=0&&slot<size?slot:fallback>=0&&fallback<size?fallback:Math.max(0,size-1);}
    private Material guiMaterial(String path,Material fallback){Material material=Material.matchMaterial(guiString(path,fallback.name()));return material==null||!material.isItem()?fallback:material;}
    private ItemStack configuredItem(String path,Material fallback,String fallbackName,Map<String,String> replacements){
        Material material=guiMaterial(path+".material",fallback);ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();
        if(meta==null)return item;String name=guiString(path+".name",fallbackName);for(var entry:replacements.entrySet())name=name.replace(entry.getKey(),entry.getValue());meta.displayName(text(name));
        List<String> lore=plugin.guiConfig().getStringList(path+".lore");if(!lore.isEmpty()){List<Component> lines=new ArrayList<>();for(String line:lore){for(var entry:replacements.entrySet())line=line.replace(entry.getKey(),entry.getValue());lines.add(text(line));}meta.lore(lines);}
        item.setItemMeta(meta);return item;
    }
    private ItemStack named(Material material,String title){ItemStack i=new ItemStack(material);ItemMeta m=i.getItemMeta();m.displayName(text(title));i.setItemMeta(m);return i;}
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