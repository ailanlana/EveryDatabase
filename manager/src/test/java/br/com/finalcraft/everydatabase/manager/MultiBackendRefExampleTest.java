package br.com.finalcraft.everydatabase.manager;

import br.com.finalcraft.everydatabase.EntityDescriptor;
import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.Storages;
import br.com.finalcraft.everydatabase.manager.cache.CachePolicy;
import br.com.finalcraft.everydatabase.manager.jackson.RefCodecs;
import br.com.finalcraft.everydatabase.manager.testdata.multibackend.Clan;
import br.com.finalcraft.everydatabase.manager.testdata.multibackend.PlayerProfile;
import br.com.finalcraft.everydatabase.manager.testdata.multibackend.Session;
import br.com.finalcraft.everydatabase.manager.testdata.multibackend.Settings;
import br.com.finalcraft.everydatabase.manager.testdata.multibackend.Stats;
import br.com.finalcraft.everydatabase.manager.testdata.multibackend.Wallet;
import br.com.finalcraft.everydatabase.modules.localfile.LocalFileConfig;
import br.com.finalcraft.everydatabase.modules.memory.InMemoryStorage;
import br.com.finalcraft.everydatabase.modules.mongo.MongoConfig;
import br.com.finalcraft.everydatabase.modules.mongo.MongoStorage;
import br.com.finalcraft.everydatabase.modules.sql.SqlConfig;
import br.com.finalcraft.everydatabase.modules.sql.SqlStorage;
import br.com.finalcraft.everydatabase.modules.sql.h2.H2SqlStorage;
import br.com.finalcraft.everydatabase.modules.sql.postgresql.PostgreSqlStorage;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Example: one root entity, six different databases, six different key types.
 *
 * <p>A {@link PlayerProfile} (keyed by {@code UUID}) is stored in one database and holds five
 * references — each with a <b>different key type</b> ({@code String}, {@code Long},
 * {@code Integer}, a composite {@code record}, a wrapper {@code record}) — that resolve through
 * managers backed by <b>different databases</b>. The {@code Ref}/manager layer is backend- and
 * key-type-agnostic: the root entity neither knows nor cares where, or under which key type, its
 * referenced entities live.
 *
 * <p>{@link #a_root_entity_fans_out_across_six_different_databases()} wires the real six backends
 * (MariaDB / PostgreSQL / MongoDB / H2 / LocalFile / InMemory) and <b>self-skips</b> when the three
 * Docker servers are unreachable. {@link #the_same_fan_out_resolves_across_embedded_backends()}
 * runs the identical fan-out on embedded backends only (H2 / LocalFile / InMemory), so the
 * heterogeneous-key round-trips are verified without Docker.
 */
class MultiBackendRefExampleTest {

    private static final String EXAMPLE_DB     = "everydatabase_example";
    private static final String MARIADB_SERVER = "jdbc:mysql://localhost:39306";
    private static final String PG_SERVER      = "jdbc:postgresql://localhost:39307";
    private static final String MONGO_URL      = "mongodb://root:root@localhost:39308";
    private static final String USER = "root";
    private static final String PASS = "root";

    @TempDir
    Path localFileDir;

    private final List<Storage> opened = new ArrayList<>();

    @BeforeEach
    void clearRegistry() {
        Refs.clear();
    }

    @AfterEach
    void tearDown() {
        Refs.clear();
        for (Storage storage : opened) {
            try {
                storage.close().join();
            } catch (Exception ignored) {
                // best-effort
            }
        }
        opened.clear();
    }

    @Test
    void a_root_entity_fans_out_across_six_different_databases() {
        // --- the three embedded backends are always available ---
        InMemoryStorage inMemory = open(Storages.createInMemory());
        H2SqlStorage h2 = open(Storages.createH2(new SqlConfig(
                "jdbc:h2:mem:" + EXAMPLE_DB + "_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "", "")));
        var localFile = open(Storages.createLocalFile(new LocalFileConfig(localFileDir)));

        // --- the three network backends require Docker; skip cleanly if they are down ---
        Assumptions.assumeTrue(networkUp(),
                "MariaDB/PostgreSQL/MongoDB not reachable - run 'docker compose up -d'. "
                + "Skipping the multi-backend example.");

        SqlStorage mariadb = open(Storages.createSQL(new SqlConfig(MARIADB_SERVER + "/" + EXAMPLE_DB, USER, PASS)));
        PostgreSqlStorage postgres = open(Storages.createPostgreSQL(new SqlConfig(PG_SERVER + "/" + EXAMPLE_DB, USER, PASS)));
        MongoStorage mongo = open(Storages.createMongo(new MongoConfig(MONGO_URL, EXAMPLE_DB)));
        Assumptions.assumeTrue(
                mariadb.health().join().isConnected()
                        && postgres.health().join().isConnected()
                        && mongo.health().join().isConnected(),
                "A required database is down - skipping the multi-backend example.");

        // root -> MariaDB, clan -> PostgreSQL, wallet -> MongoDB, stats -> H2,
        // settings -> LocalFile, session -> InMemory
        fanOut(mariadb, postgres, mongo, h2, localFile, inMemory);
    }

    @Test
    void the_same_fan_out_resolves_across_embedded_backends() {
        // No Docker needed: exercise the heterogeneous keys on real embedded persistence -
        // H2 (SQL string keys via toString), LocalFile (sanitized record-key filenames),
        // InMemory (equals/hashCode keys).
        Storage sql = open(Storages.createH2(new SqlConfig(
                "jdbc:h2:mem:" + EXAMPLE_DB + "_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "", "")));
        Storage file = open(Storages.createLocalFile(new LocalFileConfig(localFileDir)));
        Storage mem = open(Storages.createInMemory());

        // profiles + clans + stats -> H2; settings -> LocalFile; wallet + session -> InMemory
        fanOut(sql, sql, mem, sql, file, mem);
    }

    // ------------------------------------------------------------------
    //  The fan-out, shared by both tests (only the backends differ)
    // ------------------------------------------------------------------

    private void fanOut(Storage profilesStore, Storage clansStore, Storage walletsStore,
                        Storage statsStore, Storage settingsStore, Storage sessionsStore) {

        // one manager per entity type, each backed by its given store, each with its own key type
        CachingManager<String, Clan> clans = new CachingManager<>(
                descriptor(String.class, Clan.class, "clans", c -> c.getTag()), clansStore, CachePolicy.always());
        CachingManager<Long, Wallet> wallets = new CachingManager<>(
                descriptor(Long.class, Wallet.class, "wallets", w -> w.getAccountNumber()), walletsStore, CachePolicy.always());
        CachingManager<Integer, Stats> stats = new CachingManager<>(
                descriptor(Integer.class, Stats.class, "stats", s -> s.getId()), statsStore, CachePolicy.always());
        CachingManager<Settings.Key, Settings> settings = new CachingManager<>(
                descriptor(Settings.Key.class, Settings.class, "settings", s -> s.getKey()), settingsStore, CachePolicy.always());
        CachingManager<Session.Id, Session> sessions = new CachingManager<>(
                descriptor(Session.Id.class, Session.class, "sessions", s -> s.getId()), sessionsStore, CachePolicy.always());
        CachingManager<UUID, PlayerProfile> profiles = new CachingManager<>(
                descriptor(UUID.class, PlayerProfile.class, "profiles", p -> p.getUuid()), profilesStore, CachePolicy.always());

        // write each inner entity into its own store, under its own key type
        clans.saveAndCache(new Clan("KNIGHTS", "The Knights")).join();              // String key
        wallets.saveAndCache(new Wallet(100_000_001L, 1_000L)).join();  // Long key
        stats.saveAndCache(new Stats(7, 42, 3)).join();                        // Integer key
        Settings.Key settingsKey = new Settings.Key(UUID.randomUUID(), "ui");
        settings.saveAndCache(new Settings(settingsKey, "en", true)).join();    // composite record key
        Session.Id sessionId = new Session.Id("tok-abc");
        sessions.saveAndCache(new Session(sessionId, "lobby-1")).join();        // wrapper record key

        // the root references all five, each via a different key type
        UUID profileId = UUID.randomUUID();
        profiles.saveAndCache(new PlayerProfile(
            profileId,
            Ref.of("KNIGHTS", Clan.class),
            Ref.of(100_000_001L, Wallet.class),
            Ref.of(7, Stats.class),
            Ref.of(settingsKey, Settings.class),
            Ref.of(sessionId, Session.class)
        )).join();

        // reload the root (force a decode), then resolve every reference across stores/key types
        profiles.evict(profileId);
        PlayerProfile loaded = profiles.resolve(profileId).join().orElseThrow(AssertionError::new);

        assertEquals("The Knights", loaded.getClan().resolve().join().orElseThrow(AssertionError::new).getName());   // String -> Clan
        assertEquals(1_000L, loaded.getWallet().resolve().join().orElseThrow(AssertionError::new).getBalance());     // Long -> Wallet
        assertEquals(42, loaded.getStats().resolve().join().orElseThrow(AssertionError::new).getKills());            // Integer -> Stats
        assertEquals("en", loaded.getSettings().resolve().join().orElseThrow(AssertionError::new).getLanguage());    // record -> Settings
        assertEquals("lobby-1", loaded.getSession().resolve().join().orElseThrow(AssertionError::new).getServer());  // record -> Session

        System.out.println("loaded: " + loaded);
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private <S extends Storage> S open(S storage) {
        storage.init().join();
        opened.add(storage);
        return storage;
    }

    private static <K, T> EntityDescriptor<K, T> descriptor(Class<K> keyType, Class<T> type,
                                                            String collection, Function<T, K> key) {
        return EntityDescriptor.builder(keyType, type)
                .collection(collection)
                .keyExtractor(key)
                .codec(RefCodecs.json(type))
                .build();
    }

    /**
     * Creates the example databases on MariaDB and PostgreSQL (idempotent) and pings MongoDB,
     * returning whether all three network servers are reachable. Uses short timeouts so a down
     * server fails fast rather than hanging the suite.
     */
    private static boolean networkUp() {
        DriverManager.setLoginTimeout(3);
        try {
            try (Connection c = DriverManager.getConnection(MARIADB_SERVER + "/", USER, PASS);
                 Statement st = c.createStatement()) {
                st.execute("CREATE DATABASE IF NOT EXISTS `" + EXAMPLE_DB + "`");
            }
            try (Connection c = DriverManager.getConnection(PG_SERVER + "/postgres", USER, PASS);
                 Statement st = c.createStatement()) {
                boolean exists;
                try (ResultSet rs = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + EXAMPLE_DB + "'")) {
                    exists = rs.next();
                }
                if (!exists) {
                    st.execute("CREATE DATABASE \"" + EXAMPLE_DB + "\"");
                }
            }
        } catch (SQLException e) {
            return false;
        }
        return mongoReachable();
    }

    private static boolean mongoReachable() {
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(MONGO_URL))
                .applyToClusterSettings(b -> b.serverSelectionTimeout(3, TimeUnit.SECONDS))
                .applyToSocketSettings(b -> b.connectTimeout(3, TimeUnit.SECONDS))
                .build();
        try (MongoClient client = MongoClients.create(settings)) {
            client.getDatabase("admin").runCommand(new Document("ping", 1));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
