package br.com.finalcraft.everydatabase.manager.jackson;

import br.com.finalcraft.everydatabase.manager.Ref;
import com.fasterxml.jackson.databind.module.SimpleModule;

/**
 * Jackson module that teaches an {@code ObjectMapper} to read/write {@link Ref} as its key.
 *
 * <p>Register it on the mapper backing your codec (or use {@link RefCodecs#json(Class)}):
 * <pre>{@code
 * ObjectMapper mapper = new ObjectMapper().registerModule(new RefModule());
 * Codec<Player> codec = new JacksonJsonCodec<>(Player.class, mapper);
 * }</pre>
 */
public final class RefModule extends SimpleModule {

    public RefModule() {
        super("EveryDatabaseRefModule");
        addSerializer(Ref.class, new RefSerializer());
        addDeserializer(Ref.class, new RefDeserializer());
    }
}
