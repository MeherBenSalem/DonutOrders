package io.nightbeam.donutorders.service;

import io.nightbeam.donutorders.economy.VaultEconomyProvider;
import io.nightbeam.donutorders.storage.SqlOrderRepository;
import io.nightbeam.donutorders.util.Money;
import io.nightbeam.donutorders.util.SchedulerAdapter;
import java.util.UUID;
import java.util.logging.Logger;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Every money movement goes through here. Online players are paid on their own thread, everyone else on the
 * global thread. A deposit that fails is never lost: it is written down as owed and paid when the player
 * next joins (or on the next retry).
 */
public final class Payments {

    private final SchedulerAdapter scheduler;
    private final VaultEconomyProvider economy;
    private final SqlOrderRepository repository;
    private final FeeCalculator fees;
    private final Logger logger;

    public Payments(SchedulerAdapter scheduler, VaultEconomyProvider economy, SqlOrderRepository repository, FeeCalculator fees,
                    Logger logger) {
        this.scheduler = scheduler;
        this.economy = economy;
        this.repository = repository;
        this.fees = fees;
        this.logger = logger;
    }

    public boolean has(Player player, double amount) {
        try {
            return economy.has(player, amount);
        } catch (RuntimeException exception) {
            logger.warning("The economy failed to read a balance: " + exception.getMessage());
            return false;
        }
    }

    public double balance(Player player) {
        try {
            return economy.getBalance(player);
        } catch (RuntimeException exception) {
            return 0.0D;
        }
    }

    /** Takes money from a player; call on their thread. */
    public boolean withdraw(Player player, double amount) {
        if (!Money.positive(amount)) return true;
        try {
            EconomyResponse response = economy.withdraw(player, Money.round(amount));
            return response.transactionSuccess();
        } catch (RuntimeException exception) {
            logger.warning("The economy failed to withdraw " + amount + " from " + player.getName() + ": " + exception.getMessage());
            return false;
        }
    }

    /** Pays a player wherever they are. */
    public void pay(UUID player, double amount, String reason) {
        if (!Money.positive(amount) || player == null) return;
        Player online = Bukkit.getPlayer(player);
        if (online != null && online.isOnline()) {
            scheduler.runEntity(online, () -> payNow(online, amount, reason), () -> scheduler.runGlobal(() -> payNow(Bukkit.getOfflinePlayer(player), amount, reason)));
        } else {
            scheduler.runGlobal(() -> payNow(Bukkit.getOfflinePlayer(player), amount, reason));
        }
    }

    /** Pays on the current thread; a failure is written down as owed. */
    public boolean payNow(OfflinePlayer player, double amount, String reason) {
        if (!Money.positive(amount)) return true;
        double rounded = Money.round(amount);
        try {
            if (economy.deposit(player, rounded).transactionSuccess()) return true;
        } catch (RuntimeException exception) {
            logger.warning("The economy failed to pay " + rounded + " to " + player.getUniqueId() + " (" + reason + "): " + exception.getMessage());
        }
        owe(player.getUniqueId(), rounded, reason);
        return false;
    }

    public void owe(UUID player, double amount, String reason) {
        logger.warning("Could not pay " + amount + " to " + player + " (" + reason + "); it will be paid when they next join.");
        repository.addPayout(player, amount, reason, System.currentTimeMillis()).exceptionally(failure -> {
            logger.severe("Could not record " + amount + " owed to " + player + " (" + reason + "). Pay it manually: " + failure.getMessage());
            return null;
        });
    }

    /** Seller tax and creation fees: into the configured account, or out of the economy. */
    public void payFeeAccount(double amount) {
        String account = fees.feeAccount();
        if (!Money.positive(amount) || account.isBlank()) return;
        scheduler.runGlobal(() -> {
            OfflinePlayer target;
            try {
                target = Bukkit.getOfflinePlayer(UUID.fromString(account));
            } catch (IllegalArgumentException notUuid) {
                target = Bukkit.getOfflinePlayer(account);
            }
            try {
                if (!economy.deposit(target, Money.round(amount)).transactionSuccess()) {
                    logger.warning("Could not pay " + amount + " in fees to " + account + ".");
                }
            } catch (RuntimeException exception) {
                logger.warning("Could not pay fees to " + account + ": " + exception.getMessage());
            }
        });
    }

    /** Pays out anything owed to a player who just joined; call on their thread. */
    public void settleOwed(Player player, java.util.function.DoubleConsumer onPaid) {
        repository.takePayouts(player.getUniqueId()).thenAccept(payouts -> {
            if (payouts.isEmpty()) return;
            scheduler.runEntity(player, () -> {
                double paid = 0.0D;
                for (SqlOrderRepository.Payout payout : payouts) {
                    if (payNow(player, payout.amount(), payout.reason())) paid += payout.amount();
                }
                if (paid > 0.0D) onPaid.accept(paid);
            }, () -> payouts.forEach(payout -> owe(payout.player(), payout.amount(), payout.reason())));
        }).exceptionally(failure -> {
            logger.warning("Could not read money owed to " + player.getName() + ": " + failure.getMessage());
            return null;
        });
    }

    public String format(double amount) {
        try {
            return economy.format(amount);
        } catch (RuntimeException exception) {
            return String.format(java.util.Locale.US, "$%,.2f", amount);
        }
    }

    public String currencyName() {
        try {
            return economy.currencyName();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    public String bridge() {
        return economy.bridgeLabel();
    }
}
