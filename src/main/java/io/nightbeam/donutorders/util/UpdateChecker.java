package io.nightbeam.donutorders.util;

import io.nightbeam.donutorders.DonutOrdersPlugin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class UpdateChecker implements Listener {

    private static final String PROJECT_ID = "donut-orders";
    private static final String API_URL = "https://api.modrinth.com/v2/project/" + PROJECT_ID + "/version";
    private static final String DOWNLOAD_URL = "https://modrinth.com/plugin/donut-orders";

    private final DonutOrdersPlugin plugin;
    private final HttpClient httpClient;
    private final AtomicReference<String> latestVersion = new AtomicReference<>();
    private volatile boolean updateAvailable = false;
    private volatile long lastCheckTime = 0;

    public UpdateChecker(DonutOrdersPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void checkNow() {
        if (!plugin.getConfig().getBoolean("update-checker.enabled", true)) {
            return;
        }

        long intervalHours = plugin.getConfig().getLong("update-checker.check-interval-hours", 12);
        long intervalMillis = intervalHours * 3_600_000L;
        long now = System.currentTimeMillis();

        if (now - lastCheckTime < intervalMillis && lastCheckTime > 0) {
            return;
        }

        lastCheckTime = now;
        String currentVersion = plugin.getDescription().getVersion();

        CompletableFuture.runAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(API_URL))
                        .header("Accept", "application/json")
                        .header("User-Agent", "DonutOrders/" + currentVersion)
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    plugin.getLogger().warning("Update check failed: HTTP " + response.statusCode());
                    return;
                }

                String parsedVersion = parseLatestReleaseVersion(response.body());
                if (parsedVersion == null) {
                    plugin.getLogger().warning("Update check failed: could not parse a release version from Modrinth.");
                    return;
                }

                latestVersion.set(parsedVersion);
                updateAvailable = isNewer(parsedVersion, currentVersion);
                logConsoleStatus(currentVersion, parsedVersion, updateAvailable);
                plugin.schedulerAdapter().runGlobal(
                        () -> notifyOnlineAdmins(currentVersion, parsedVersion, updateAvailable));
            } catch (Exception exception) {
                plugin.getLogger().log(Level.FINE, "Update check failed (non-critical): " + exception.getMessage());
            }
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!updateAvailable) {
            return;
        }
        if (!plugin.getConfig().getBoolean("update-checker.notify-admins", true)) {
            return;
        }

        Player player = event.getPlayer();
        if (!player.hasPermission("donutorders.update.notify")) {
            return;
        }

        String currentVersion = plugin.getDescription().getVersion();
        String latest = latestVersion.get();
        plugin.schedulerAdapter().runEntity(player, () -> sendAdminStatus(player, currentVersion, latest, true));
    }

    private void logConsoleStatus(String currentVersion, String latest, boolean needsUpdate) {
        if (!plugin.getConfig().getBoolean("update-checker.notify-console", true)) {
            return;
        }
        plugin.getLogger().info("");
        if (needsUpdate) {
            plugin.getLogger().info("A new version is available!");
            plugin.getLogger().info("Current Version: " + currentVersion);
            plugin.getLogger().info("Latest Version: " + latest);
            plugin.getLogger().info("Download: " + DOWNLOAD_URL);
        } else {
            plugin.getLogger().info("DonutOrders is up to date (" + currentVersion + ").");
        }
        plugin.getLogger().info("");
    }

    private void notifyOnlineAdmins(String currentVersion, String latest, boolean needsUpdate) {
        if (!plugin.getConfig().getBoolean("update-checker.notify-admins", true)) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission("donutorders.update.notify")) {
                continue;
            }
            plugin.schedulerAdapter().runEntity(
                    player, () -> sendAdminStatus(player, currentVersion, latest, needsUpdate));
        }
    }

    private void sendAdminStatus(Player player, String currentVersion, String latest, boolean needsUpdate) {
        MessageUtil messages = plugin.messages();
        player.sendMessage(Component.empty());
        if (needsUpdate) {
            player.sendMessage(messages.component(
                    "update.available",
                    "&eA newer version of DonutOrders is available."));
        } else {
            player.sendMessage(messages.component(
                    "update.up-to-date",
                    "&aDonutOrders is up to date."));
        }
        player.sendMessage(messages.component(
                "update.version-line",
                "&7Current: %current%  Latest: %latest%",
                "current", currentVersion,
                "latest", latest == null ? currentVersion : latest));
        if (needsUpdate) {
            player.sendMessage(Component.text(DOWNLOAD_URL, NamedTextColor.AQUA));
        }
        player.sendMessage(Component.empty());
    }

    static String parseLatestReleaseVersion(String jsonBody) {
        if (jsonBody == null || jsonBody.isEmpty()) {
            return null;
        }
        String bestRelease = null;
        int searchFrom = 0;
        while (true) {
            int versionKey = jsonBody.indexOf("\"version_number\"", searchFrom);
            if (versionKey < 0) {
                break;
            }
            String version = readJsonStringValue(jsonBody, versionKey);
            int nextVersionKey = jsonBody.indexOf("\"version_number\"", versionKey + 16);
            int typeKey = jsonBody.indexOf("\"version_type\"", versionKey);
            searchFrom = versionKey + 16;
            if (version == null) {
                continue;
            }
            if (typeKey < 0 || (nextVersionKey >= 0 && typeKey > nextVersionKey)) {
                continue;
            }
            String type = readJsonStringValue(jsonBody, typeKey);
            if (type == null || !"release".equalsIgnoreCase(type)) {
                continue;
            }
            if (bestRelease == null || isNewer(version, bestRelease)) {
                bestRelease = version;
            }
        }
        return bestRelease;
    }

    private static String readJsonStringValue(String json, int keyIndex) {
        int colon = json.indexOf(':', keyIndex);
        if (colon < 0) {
            return null;
        }
        int quote = json.indexOf('"', colon + 1);
        if (quote < 0) {
            return null;
        }
        int end = json.indexOf('"', quote + 1);
        if (end < 0) {
            return null;
        }
        return json.substring(quote + 1, end);
    }

    static boolean isNewer(String latest, String current) {
        try {
            int[] latestParts = parseVersion(latest);
            int[] currentParts = parseVersion(current);

            for (int i = 0; i < Math.max(latestParts.length, currentParts.length); i++) {
                int l = i < latestParts.length ? latestParts[i] : 0;
                int c = i < currentParts.length ? currentParts[i] : 0;
                if (l > c) {
                    return true;
                }
                if (l < c) {
                    return false;
                }
            }
            return false;
        } catch (Exception exception) {
            return false;
        }
    }

    private static int[] parseVersion(String version) {
        String cleaned = version.replaceAll("[^0-9.]", "");
        String[] parts = cleaned.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Integer.parseInt(parts[i].isEmpty() ? "0" : parts[i]);
        }
        return result;
    }
}
