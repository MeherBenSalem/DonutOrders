package io.nightbeam.donutorders.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * One line per order event in plugins/DonutOrders/logs/transactions-YYYY-MM-DD.log (UTC), written on its
 * own thread so a slow disk never delays a purchase.
 */
public final class TransactionLog {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final Path directory;
    private final BooleanSupplier enabled;
    private final Logger logger;
    private final ExecutorService writer;
    private volatile boolean warned;

    public TransactionLog(Path directory, BooleanSupplier enabled, Logger logger) {
        this.directory = directory;
        this.enabled = enabled;
        this.logger = logger;
        this.writer = directory == null ? null : Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "DonutOrders-TransactionLog");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Appends "ACTION key=value ..." with a UTC timestamp. Values containing spaces are quoted. */
    public void log(String action, Object... keyValues) {
        if (writer == null || !enabled.getAsBoolean()) return;
        Instant now = Instant.now();
        StringBuilder line = new StringBuilder("[").append(TIME.format(now)).append(" UTC] ").append(action);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            String value = String.valueOf(keyValues[i + 1]).replace('\n', ' ').replace('\r', ' ');
            line.append(' ').append(keyValues[i]).append('=');
            if (value.indexOf(' ') >= 0 || value.isEmpty()) line.append('"').append(value.replace("\"", "'")).append('"');
            else line.append(value);
        }
        line.append(System.lineSeparator());
        String text = line.toString();
        try {
            writer.execute(() -> write(now, text));
        } catch (RuntimeException rejected) {
            // Shutting down.
        }
    }

    private void write(Instant now, String text) {
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("transactions-" + DAY.format(now) + ".log"), text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            if (!warned) {
                warned = true;
                logger.warning("Cannot write the transaction log: " + exception.getMessage());
            }
        }
    }

    public void shutdown() {
        if (writer == null) return;
        writer.shutdown();
        try {
            writer.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
