package io.nightbeam.donutorders.gui;

import io.nightbeam.donutorders.service.OrderService;
import io.nightbeam.donutorders.util.Sounds;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** {@code /orders sell}: shows what selling would pay, then sells into the best orders on confirmation. */
public final class SellFlow {

    private SellFlow() {
    }

    public static void confirm(GuiManager gui, Player player, boolean everything, Runnable back) {
        OrderService orders = gui.orders();
        List<ItemStack> candidates = orders.sellCandidates(player, everything);
        OrderService.Estimate estimate = orders.estimate(player, candidates);
        if (estimate.units() <= 0) {
            gui.plugin().messages().sendRaw(player, "sell.nothing", "&cNo open order wants what you are selling.");
            Sounds.play(player, gui.plugin().getConfig(), "error");
            return;
        }
        Runnable sell = () -> orders.sell(player, everything).thenAccept(summary -> gui.plugin().schedulerAdapter().runEntity(player, () -> {
            gui.plugin().messages().send(player, orders.describe(summary));
            Sounds.play(player, gui.plugin().getConfig(), summary.delivered() > 0 ? "success" : "error");
        }));
        if (!gui.plugin().getConfig().getBoolean("sell.confirm", true)) {
            sell.run();
            return;
        }
        var messages = gui.plugin().messages();
        ItemStack display = gui.button("sell", Material.EMERALD)
                .name(messages.name("gui.sell.title-item", "<green>Sell %amount% items", "amount", String.valueOf(estimate.units())))
                .lore(List.of(
                        messages.lore("gui.sell.orders", "<gray>To <white>%count%</white> orders, best price first", "count", String.valueOf(estimate.orders())),
                        messages.lore("gui.sell.gross", "<gray>Worth: <gold>%amount%", "amount", orders.format(estimate.gross())),
                        estimate.tax() > 0 ? messages.lore("gui.sell.tax", "<gray>Tax: <red>-%amount%", "amount", orders.format(estimate.tax()))
                                : messages.lore("gui.sell.no-tax", "<gray>No tax"),
                        messages.lore("gui.sell.net", "<gray>You get: <green>%amount%", "amount", orders.format(estimate.net()))))
                .build();
        gui.confirm(player, new ConfirmGui.Request(messages.name("gui.sell.title", "Sell to orders?"), display, List.of(),
                who -> sell.run(), who -> back.run()));
    }
}
