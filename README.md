# SuperOrders

SuperOrders is a Java plugin project for a player-driven Minecraft buy-order marketplace, targeting Paper/Purpur 1.21.11 and Java 21. It uses Vault for economy and SQLite for durable data.

## Build and install
1. Install JDK 21 and Maven 3.9 or newer.
2. Run `mvn clean package` from the project root.
3. Copy the shaded JAR from `target/` into the server's `plugins/` folder.
4. Install Vault and a compatible economy provider, then restart the server.
5. Review `plugins/SuperOrders/config.yml`, `messages.yml`, and `gui.yml`.

## Commands
- `/orders` or `/orders market` — open marketplace.
- `/orders create` — create an order using validated chat prompts.
- `/orders mine` — list your orders.
- `/orders cancel <id>` — cancel your active order and request unused escrow back.
- `/orders history` — open transaction history.
- The marketplace GUI includes pagination, search by item/owner/order ID, and newest/lowest-price/highest-price sorting.
- `/orders mine` — manage your orders and request cancellation through a confirmation prompt.
- `/orders help` — help.
- `/orders admin reload` — reload config.
- `/orders admin inspect <id>` — inspect persisted order state.
- `/orders admin cancel <id>` — administrator cancellation.
- `/orders admin recover` — list open refund obligations and unresolved transaction details.
- `/orders admin refund <obligation-id>` — explicitly attempt a recorded refund obligation after checking the economy provider's transaction history.
- `/orders admin resolve <transaction-id> paid` — finalize a `PAYMENT_PENDING` delivery after verifying that the seller was paid; it does not send another payout.
- `/orders admin resolve <transaction-id> restore` — restore reserved items to an online seller for an `ITEMS_RESERVED` transaction; the seller must be online.
- `/orders admin debug` — diagnostics.

## Permissions
Player permissions are declared in `src/main/resources/plugin.yml`. Admin permissions default to operators. Grant only the specific admin permission needed.

## Transaction safety and current limitations
The plugin writes transaction journal entries before escrow withdrawal and delivery payout attempts. Delivery transactions are serialized per order; unresolved payout states lock an order to prevent blind duplicate payouts. Cancellation and expiration record refund obligations durably before attempting the refund. Vault and SQLite do not share an atomic transaction, so ambiguous outcomes require manual review and must not be blindly retried.

**This branch is still under development and is not production-ready.** Delivery accepts plain matching item stacks only. The GUI leaves items in the seller's inventory until the amount is confirmed. Refund obligations and interrupted transactions are recorded for admin review, and the optional PlaceholderAPI expansion provides basic marketplace/player statistics. Because Vault and SQLite cannot commit atomically, a crash or ambiguous economy-provider response can still require manual reconciliation. Never retry an obligation in `PROCESSING` or finalize a `PAYMENT_PENDING` transaction until the economy provider's history has been checked. Item restoration is deliberately restricted to `ITEMS_RESERVED` transactions; ambiguous states require investigation. Broad automated integration tests, complete crash recovery across Bukkit inventory and Vault operations, and staging-server verification remain unfinished. Do not use it with real player balances until the full lifecycle has been compiled and tested on a staging server.

## PlaceholderAPI
When PlaceholderAPI is installed, SuperOrders registers the `superorders` expansion:
- `%superorders_active_orders%`
- `%superorders_my_active_orders%`
- `%superorders_my_active_escrow%`
- `%superorders_my_delivered_items%`

## Testing
The repository has initial unit tests, but they do not cover the full economy/database lifecycle. The current development environment could not reach GitHub to clone the repository, so no Maven build or in-game test has been run here. Do not interpret the presence of source files as a passing build.
