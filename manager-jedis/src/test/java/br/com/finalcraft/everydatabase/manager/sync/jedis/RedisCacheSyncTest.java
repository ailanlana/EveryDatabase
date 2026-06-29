package br.com.finalcraft.everydatabase.manager.sync.jedis;

import org.junit.jupiter.api.DisplayName;

/**
 * The Jedis cache-sync contract against upstream <b>Redis</b>. Self-skips when the Redis container is
 * down. Connects to the {@code docker-compose} redis service on port 39310 - proving the same Jedis
 * transport works unchanged against both Redis and Valkey.
 */
@DisplayName("CacheSync over Jedis - Redis")
class RedisCacheSyncTest extends AbstractJedisCacheSyncTest {

    @Override
    protected int port() {
        return 39310;
    }

    @Override
    protected String serverName() {
        return "Redis";
    }
}
