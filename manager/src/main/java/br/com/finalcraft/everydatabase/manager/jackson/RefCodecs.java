package br.com.finalcraft.everydatabase.manager.jackson;

import br.com.finalcraft.everydatabase.codec.JacksonJsonCodec;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Convenience factories for codecs that understand {@link br.com.finalcraft.everydatabase.manager.Ref}
 * fields, so callers don't have to wire the {@link RefModule} onto an {@code ObjectMapper} by hand.
 */
public final class RefCodecs {

    private RefCodecs() {
    }

    /** A fresh compact {@code ObjectMapper} with the {@link RefModule} registered. */
    public static ObjectMapper newMapper() {
        return new ObjectMapper().registerModule(new RefModule());
    }

    /** A compact JSON codec for {@code type} that serializes {@code Ref} fields as their key. */
    public static <T> JacksonJsonCodec<T> json(Class<T> type) {
        return new JacksonJsonCodec<>(type, newMapper());
    }
}
