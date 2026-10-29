# Friends System

A friends system for a Minecraft network made of Paper servers behind one Velocity proxy. The Paper plugin provides commands and the friend-list GUI. The Velocity plugin tracks server presence and routes messages and invites between servers. Both plugins use the same MariaDB database.

## Requirements

- Java 21 or newer
- Maven 3.9+
- Paper 1.21.4 and Velocity 3.4+
- MariaDB 10.6+ (or a compatible MySQL server)

## Build

From the project directory, run:

```sh
mvn package
```

Install `paper/target/friends-system-paper-1.0.0.jar` on every Paper backend and `velocity/target/friends-system-velocity-1.0.0.jar` on the Velocity proxy. Restart each server once to create default configuration files.

## Database setup

Create a database and a dedicated account. Use a strong password and grant the account access only to this database:

```sql
CREATE DATABASE friends CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'friends'@'%' IDENTIFIED BY 'replace-this-password';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, INDEX, ALTER ON friends.* TO 'friends'@'%';
```

Set matching connection settings in `plugins/FriendsSystem/config.yml` on every Paper backend and `plugins/friends-system/friends.properties` on Velocity. The tables are created automatically. Make sure the database is reachable from both the proxy and every backend; restrict network access to those hosts.

## Commands

- `/friend <player>` sends a request. A reciprocal request automatically becomes a friendship.
- `/friend accept <player>` and `/friend deny <player>` handle incoming requests.
- `/friend remove <player>` removes a friend.
- `/friendlist` opens the friend GUI. It shows online status and server; click a friend and send one chat message to message them.
- `/friend invite <player>` invites a friend to the server you are currently playing on.
- `/friend join <player>` accepts that player's active invite and connects you to their server. Invites expire after two minutes.

Messages and notifications are queued in MariaDB, so a friend on another backend can receive them. Events remain queued while the recipient is offline and are delivered when they next connect. The Paper plugin must be installed on every backend for the commands and GUI to be available there.