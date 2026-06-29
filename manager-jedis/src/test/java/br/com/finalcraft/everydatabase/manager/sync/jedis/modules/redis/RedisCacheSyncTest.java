package br.com.finalcraft.everydatabase.manager.sync.jedis.modules.redis;

import br.com.finalcraft.everydatabase.manager.sync.jedis.AbstractJedisCacheSyncTest;
import org.junit.jupiter.api.DisplayName;

/**
 * The Jedis cache-sync contract against upstream <b>Redis</b> (port 39310). Self-skips when Redis is
 * down. Proves the same Jedis transport works unchanged against both Redis and Valkey.
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
