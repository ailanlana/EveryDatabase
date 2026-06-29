package br.com.finalcraft.everydatabase.manager.sync.jedis;

/**
 * Connection settings for a {@link JedisCacheSyncTransport}. Immutable; the same settings work
 * unchanged against Redis and Valkey (identical RESP wire protocol).
 *
 * <p>{@link #channel()} is the pub/sub channel every collection publishes to; the default is
 * {@value #DEFAULT_CHANNEL}. When several independent applications (or environments) share one
 * server, give each its own channel so their invalidation signals do not cross.
 */
public final class JedisCacheSyncConfig {

    /** Default Redis/Valkey port. */
    public static final int DEFAULT_PORT = 6379;
    /** Default pub/sub channel for cache-sync signals. */
    public static final String DEFAULT_CHANNEL = "everydatabase:changes";

    private final String host;
    private final int port;
    private final String password;   // nullable: no AUTH
    private final int database;
    private final String channel;

    /** No auth, database 0, default channel. */
    public JedisCacheSyncConfig(String host, int port) {
        this(host, port, null, 0, DEFAULT_CHANNEL);
    }

    /** Authenticated, database 0, default channel. */
    public JedisCacheSyncConfig(String host, int port, String password) {
        this(host, port, password, 0, DEFAULT_CHANNEL);
    }

    public JedisCacheSyncConfig(String host, int port, String password, int database, String channel) {
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("host must not be null/empty");
        }
        if (channel == null || channel.isEmpty()) {
            throw new IllegalArgumentException("channel must not be null/empty");
        }
        this.host     = host;
        this.port     = port;
        this.password = password;
        this.database = database;
        this.channel  = channel;
    }

    /** Returns a copy of these settings with a different channel. */
    public JedisCacheSyncConfig withChannel(String newChannel) {
        return new JedisCacheSyncConfig(host, port, password, database, newChannel);
    }

    public String host()     { return host; }
    public int port()        { return port; }
    /** The AUTH password, or {@code null} when the server has no auth. */
    public String password() { return password; }
    public int database()    { return database; }
    public String channel()  { return channel; }
}
