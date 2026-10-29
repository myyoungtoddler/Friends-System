package dev.friendssystem.paper;

import dev.friendssystem.common.FriendDatabase;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class FriendsPaperPlugin extends JavaPlugin {
    private FriendDatabase database;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        var settings = getConfig().getConfigurationSection("database");
        try {
            database = new FriendDatabase(
                    settings.getString("host", "127.0.0.1"),
                    settings.getInt("port", 3306),
                    settings.getString("name", "friends"),
                    settings.getString("username", "friends"),
                    settings.getString("password", "change-me"));
            database.initialize();
        } catch (RuntimeException exception) {
            getLogger().severe("Could not connect to MariaDB: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        FriendCommands commands = new FriendCommands(this, database);
        PluginCommand friend = getCommand("friend");
        friend.setExecutor(commands);
        friend.setTabCompleter(commands);
        getCommand("friendlist").setExecutor(commands);
        getServer().getPluginManager().registerEvents(new FriendListListener(this, database), this);
    }

    @Override
    public void onDisable() {
        if (database != null) {
            database.close();
        }
    }

    public FriendDatabase database() {
        return database;
    }
}