package com.donutorders.util;

import com.donutorders.DonutOrders;
import com.donutorders.scheduler.FoliaScheduler;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks Modrinth for a newer DonutOrders release on plugin startup.
 */
public final class ModrinthUpdateChecker {

    private static final String PROJECT_SLUG = "donut-orders";
    private static final String MODRINTH_URL = "https://modrinth.com/plugin/" + PROJECT_SLUG;
    private static final String VERSIONS_ENDPOINT =
            "https://api.modrinth.com/v2/project/" + PROJECT_SLUG + "/version";
    private static final Pattern VERSION_NUMBER =
            Pattern.compile("\"version_number\"\\s*:\\s*\"([^\"]+)\"");

    private ModrinthUpdateChecker() {}

    /**
     * Fetches the latest Modrinth release asynchronously and logs the result.
     */
    public static void checkAsync(DonutOrders plugin) {
        if (!plugin.getConfig().getBoolean("update-check.enabled", true)) {
            return;
        }

        String currentVersion = plugin.getDescription().getVersion();
        FoliaScheduler.runAsync(() -> {
            try {
                String latestVersion = fetchLatestVersion(plugin);
                if (latestVersion == null) {
                    return;
                }

                int comparison = compareVersions(latestVersion, currentVersion);
                FoliaScheduler.runGlobal(() -> {
                    if (comparison > 0) {
                        plugin.getLogger().warning(
                                "[DonutOrders] A new version is available: "
                                        + latestVersion + " (running " + currentVersion + "). "
                                        + "Download: " + MODRINTH_URL);
                    } else {
                        plugin.getLogger().info(
                                "[DonutOrders] Running the latest version (" + currentVersion + ").");
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().log(Level.FINE,
                        "[DonutOrders] Could not check Modrinth for updates.", e);
            }
        });
    }

    private static String fetchLatestVersion(DonutOrders plugin) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(VERSIONS_ENDPOINT).toURL()
                .openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(5_000);
        connection.setRequestProperty("User-Agent",
                "DonutOrders/" + plugin.getDescription().getVersion()
                        + " (" + MODRINTH_URL + ")");

        int status = connection.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            plugin.getLogger().fine("[DonutOrders] Modrinth update check returned HTTP " + status + ".");
            return null;
        }

        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
        }

        Matcher matcher = VERSION_NUMBER.matcher(body);
        if (!matcher.find()) {
            plugin.getLogger().fine("[DonutOrders] Modrinth response did not include a version number.");
            return null;
        }
        return matcher.group(1);
    }

    /**
     * Compares dotted version strings (e.g. {@code 1.10.0} vs {@code 1.9.0}).
     *
     * @return positive if {@code a} is newer than {@code b}
     */
    static int compareVersions(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int leftPart = i < left.length ? parseVersionPart(left[i]) : 0;
            int rightPart = i < right.length ? parseVersionPart(right[i]) : 0;
            if (leftPart != rightPart) {
                return Integer.compare(leftPart, rightPart);
            }
        }
        return 0;
    }

    private static int parseVersionPart(String part) {
        int end = 0;
        while (end < part.length() && Character.isDigit(part.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        return Integer.parseInt(part.substring(0, end));
    }
}
