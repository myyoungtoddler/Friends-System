package dev.friendssystem.paper;

import dev.friendssystem.common.FriendDatabase;
import dev.friendssystem.common.FriendDatabase.FriendRecord;
import dev.friendssystem.common.FriendDatabase.PlayerRecord;
import dev.friendssystem.common.FriendDatabase.RequestResult;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class FriendCommands implements CommandExecutor, TabCompleter {
    private final FriendsPaperPlugin plugin;
    private final FriendDatabase database;

    public FriendCommands(FriendsPaperPlugin plugin, FriendDatabase database) {
        this.plugin = plugin;
        this.database = database;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }
        if (command.getName().equalsIgnoreCase("friendlist")) {
            openFriendList(player);
            return true;
        }
        if (args.length == 1) {
            sendRequest(player, args[0]);
            return true;
        }
        if (args.length == 2) {
            String subcommand = args[0].toLowerCase(Locale.ROOT);
            switch (subcommand) {
                case "accept" -> handleRequest(player, args[1], true);
                case "deny" -> handleRequest(player, args[1], false);
                case "remove" -> removeFriend(player, args[1]);
                case "invite" -> inviteFriend(player, args[1]);
                case "join" -> joinFriend(player, args[1]);
                default -> sendHelp(player);
            }
            return true;
        }
        sendHelp(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("friend") && args.length == 1) {
            return List.of("accept", "deny", "remove", "invite", "join").stream()
                    .filter(option -> option.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (command.getName().equalsIgnoreCase("friend") && args.length == 2 && sender instanceof Player player) {
            return database.listFriends(player.getUniqueId()).stream().map(FriendRecord::name)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }

    public void openFriendList(Player player) {
        List<FriendRecord> friends = database.listFriends(player.getUniqueId());
        FriendListHolder holder = new FriendListHolder();
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text("Friends", NamedTextColor.AQUA));
        holder.setInventory(inventory);
        for (int slot = 0; slot < Math.min(friends.size(), 54); slot++) {
            FriendRecord friend = friends.get(slot);
            ItemStack item = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) item.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(friend.uuid()));
            meta.displayName(Component.text(friend.name(), friend.online() ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            List<Component> lore = new ArrayList<>();
            lore.add(friend.online()
                    ? Component.text("Online: " + friend.serverName(), NamedTextColor.GREEN)
                    : Component.text("Offline", NamedTextColor.DARK_GRAY));
            lore.add(Component.text("Click to send a message", NamedTextColor.YELLOW));
            meta.lore(lore);
            item.setItemMeta(meta);
            inventory.setItem(slot, item);
            holder.add(slot, friend);
        }
        if (friends.isEmpty()) {
            inventory.setItem(22, new ItemStack(Material.BARRIER));
            player.sendMessage(Component.text("You do not have any friends yet.", NamedTextColor.GRAY));
        }
        player.openInventory(inventory);
    }

    private void sendRequest(Player player, String targetName) {
        Optional<PlayerRecord> target = database.findPlayer(targetName);
        if (target.isEmpty()) {
            player.sendMessage(Component.text("That player has not joined this network before.", NamedTextColor.RED));
            return;
        }
        RequestResult result = database.requestFriend(player.getUniqueId(), target.get().uuid());
        switch (result) {
            case SENT -> {
                database.enqueueEvent(target.get().uuid(), player.getUniqueId(), "REQUEST", null);
                player.sendMessage(Component.text("Friend request sent to " + target.get().name() + ".", NamedTextColor.GREEN));
            }
            case ACCEPTED -> {
                database.enqueueEvent(target.get().uuid(), player.getUniqueId(), "ACCEPTED", null);
                player.sendMessage(Component.text("You are now friends with " + target.get().name() + ".", NamedTextColor.GREEN));
            }
            case ALREADY_PENDING -> player.sendMessage(Component.text("You already sent them a friend request.", NamedTextColor.YELLOW));
            case ALREADY_FRIENDS -> player.sendMessage(Component.text("You are already friends.", NamedTextColor.YELLOW));
            case SELF -> player.sendMessage(Component.text("You cannot add yourself.", NamedTextColor.RED));
        }
    }

    private void handleRequest(Player player, String requesterName, boolean accept) {
        Optional<PlayerRecord> requester = database.findPlayer(requesterName);
        if (requester.isEmpty()) {
            player.sendMessage(Component.text("No friend request found from that player.", NamedTextColor.RED));
            return;
        }
        boolean updated = accept
                ? database.acceptRequest(player.getUniqueId(), requester.get().uuid())
                : database.denyRequest(player.getUniqueId(), requester.get().uuid());
        player.sendMessage(updated
                ? Component.text(accept ? "You are now friends with " + requester.get().name() + "." : "Friend request declined.", NamedTextColor.GREEN)
                : Component.text("No friend request found from that player.", NamedTextColor.RED));
        if (updated && accept) {
            database.enqueueEvent(requester.get().uuid(), player.getUniqueId(), "ACCEPTED", null);
        }
    }

    private void removeFriend(Player player, String targetName) {
        Optional<PlayerRecord> target = database.findPlayer(targetName);
        if (target.isPresent() && database.removeFriend(player.getUniqueId(), target.get().uuid())) {
            player.sendMessage(Component.text("Removed " + target.get().name() + " from your friends.", NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text("That player is not your friend.", NamedTextColor.RED));
        }
    }

    private void inviteFriend(Player player, String targetName) {
        Optional<PlayerRecord> target = database.findPlayer(targetName);
        if (target.isEmpty() || !database.createInvite(player.getUniqueId(), target.get().uuid(), player.getServer().getName())) {
            player.sendMessage(Component.text("You can only invite a friend to your current server.", NamedTextColor.RED));
            return;
        }
        player.sendMessage(Component.text("Invite sent to " + target.get().name() + ".", NamedTextColor.GREEN));
    }

    private void joinFriend(Player player, String inviterName) {
        Optional<PlayerRecord> inviter = database.findPlayer(inviterName);
        if (inviter.isEmpty()) {
            player.sendMessage(Component.text("No active invite from that player.", NamedTextColor.RED));
            return;
        }
        database.enqueueEvent(player.getUniqueId(), inviter.get().uuid(), "JOIN", inviter.get().uuid().toString());
        player.sendMessage(Component.text("Connecting to your friend's server...", NamedTextColor.GREEN));
    }

    private void sendHelp(Player player) {
        player.sendMessage(Component.text("/friend <player> | accept <player> | deny <player>", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("/friend remove <player> | invite <player> | join <player>", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("/friendlist", NamedTextColor.YELLOW));
    }

    static final class FriendListHolder implements InventoryHolder {
        private final java.util.Map<Integer, FriendRecord> friendsBySlot = new java.util.HashMap<>();
        private Inventory inventory;

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        void add(int slot, FriendRecord friend) {
            friendsBySlot.put(slot, friend);
        }

        FriendRecord friendAt(int slot) {
            return friendsBySlot.get(slot);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}