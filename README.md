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
- `/orders history` — show recent transaction journal entries.
- `/orders help` — help.
- `/orders admin reload` — reload config.
- `/orders admin inspect <id>` — inspect persisted order state.
- `/orders admin cancel <id>` — administrator cancellation.
- `/orders admin recover` — list open refund obligations and interrupted transaction counts.
- `/orders admin refund <obligation-id>` — explicitly attempt a recorded refund obligation after checking the economy provider's transaction history.
- `/orders admin debug` — diagnostics.

## Permissions
Player permissions are declared in `src/main/resources/plugin.yml`. Admin permissions default to operators. Grant only the specific admin permission needed.

## Transaction safety and current limitations
The plugin writes transaction journal entries before escrow withdrawal and delivery payout attempts. Delivery transactions are serialized per order; unresolved payout states lock an order to prevent blind duplicate payouts. Cancellation and expiration record refund obligations durably before attempting the refund. Vault and SQLite do not share an atomic transaction, so ambiguous outcomes require manual review and must not be blindly retried.

**This branch is still under development and is not production-ready.** The marketplace has page navigation and the delivery GUI keeps items in the player's inventory until the amount is confirmed, avoiding items being stranded in a temporary GUI if it is closed. Delivery accepts plain matching item stacks only. Refund obligations can be inspected and explicitly attempted by an administrator. Because Vault and SQLite cannot commit atomically, a crash or ambiguous economy-provider response can still require manual reconciliation; a refund in `PROCESSING` must not be retried until the economy provider's history has been checked. Full crash-time item restoration, automatic reconciliation, sorting/search/filter controls, PlaceholderAPI, broad integration tests, and staging-server verification remain unfinished. Do not use it with real player balances until the full lifecycle has been compiled and tested on a staging server.

## Testing
An initial unit test covers order quantity arithmetic. The current development environment could not reach GitHub to clone the repository, so no Maven build or in-game test has been run here. Do not interpret the presence of source files as a passing build.
