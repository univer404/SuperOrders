package com.superorders.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.entity.Player;

/** Prevents marketplace display items from being moved or taken. */
public final class MarketInventoryListener implements Listener {
    @EventHandler public void onClick(InventoryClickEvent event) {
        if (isMarket(event.getView().getTopInventory())) event.setCancelled(true);
    }
    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (isMarket(event.getView().getTopInventory())) event.setCancelled(true);
    }
    private boolean isMarket(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        return holder instanceof Player && inventory.getSize() >= 9;
    }
}
