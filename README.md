# SuperOrders

SuperOrders is a Paper/Purpur Java plugin project for a player-driven Minecraft buy-order marketplace. It targets Minecraft 1.21.11 and Java 21, uses Vault for economy and SQLite for persistence.

## Build
Install JDK 21 and Maven 3.9+, then run:

```sh
mvn clean package
```

The shaded plugin JAR will be in `target/`. Install Vault and a compatible economy plugin on your server, put the SuperOrders JAR in `plugins/`, and restart.

## Commands
- `/orders` or `/orders market` — marketplace
- `/orders create` — create an order
- `/orders mine` — own orders
- `/orders history` — history
- `/orders help` — help
- `/orders admin reload` — reload supported configuration
- `/orders admin debug` — diagnostics

## Permissions
See `plugin.yml`. Administrative permissions default to operators.

## Financial safety
Vault and SQLite do not share a distributed transaction. A production marketplace must persist transaction intent before economy or inventory side effects and reconcile uncertain outcomes instead of blindly retrying. Test on a staging server with backups before using real player balances.

## Status
This project is under active implementation. A successful Maven build is not proof of in-game transaction safety; validate the complete lifecycle on a staging server before production use.