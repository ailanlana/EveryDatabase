package br.com.finalcraft.everydatabase.manager.sync.jedis;

import org.junit.jupiter.api.DisplayName;

/**
 * The Jedis cache-sync contract against <b>Valkey</b> (the BSD Redis fork). Self-skips when the
 * Valkey container is down. Connects to the {@code docker-compose} valkey service on port 39309.
 */
@DisplayName("CacheSync over Jedis - Valkey")
class ValkeyCacheSyncTest extends AbstractJedisCacheSyncTest {

    @Override
    protected int port() {
        return 39309;
    }

    @Override
    protected String serverName() {
        return "Valkey";
    }
}
