package br.com.finalcraft.everydatabase.libby.util;

import net.byteflux.libby.Library;

import java.util.regex.Pattern;

/**
 * Tiny factory that turns Maven-style coordinate strings into Libby
 * {@link Library} instances, so dependency lists can be written as plain
 * {@code "group:artifact:version"} strings instead of verbose builder chains.
 */
public final class LibraryFactory {

    private LibraryFactory() {
        // Static utility class.
    }

    /**
     * Parses {@code "groupId:artifactId:version"} into a {@link Library}.
     *
     * <p>An optional fourth segment is treated as the Base64-encoded SHA-256
     * checksum of the jar: {@code "groupId:artifactId:version:checksum"}.</p>
     *
     * @param coordinates the coordinate string to parse
     * @return the parsed {@link Library}
     * @throws IllegalArgumentException if the string has fewer than three segments
     */
    public static Library of(String coordinates) {
        String[] parts = coordinates.split(Pattern.quote(":"));
        if (parts.length < 3) {
            throw new IllegalArgumentException(
                    "Expected 'groupId:artifactId:version[:checksum]' but got: " + coordinates);
        }

        Library.Builder builder = Library.builder()
                .groupId(parts[0])
                .artifactId(parts[1])
                .version(parts[2]);

        if (parts.length > 3) {
            builder.checksum(parts[3]);
        }

        return builder.build();
    }

}
