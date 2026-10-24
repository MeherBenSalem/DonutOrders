package io.nightbeam.donutorders.service;

import io.nightbeam.donutorders.util.Money;
import java.util.function.Supplier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

/**
 * The creation fee (paid by the buyer with the order) and the seller tax (taken from each delivery payout).
 * Permission tiers lower the tax: the lowest matching rate applies. Amounts are rounded to cents.
 */
public final class FeeCalculator {

    private final Supplier<FileConfiguration> config;

    public FeeCalculator(Supplier<FileConfiguration> config) {
        this.config = config;
    }

    public boolean taxEnabled() {
        return config.get().getBoolean("economy.seller-tax.enabled", false);
    }

    /** The seller's tax rate as a fraction (0.05 for 5%). */
    public double taxRate(Player seller) {
        if (!taxEnabled()) return 0.0D;
        FileConfiguration cfg = config.get();
        double rate = fraction(cfg.getDouble("economy.seller-tax.percent", 0.0D));
        ConfigurationSection tiers = cfg.getConfigurationSection("economy.seller-tax.permissions");
        if (seller != null && tiers != null) {
            for (String permission : tiers.getKeys(true)) {
                if (tiers.isConfigurationSection(permission)) continue;
                if (seller.hasPermission(permission)) rate = Math.min(rate, fraction(tiers.getDouble(permission, rate * 100.0D)));
            }
        }
        return rate;
    }

    /** Where seller tax and creation fees go: a player name or UUID, or blank to remove them from the economy. */
    public String feeAccount() {
        String account = config.get().getString("economy.deposit-to", "");
        return account == null ? "" : account.trim();
    }

    public double creationFee(Player buyer, double total) {
        FileConfiguration cfg = config.get();
        if (!cfg.getBoolean("economy.creation-fee.enabled", false)) return 0.0D;
        String exempt = cfg.getString("economy.creation-fee.exempt-permission", "donutorders.fee.exempt");
        if (buyer != null && exempt != null && !exempt.isBlank() && buyer.hasPermission(exempt)) return 0.0D;
        double flat = Math.max(0.0D, cfg.getDouble("economy.creation-fee.flat", 0.0D));
        double percent = fraction(cfg.getDouble("economy.creation-fee.percent", 0.0D));
        double max = cfg.getDouble("economy.creation-fee.max", 0.0D);
        double fee = Money.round(flat + total * percent);
        return max > 0.0D ? Math.min(fee, max) : fee;
    }

    public boolean refundFeeOnCancel() {
        return config.get().getBoolean("economy.creation-fee.refund-on-cancel", false);
    }

    static double fraction(double percent) {
        if (!Double.isFinite(percent)) return 0.0D;
        return Math.max(0.0D, Math.min(100.0D, percent)) / 100.0D;
    }
}
