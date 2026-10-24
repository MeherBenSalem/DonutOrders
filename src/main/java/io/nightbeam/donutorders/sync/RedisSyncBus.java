package io.nightbeam.donutorders.sync;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

/**
 * Redis is a notification bus only. Receivers always re-read MySQL before touching their cache.
 * The subscriber reconnects with back-off when Redis restarts; MySQL polling covers the gap.
 */
public final class RedisSyncBus implements SyncBus {

    private final Logger logger;
    private final String instanceId;
    private final String host;
    private final int port;
    private final String password;
    private final String channel;

    private volatile boolean running;
    private volatile BiConsumer<Kind, UUID> handler = (kind, id) -> { };
    private Thread subscriberThread;
    private Jedis publisher;
    private volatile Jedis subscriber;
    private volatile JedisPubSub pubSub;

    public RedisSyncBus(
            Logger logger,
            String instanceId,
            String host,
            int port,
            String password,
            String channel
    ) {
        this.logger = logger;
        this.instanceId = instanceId;
        this.host = host;
        this.port = port;
        this.password = password == null ? "" : password;
        this.channel = channel;
    }

    @Override
    public void setHandler(BiConsumer<Kind, UUID> handler) {
        this.handler = handler;
    }

    @Override
    public boolean live() {
        return running;
    }

    @Override
    public void start() {
        try {
            synchronized (this) {
                this.publisher = connect();
            }
            this.running = true;
            this.subscriberThread = new Thread(this::subscribeLoop, "DonutOrders-Redis");
            this.subscriberThread.setDaemon(true);
            this.subscriberThread.start();
            logger.info("Redis sync subscribed to channel " + channel + ".");
        } catch (Exception exception) {
            running = false;
            closeQuietly();
            logger.warning("Redis sync failed to start (orders stay safe; servers refresh by polling MySQL): "
                    + exception.getMessage());
        }
    }

    @Override
    public void shutdown() {
        running = false;
        try {
            JedisPubSub current = pubSub;
            if (current != null && current.isSubscribed()) {
                current.unsubscribe();
            }
        } catch (Exception ignored) {
        }
        closeQuietly();
        if (subscriberThread != null) {
            subscriberThread.interrupt();
        }
    }

    @Override
    public synchronized void publish(Kind kind, UUID id) {
        if (!running) {
            return;
        }
        String message = instanceId + "\t" + kind.name() + "\t" + id;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (publisher == null) publisher = connect();
                publisher.publish(channel, message);
                return;
            } catch (Exception exception) {
                closePublisher();
                if (attempt == 1) logger.warning("Failed to publish a Redis sync message: " + exception.getMessage());
            }
        }
    }

    private void subscribeLoop() {
        long backoff = 1_000L;
        boolean warned = false;
        while (running) {
            try {
                subscriber = connect();
                pubSub = new JedisPubSub() {
                    @Override
                    public void onMessage(String ch, String message) {
                        handleMessage(message);
                    }
                };
                if (warned) logger.info("Redis sync reconnected.");
                backoff = 1_000L;
                warned = false;
                subscriber.subscribe(pubSub, channel);
            } catch (Exception exception) {
                if (running && !warned) {
                    warned = true;
                    logger.warning("Redis subscriber disconnected (" + exception.getMessage()
                            + "); retrying. MySQL polling keeps servers in sync meanwhile.");
                }
            } finally {
                try {
                    if (subscriber != null) subscriber.close();
                } catch (Exception ignored) {
                }
            }
            if (!running) return;
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException interrupted) {
                return;
            }
            backoff = Math.min(30_000L, backoff * 2);
        }
    }

    private void handleMessage(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        String[] parts = message.split("\t", 3);
        if (parts.length != 3) {
            return;
        }
        if (instanceId.equals(parts[0])) {
            return;
        }
        UUID id;
        Kind kind;
        try {
            id = UUID.fromString(parts[2]);
            kind = Kind.valueOf(parts[1]);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        try {
            handler.accept(kind, id);
        } catch (RuntimeException exception) {
            logger.warning("Failed to apply a Redis sync message: " + exception.getMessage());
        }
    }

    private Jedis connect() {
        Jedis jedis = new Jedis(host, port);
        if (!password.isBlank()) {
            jedis.auth(password);
        }
        jedis.ping();
        return jedis;
    }

    private synchronized void closePublisher() {
        try {
            if (publisher != null) {
                publisher.close();
            }
        } catch (Exception ignored) {
        }
        publisher = null;
    }

    private void closeQuietly() {
        closePublisher();
        try {
            if (subscriber != null) {
                subscriber.close();
            }
        } catch (Exception ignored) {
        }
        subscriber = null;
    }
}
