package dev.friendssystem.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import dev.friendssystem.common.FriendDatabase;
import dev.friendssystem.common.FriendDatabase.FriendEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

@Plugin(id = "friends-system", name = "FriendsSystem", version = "1.0.0")
public final class FriendsVelocityPlugin {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private FriendDatabase database;

    @Inject
    public FriendsVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        try {
            Files.createDirectories(dataDirectory);
            Path configFile = dataDirectory.resolve("friends.properties");
            if (Files.notExists(configFile)) {
                try (InputStream input = getClass().getResourceAsStream("/friends.properties")) {
                    if (input == null) {
                        throw new IOException("Default friends.properties is missing");
                    }
                    Files.copy(input, configFile);
                }
            }
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(configFile)) {
                properties.load(input);
            }
            database = new FriendDatabase(
                    properties.getProperty("database.host", "127.0.0.1"),
                    Integer.parseInt(properties.getProperty("database.port", "3306")),
                    properties.getProperty("database.name", "friends"),
                    properties.getProperty("database.username", "friends"),
                    properties.getProperty("database.password", "change-me"));
            database.initialize();
            database.clearPresence();
            proxy.getScheduler().buildTask(this, this::pollEvents).repeat(Duration.ofMillis(500)).schedule();
            logger.info("FriendsSystem connected to MariaDB.");
        } catch (Exception exception) {
            logger.error("FriendsSystem could not start; check friends.properties and MariaDB availability.", exception);
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        if (database == null) {
            return;
        }
        Player player = event.getPlayer();
        String serverName = event.getServer().getServerInfo().getName();
        proxy.getScheduler().buildTask(this, () -> database.updatePresence(player.getUniqueId(), player.getUsername(), serverName)).schedule();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (database == null) {
            return;
        }
        proxy.getScheduler().buildTask(this, () -> database.setOffline(event.getPlayer().getUniqueId())).schedule();
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (database != null) {
            for (Player player : proxy.getAllPlayers()) {
                database.setOffline(player.getUniqueId());
            }
            database.close();
            database = null;
        }
    }

    private void pollEvents() {
        if (database == null) {
            return;
        }
        try {
            List<FriendEvent> events = database.pendingEvents(100);
            for (FriendEvent event : events) {
                Player recipient = proxy.getPlayer(event.recipient()).orElse(null);
                if (recipient == null) {
                    continue;
                }
                processEvent(recipient, event);
            }
        } catch (RuntimeException exception) {
            logger.warn("Could not poll friend events: {}", exception.getMessage());
        }
    }

    private void processEvent(Player recipient, FriendEvent event) {
        String senderName = database.findPlayerName(event.sender()).orElse("Unknown player");
        switch (event.type()) {
            case "MESSAGE" -> recipient.sendMessage(Component.text("[Friend] " + senderName + ": " + event.payload(), NamedTextColor.AQUA));
            case "REQUEST" -> recipient.sendMessage(Component.text(senderName + " sent you a friend request. Use /friend accept " + senderName + ".", NamedTextColor.YELLOW));
            case "ACCEPTED" -> recipient.sendMessage(Component.text(senderName + " accepted your friend request.", NamedTextColor.GREEN));
            case "INVITE" -> recipient.sendMessage(Component.text(senderName + " invited you to " + event.payload()
                    + ". Use /friend join " + senderName + " within 2 minutes.", NamedTextColor.GREEN));
            case "JOIN" -> joinInviterServer(recipient, event, senderName);
            default -> logger.warn("Ignoring unknown friend event type {}", event.type());
        }
        database.markEventDelivered(event.id());
    }

    private void joinInviterServer(Player recipient, FriendEvent event, String inviterName) {
        if (!database.isFriend(recipient.getUniqueId(), event.sender())) {
            recipient.sendMessage(Component.text("You are not friends with that player.", NamedTextColor.RED));
            return;
        }
        String serverName = database.consumeInvite(recipient.getUniqueId(), event.sender()).orElse(null);
        if (serverName == null) {
            recipient.sendMessage(Component.text("That invite has expired or does not exist.", NamedTextColor.RED));
            return;
        }
        RegisteredServer server = proxy.getServer(serverName).orElse(null);
        if (server == null) {
            recipient.sendMessage(Component.text("That server is not available right now.", NamedTextColor.RED));
            return;
        }
        recipient.createConnectionRequest(server).connect().thenAccept(result -> {
            if (!result.isSuccessful()) {
                recipient.sendMessage(Component.text("Could not connect you to " + inviterName + "'s server.", NamedTextColor.RED));
            }
        });
    }
}