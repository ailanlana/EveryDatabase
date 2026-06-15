package br.com.finalcraft.everydatabase.manager.jackson;

import br.com.finalcraft.everydatabase.manager.Ref;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/**
 * Serializes a {@link Ref} as <b>just its key</b> - on disk it is indistinguishable from
 * storing the raw key, so there is no embedded entity and no duplication. The referenced
 * type {@code V} is not written; it is recovered from the field declaration on read.
 */
@SuppressWarnings("rawtypes")
public final class RefSerializer extends JsonSerializer<Ref> {

    @Override
    public void serialize(Ref value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null || value.key() == null) {
            gen.writeNull();
            return;
        }
        // Delegate to whatever serializer the key type already has (UUID -> string, Long -> number, ...).
        serializers.defaultSerializeValue(value.key(), gen);
    }
}
