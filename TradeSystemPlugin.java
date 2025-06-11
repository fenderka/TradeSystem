package tradesystem;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class TradeSystemPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, UUID> pendingRequests = new HashMap<>();
    private final Map<UUID, TradeSession> activeTrades = new HashMap<>();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("trade") != null) {
            getCommand("trade").setExecutor(this::onCommand);
        }
        getLogger().info("[TradeSystem] Плагин активирован");
    }

    @Override
    public void onDisable() {
        getLogger().info("[TradeSystem] Плагин деактивирован");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sendMessage(sender, ChatColor.RED, "Только игрок может использовать эту команду.");
            return true;
        }

        if (args.length == 0) {
            sendMessage(player, ChatColor.RED, "Использование: /trade <ник> | yes | cancel");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "yes" -> handleAcceptTrade(player);
            case "cancel" -> handleCancelTrade(player);
            default -> handleTradeRequest(player, args[0]);
        }

        return true;
    }

    private void handleAcceptTrade(Player player) {
        UUID requesterId = pendingRequests.remove(player.getUniqueId());
        if (requesterId == null) {
            sendMessage(player, ChatColor.RED, "У вас нет запросов на обмен.");
            return;
        }

        Player requester = Bukkit.getPlayer(requesterId);
        if (requester == null || !requester.isOnline()) {
            sendMessage(player, ChatColor.RED, "Игрок вышел.");
            return;
        }

        if (isInTrade(player.getUniqueId()) || isInTrade(requester.getUniqueId())) {
            sendMessage(player, ChatColor.RED, "Вы или другой игрок уже участвуете в обмене.");
            return;
        }

        TradeSession session = new TradeSession(requester, player);
        activeTrades.put(requester.getUniqueId(), session);
        activeTrades.put(player.getUniqueId(), session);
        session.open();
    }

    private void handleCancelTrade(Player player) {
        TradeSession session = activeTrades.get(player.getUniqueId());
        if (session != null) {
            session.cancelTrade();
        } else {
            sendMessage(player, ChatColor.RED, "У вас нет активного обмена.");
        }
    }

    private void handleTradeRequest(Player player, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !target.isOnline()) {
            sendMessage(player, ChatColor.RED, "Игрок не найден или не в сети.");
            return;
        }

        if (player.equals(target)) {
            sendMessage(player, ChatColor.RED, "Нельзя обмениваться с самим собой.");
            return;
        }

        if (isInTrade(player.getUniqueId()) || isInTrade(target.getUniqueId())) {
            sendMessage(player, ChatColor.RED, "Вы или этот игрок уже в обмене.");
            return;
        }

        pendingRequests.put(target.getUniqueId(), player.getUniqueId());
        sendMessage(target, ChatColor.GREEN, player.getName() + " предлагает обмен. Введите /trade yes чтобы принять.");
        sendMessage(player, ChatColor.YELLOW, "Вы предложили обмен игроку " + target.getName());
    }

    private boolean isInTrade(UUID playerId) {
        return activeTrades.containsKey(playerId);
    }

    private void sendMessage(CommandSender sender, ChatColor color, String msg) {
        sender.sendMessage(color + msg);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        pendingRequests.remove(player.getUniqueId());

        TradeSession session = activeTrades.get(player.getUniqueId());
        if (session != null) session.cancelTrade();
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        TradeSession session = activeTrades.get(player.getUniqueId());
        if (session == null) return;

        Inventory clickedInventory = e.getClickedInventory();
        Inventory topInventory = player.getOpenInventory().getTopInventory();

        if (clickedInventory == null || (!topInventory.equals(session.invP1) && !topInventory.equals(session.invP2)))
            return;

        boolean isClickerP1 = player.equals(session.p1);
        int slot = e.getSlot();

        if (clickedInventory.equals(topInventory)) {
            if (slot >= 0 && slot <= 8) {
                if ((topInventory.equals(session.invP1) && !isClickerP1) || (topInventory.equals(session.invP2) && isClickerP1)) {
                    e.setCancelled(true);
                    return;
                }
            } else if (slot == 45 || slot == 53) {
                e.setCancelled(true);
                if (slot == 45) session.confirm(player);
                else session.cancelTrade();
                return;
            } else {
                e.setCancelled(true);
                return;
            }
        }

        Bukkit.getScheduler().runTaskLater(this, () -> {
            session.updateOfferedItems();
            session.refreshInventories();
        }, 1L);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        TradeSession session = activeTrades.get(player.getUniqueId());
        if (session == null) return;

        Inventory topInventory = player.getOpenInventory().getTopInventory();
        if (!topInventory.equals(session.invP1) && !topInventory.equals(session.invP2)) return;

        for (int slot : e.getRawSlots()) {
            if (slot < topInventory.getSize()) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Player player = (Player) event.getPlayer();
        TradeSession session = activeTrades.get(player.getUniqueId());

        if (session != null && !session.isCompleted && !session.isCancelling) {
            session.cancelTrade();
        }
    }

    class TradeSession {
        final Player p1, p2;
        final Inventory invP1, invP2;
        final ItemStack[] offeredP1 = new ItemStack[9];
        final ItemStack[] offeredP2 = new ItemStack[9];
        final Set<UUID> confirmed = new HashSet<>();
        boolean isCompleted = false;
        boolean isCancelling = false;

        public TradeSession(Player p1, Player p2) {
            this.p1 = p1;
            this.p2 = p2;
            invP1 = Bukkit.createInventory(p1, 54, "Обмен с " + p2.getName());
            invP2 = Bukkit.createInventory(p2, 54, "Обмен с " + p1.getName());
            setupInventory(invP1);
            setupInventory(invP2);
            refreshInventories();
        }

        private void setupInventory(Inventory inv) {
            ItemStack glass = createItem(Material.LIGHT_BLUE_STAINED_GLASS_PANE, ChatColor.GRAY + "Разделитель");
            for (int i : new int[]{9,10,11,12,13,14,15,16,17, 27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44, 46,47,48,49,50,51,52})
                inv.setItem(i, glass);

            inv.setItem(45, createItem(Material.LIME_WOOL, ChatColor.GREEN + "Подтвердить"));
            inv.setItem(53, createItem(Material.RED_WOOL, ChatColor.RED + "Отмена"));

            for (int i = 0; i <= 8; i++) inv.setItem(i, null);
            for (int i = 18; i <= 26; i++) inv.setItem(i, null);
        }

        private ItemStack createItem(Material material, String name) {
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(name);
                item.setItemMeta(meta);
            }
            return item;
        }

        public void open() {
            p1.openInventory(invP1);
            p2.openInventory(invP2);
            sendMessage(p1, ChatColor.YELLOW, "Обмен с " + p2.getName() + " начат.");
            sendMessage(p2, ChatColor.YELLOW, "Обмен с " + p1.getName() + " начат.");
        }

        public void updateOfferedItems() {
            for (int i = 0; i < 9; i++) {
                offeredP1[i] = safeClone(invP1.getItem(i));
                offeredP2[i] = safeClone(invP2.getItem(i));
            }
            confirmed.clear();
        }

        private ItemStack safeClone(ItemStack item) {
            return (item == null || item.getType() == Material.AIR) ? null : item.clone();
        }

        public void confirm(Player player) {
            if (!confirmed.add(player.getUniqueId())) {
                sendMessage(player, ChatColor.YELLOW, "Вы уже подтвердили обмен.");
                return;
            }
            broadcast(ChatColor.GREEN + player.getName() + " подтвердил обмен.");
            if (confirmed.contains(p1.getUniqueId()) && confirmed.contains(p2.getUniqueId())) {
                completeTrade();
            } else {
                refreshInventories();
            }
        }

        private void completeTrade() {
            isCompleted = true;
            p1.closeInventory();
            p2.closeInventory();
            giveItems(p1, offeredP2);
            giveItems(p2, offeredP1);
            broadcast(ChatColor.GREEN + "Обмен успешно завершён.");
            cleanup();
        }

        private void giveItems(Player player, ItemStack[] items) {
            for (ItemStack item : items) {
                if (item == null) continue;
                player.getInventory().addItem(item).values().forEach(leftover ->
                        player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            }
        }

        public void cancelTrade() {
            if (isCompleted || isCancelling) return;
            isCancelling = true;
            returnItems(p1, offeredP1);
            returnItems(p2, offeredP2);
            broadcast(ChatColor.RED + "Обмен отменён.");
            close();
        }

        private void returnItems(Player player, ItemStack[] items) {
            for (ItemStack item : items) {
                if (item == null) continue;
                player.getInventory().addItem(item).values().forEach(leftover ->
                        player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            }
        }

        private void close() {
            if (p1.getOpenInventory().getTopInventory().equals(invP1)) p1.closeInventory();
            if (p2.getOpenInventory().getTopInventory().equals(invP2)) p2.closeInventory();
            cleanup();
        }

        private void cleanup() {
            activeTrades.remove(p1.getUniqueId());
            activeTrades.remove(p2.getUniqueId());
            pendingRequests.remove(p1.getUniqueId());
            pendingRequests.remove(p2.getUniqueId());
        }

        private void broadcast(String message) {
            sendMessage(p1, ChatColor.RESET, message);
            sendMessage(p2, ChatColor.RESET, message);
        }

        private void refreshInventories() {
            for (int i = 0; i < 9; i++) {
                invP1.setItem(i, safeClone(offeredP1[i]));
                invP1.setItem(i + 18, safeClone(offeredP2[i]));
                invP2.setItem(i, safeClone(offeredP2[i]));
                invP2.setItem(i + 18, safeClone(offeredP1[i]));
            }
            updateConfirmButton(invP1, p1);
            updateConfirmButton(invP2, p2);
        }

        private void updateConfirmButton(Inventory inv, Player player) {
            ItemStack button = inv.getItem(45);
            if (button == null) return;
            ItemMeta meta = button.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(confirmed.contains(player.getUniqueId()) ?
                        ChatColor.DARK_GREEN + "Подтверждено" :
                        ChatColor.GREEN + "Подтвердить");
                button.setItemMeta(meta);
            }
        }
    }
}
