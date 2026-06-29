package br.com.finalcraft.everydatabase.manager.sync.jedis.modules.valkey;

import br.com.finalcraft.everydatabase.manager.sync.jedis.AbstractJedisCacheSyncTest;
import org.junit.jupiter.api.DisplayName;

/**
 * The Jedis cache-sync contract against <b>Valkey</b> (the BSD Redis fork, port 39309). Self-skips when
 * the Valkey container is down.
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
