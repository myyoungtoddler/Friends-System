package dev.friendssystem.common;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class FriendDatabase implements AutoCloseable {
    private final HikariDataSource dataSource;

    public FriendDatabase(String host, int port, String database, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mariadb://" + host + ":" + port + "/" + database);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("org.mariadb.jdbc.Driver");
        config.setMaximumPoolSize(10);
        config.setPoolName("FriendsSystem");
        dataSource = new HikariDataSource(config);
    }

    public void initialize() {
        String[] statements = {
                "CREATE TABLE IF NOT EXISTS friend_players (player_uuid CHAR(36) PRIMARY KEY, player_name VARCHAR(16) NOT NULL, name_lower VARCHAR(16) NOT NULL UNIQUE)",
                "CREATE TABLE IF NOT EXISTS friend_links (uuid_low CHAR(36) NOT NULL, uuid_high CHAR(36) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (uuid_low, uuid_high))",
                "CREATE TABLE IF NOT EXISTS friend_requests (requester_uuid CHAR(36) NOT NULL, target_uuid CHAR(36) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (requester_uuid, target_uuid))",
                "CREATE TABLE IF NOT EXISTS friend_presence (player_uuid CHAR(36) PRIMARY KEY, server_name VARCHAR(64) NULL, online BOOLEAN NOT NULL DEFAULT FALSE, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)",
                "CREATE TABLE IF NOT EXISTS friend_events (event_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, recipient_uuid CHAR(36) NOT NULL, sender_uuid CHAR(36) NOT NULL, event_type VARCHAR(16) NOT NULL, payload TEXT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, delivered_at TIMESTAMP NULL, INDEX pending_events (delivered_at, event_id))",
                "CREATE TABLE IF NOT EXISTS friend_invites (target_uuid CHAR(36) NOT NULL, inviter_uuid CHAR(36) NOT NULL, server_name VARCHAR(64) NOT NULL, expires_at TIMESTAMP NOT NULL, PRIMARY KEY (target_uuid, inviter_uuid))"
        };
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    public void registerPlayer(UUID playerId, String playerName) {
        String sql = "INSERT INTO friend_players (player_uuid, player_name, name_lower) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE player_name = VALUES(player_name), name_lower = VALUES(name_lower)";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, playerName);
            statement.setString(3, playerName.toLowerCase(java.util.Locale.ROOT));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    public Optional<PlayerRecord> findPlayer(String playerName) {
        String sql = "SELECT player_uuid, player_name FROM friend_players WHERE name_lower = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerName.toLowerCase(java.util.Locale.ROOT));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PlayerRecord(UUID.fromString(result.getString("player_uuid")), result.getString("player_name")));
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    public Optional<String> findPlayerName(UUID playerId) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT player_name FROM friend_players WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString("player_name")) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    public RequestResult requestFriend(UUID requester, UUID target) {
        if (requester.equals(target)) {
            return RequestResult.SELF;
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            if (isFriend(connection, requester, target)) {
                connection.rollback();
                return RequestResult.ALREADY_FRIENDS;
            }
            if (requestExists(connection, target, requester)) {
                addFriendship(connection, requester, target);
                deleteRequests(connection, requester, target);
                connection.commit();
                return RequestResult.ACCEPTED;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT IGNORE INTO friend_requests (requester_uuid, target_uuid) VALUES (?, ?)")) {
                statement.setString(1, requester.toString());
                statement.setString(2, target.toString());
                int inserted = statement.executeUpdate();
                connection.commit();
                return inserted == 0 ? RequestResult.ALREADY_PENDING : RequestResult.SENT;
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    public boolean acceptRequest(UUID target, UUID requester) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            if (!requestExists(connection, requester, target)) {
                connection.rollback();
                return false;
            }
            addFriendship(connection, target, requester);
            deleteRequests(connection, target, requester);
            connection.commit();
            return true;
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    public boolean denyRequest(UUID target, UUID requester) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM friend_requests WHERE requester_uuid = ? AND target_uuid = ?")) {
            statement.setString(1, requester.toString());
            statement.setString(2, target.toString());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    public boolean removeFriend(UUID first, UUID second) {
        String sql = "DELETE FROM friend_links WHERE uuid_low = ? AND uuid_high = ?";
        UUID low = first.toString().compareTo(second.toString()) < 0 ? first : second;
        UUID high = low.equals(first) ? second : first;
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, low.toString());
            statement.setString(2, high.toString());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    public boolean isFriend(UUID first, UUID second) {
        try (Connection connection = dataSource.getConnection()) {
            return isFriend(connection, first, second);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    public List<FriendRecord> listFriends(UUID playerId) {
        String sql = "SELECT p.player_uuid, p.player_name, COALESCE(s.online, FALSE) AS online, s.server_name "
                + "FROM friend_links f JOIN friend_players p ON p.player_uuid = "
                + "CASE WHEN f.uuid_low = ? THEN f.uuid_high ELSE f.uuid_low END "
                + "LEFT JOIN friend_presence s ON s.player_uuid = p.player_uuid "
                + "WHERE f.uuid_low = ? OR f.uuid_high = ? ORDER BY p.player_name";
        List<FriendRecord> friends = new ArrayList<>();
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            String id = playerId.toString();
            statement.setString(1, id);
            statement.setString(2, id);
            statement.setString(3, id);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    friends.add(new FriendRecord(UUID.fromString(result.getString("player_uuid")),
                            result.getString("player_name"), result.getBoolean("online"), result.getString("server_name")));
                }
            }
            return friends;
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    public boolean createInvite(UUID inviter, UUID target, String serverName) {
        if (!isFriend(inviter, target)) {
            return false;
        }
        String sql = "INSERT INTO friend_invites (target_uuid, inviter_uuid, server_name, expires_at) "
                + "VALUES (?, ?, ?, DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 2 MINUTE)) "
                + "ON DUPLICATE KEY UPDATE server_name = VALUES(server_name), expires_at = VALUES(expires_at)";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, target.toString());
            statement.setString(2, inviter.toString());
            statement.setString(3, serverName);
            statement.executeUpdate();
            enqueueEvent(connection, target, inviter, "INVITE", serverName);
            return true;
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    public Optional<String> consumeInvite(UUID target, UUID inviter) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            String serverName = null;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT server_name FROM friend_invites WHERE target_uuid = ? AND inviter_uuid = ? AND expires_at > CURRENT_TIMESTAMP")) {
                statement.setString(1, target.toString());
                statement.setString(2, inviter.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        serverName = result.getString("server_name");
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM friend_invites WHERE target_uuid = ? AND inviter_uuid = ?")) {
                statement.setString(1, target.toString());
                statement.setString(2, inviter.toString());
                statement.executeUpdate();
            }
            connection.commit();
            return Optional.ofNullable(serverName);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    public void enqueueEvent(UUID recipient, UUID sender, String type, String payload) {
        try (Connection connection = dataSource.getConnection()) {
            enqueueEvent(connection, recipient, sender, type, payload);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    public List<FriendEvent> pendingEvents(int limit) {
        String sql = "SELECT e.event_id, e.recipient_uuid, e.sender_uuid, e.event_type, e.payload FROM friend_events e "
            + "JOIN friend_presence p ON p.player_uuid = e.recipient_uuid AND p.online = TRUE "
            + "WHERE e.delivered_at IS NULL ORDER BY e.event_id LIMIT ?";
        List<FriendEvent> events = new ArrayList<>();
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    events.add(new FriendEvent(result.getLong("event_id"),
                            UUID.fromString(result.getString("recipient_uuid")),
                            UUID.fromString(result.getString("sender_uuid")),
                            result.getString("event_type"), result.getString("payload")));
                }
            }
            return events;
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    public void markEventDelivered(long eventId) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE friend_events SET delivered_at = CURRENT_TIMESTAMP WHERE event_id = ? AND delivered_at IS NULL")) {
            statement.setLong(1, eventId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    public void updatePresence(UUID playerId, String playerName, String serverName) {
        registerPlayer(playerId, playerName);
        String sql = "INSERT INTO friend_presence (player_uuid, server_name, online) VALUES (?, ?, TRUE) "
                + "ON DUPLICATE KEY UPDATE server_name = VALUES(server_name), online = TRUE, updated_at = CURRENT_TIMESTAMP";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, serverName);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    public void setOffline(UUID playerId) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE friend_presence SET online = FALSE, server_name = NULL, updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    public void clearPresence() {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE friend_presence SET online = FALSE, server_name = NULL")) {
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }

    private boolean isFriend(Connection connection, UUID first, UUID second) throws SQLException {
        UUID low = first.toString().compareTo(second.toString()) < 0 ? first : second;
        UUID high = low.equals(first) ? second : first;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM friend_links WHERE uuid_low = ? AND uuid_high = ?")) {
            statement.setString(1, low.toString());
            statement.setString(2, high.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean requestExists(Connection connection, UUID requester, UUID target) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM friend_requests WHERE requester_uuid = ? AND target_uuid = ?")) {
            statement.setString(1, requester.toString());
            statement.setString(2, target.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void addFriendship(Connection connection, UUID first, UUID second) throws SQLException {
        UUID low = first.toString().compareTo(second.toString()) < 0 ? first : second;
        UUID high = low.equals(first) ? second : first;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT IGNORE INTO friend_links (uuid_low, uuid_high) VALUES (?, ?)")) {
            statement.setString(1, low.toString());
            statement.setString(2, high.toString());
            statement.executeUpdate();
        }
    }

    private void deleteRequests(Connection connection, UUID first, UUID second) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM friend_requests WHERE (requester_uuid = ? AND target_uuid = ?) OR (requester_uuid = ? AND target_uuid = ?)")) {
            statement.setString(1, first.toString());
            statement.setString(2, second.toString());
            statement.setString(3, second.toString());
            statement.setString(4, first.toString());
            statement.executeUpdate();
        }
    }

    private void enqueueEvent(Connection connection, UUID recipient, UUID sender, String type, String payload) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO friend_events (recipient_uuid, sender_uuid, event_type, payload) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, recipient.toString());
            statement.setString(2, sender.toString());
            statement.setString(3, type);
            statement.setString(4, payload);
            statement.executeUpdate();
        }
    }

    private IllegalStateException databaseError(SQLException exception) {
        return new IllegalStateException("Friends database operation failed", exception);
    }

    public enum RequestResult {
        SENT,
        ACCEPTED,
        ALREADY_PENDING,
        ALREADY_FRIENDS,
        SELF
    }

    public record PlayerRecord(UUID uuid, String name) {
    }

    public record FriendRecord(UUID uuid, String name, boolean online, String serverName) {
    }

    public record FriendEvent(long id, UUID recipient, UUID sender, String type, String payload) {
    }
}