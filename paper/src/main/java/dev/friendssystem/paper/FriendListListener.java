package dev.friendssystem.paper;

import dev.friendssystem.common.FriendDatabase;
import dev.friendssystem.common.FriendDatabase.FriendRecord;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FriendListListener implements Listener {
    private final FriendsPaperPlugin plugin;
    private final FriendDatabase database;
    private final Map<UUID, UUID> pendingMessageTargets = new ConcurrentHashMap<>();

    public FriendListListener(FriendsPaperPlugin plugin, FriendDatabase database) {
        this.plugin = plugin;
        this.database = database;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof FriendCommands.FriendListHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        FriendRecord friend = holder.friendAt(event.getSlot());
        if (friend == null) {
            return;
        }
        pendingMessageTargets.put(player.getUniqueId(), friend.uuid());
        player.closeInventory();
        player.sendMessage(Component.text("Type your message to " + friend.name() + " in chat.", NamedTextColor.YELLOW));
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        UUID targetId = pendingMessageTargets.remove(event.getPlayer().getUniqueId());
        if (targetId == null) {
            return;
        }
        event.setCancelled(true);
        Player sender = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).strip();
        if (message.isEmpty()) {
            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Component.text("Message cancelled.", NamedTextColor.RED)));
            return;
        }
        try {
            if (!database.isFriend(sender.getUniqueId(), targetId)) {
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Component.text("You are no longer friends.", NamedTextColor.RED)));
                return;
            }
            database.enqueueEvent(targetId, sender.getUniqueId(), "MESSAGE", message);
            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Component.text("Message sent.", NamedTextColor.GREEN)));
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not queue a friend message: " + exception.getMessage());
            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Component.text("Your message could not be sent.", NamedTextColor.RED)));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pendingMessageTargets.remove(event.getPlayer().getUniqueId());
    }
}