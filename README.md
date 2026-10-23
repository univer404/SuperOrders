# SuperOrders

SuperOrders is a Java plugin project for a player-driven Minecraft buy-order marketplace, targeting Paper/Purpur 1.21.11 and Java 21. It uses Vault for economy and SQLite for durable data.

## Build and install
1. Install JDK 21 and Maven 3.9 or newer.
2. Run `mvn clean package` from the project root.
3. Copy the shaded JAR from `target/` into the server's `plugins/` folder.
4. Install Vault and a compatible economy provider, then restart the server.
5. Review `plugins/SuperOrders/config.yml`, `messages.yml`, and `gui.yml`. The GUI file controls the marketplace title, size, and navigation slots.

## Commands
- `/orders` or `/orders market` — open marketplace.
- `/orders create` — open the GUI item picker and configure quantity and unit price before confirming. The GUI starts at the configured minimum unit price.
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
- `/orders admin escrow-refund <transaction-id>` — manually compensate an unresolved order-creation withdrawal only after confirming the original withdrawal succeeded.
- `/orders admin resolve <transaction-id> paid` — finalize a `PAYMENT_PENDING` delivery after verifying that the seller was paid; it does not send another payout.
- `/orders admin resolve <transaction-id> restore` — restore reserved items to an online seller for an `ITEMS_RESERVED` transaction; the seller must be online. Do not use this for `DELIVERY_PREPARED` or `FAILED_REQUIRES_REVIEW` until inventory has been inspected.
- `/orders admin debug` — diagnostics.

## Permissions
Player permissions are declared in `src/main/resources/plugin.yml`. Admin permissions default to operators. Grant only the specific admin permission needed.

## Transaction safety and current limitations
The plugin writes transaction journal entries before escrow withdrawal and delivery payout attempts. Delivery transactions are serialized per order; unresolved delivery states lock the order to prevent blind duplicate payouts. Cancellation and expiration now claim their durable refund obligation as `PROCESSING` **before** calling Vault. A confirmed payout becomes `PAID`; a definite rejection becomes `REVIEW`; an exception or ambiguous provider outcome stays `PROCESSING` so the same refund cannot be casually retried. Check the economy provider's history before any manual action.

Recovery guidance:
- `DELIVERY_PREPARED`: inspect the seller inventory and economy history. The crash may have happened before, during, or after item reservation; do not restore or pay based on this state alone. If item reservation throws, the plugin marks the transaction `FAILED_REQUIRES_REVIEW` and logs the transaction ID.
- `ITEMS_RESERVED`: items were recorded as removed, but no payout should be assumed. Verify inventory and provider history before using `/orders admin resolve <transaction-id> restore`.
- `PAYMENT_PENDING`: the payout may or may not have succeeded. Verify the provider history first. Use `/orders admin resolve <transaction-id> paid` only if the seller was paid; it finalizes escrow without issuing another payout.
- `PROCESSING` refund obligation: payout outcome may be ambiguous. Do not rerun it until provider history confirms whether payment occurred.
- `ESCROW_WITHDRAW_PENDING` or `ESCROW_REFUND_PENDING`: inspect balance/provider history and order records before taking action. Never issue compensation unless the original withdrawal is confirmed.
- `FAILED_REQUIRES_REVIEW`: item removal may have been partial or another step may have failed. Inspect the seller inventory and economy transaction history before attempting compensation; do not retry delivery blindly.

Vault, Bukkit inventory changes, and SQLite cannot participate in one atomic transaction. Therefore no plugin can safely infer the outcome of every crash solely from its journal. Ambiguous states require manual reconciliation and may require the player to remain online for item restoration. This branch is still under development; automated tests cover repository state transitions but do not replace staging-server failure-injection tests. Back up the SQLite database before updating and test with non-production balances first.
 
## PlaceholderAPI
When PlaceholderAPI is installed, SuperOrders registers the `superorders` expansion:
- `%superorders_active_orders%`
- `%superorders_my_active_orders%`
- `%superorders_my_active_escrow%`
- `%superorders_my_delivered_items%`

## Testing
GitHub Actions runs `mvn clean verify` on Java 21 and uploads the built plugin JAR when successful. A passing CI build confirms compilation and automated tests, but does not replace in-game testing against the target Paper/Purpur version and the server's actual Vault economy provider.
