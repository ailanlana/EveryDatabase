package br.com.finalcraft.everydatabase.modules.groupedfile;

import br.com.finalcraft.everydatabase.Storage;
import br.com.finalcraft.everydatabase.modules.AbstractStorageStressTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * 10k-record stress run against the grouped (key-major) file backend. The contract {@code DESCRIPTOR}
 * carries a {@code JacksonJsonCodec}, so the storage resolves to a JSON container - one {@code <uuid>.json}
 * file per player under the base directory. Skip with {@code -PskipStress}.
 *
 * <p>Same residuals model as the contract suites: files land under {@link #RESIDUALS_ROOT} and are kept
 * for inspection unless {@link #CLEAN_TEST_RESIDUALS} is set. Note the run writes ~10k files and the
 * full-scan {@code count}/{@code query} phases re-read all of them (GroupedFile scans are O(total keys)),
 * so it is heavier than the LocalFile stress run by design.
 */
@DisplayName("GroupedFileStorage - stress")
class GroupedFileStorageStressTest extends AbstractStorageStressTest {

    /**
     * Set to {@code true} to delete the residual file tree after the run.
     * When {@code false} (default), the ~10k key files survive under {@link #RESIDUALS_ROOT}.
     */
    static final boolean CLEAN_TEST_RESIDUALS = false;

    static final Path RESIDUALS_ROOT = Path.of("build/test-residuals/GroupedFileStorageStress");

    @Override
    protected Storage createStorage(String testMethodName) {
        Path tempDir = RESIDUALS_ROOT.resolve(testMethodName);
        deleteQuietly(tempDir);
        tempDir.toFile().mkdirs();
        return new GroupedFileStorage(new GroupedFileConfig(tempDir));
    }

    @AfterAll
    static void handleResiduals() {
        if (!CLEAN_TEST_RESIDUALS) {
            System.out.println("[GroupedFileStorageStressTest] CLEAN_TEST_RESIDUALS=false - keeping files for inspection:");
            System.out.println("  -> " + RESIDUALS_ROOT.toAbsolutePath());
        } else {
            deleteQuietly(RESIDUALS_ROOT);
        }
    }

    private static void deleteQuietly(Path path) {
        if (!Files.exists(path)) return;
        try {
            Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }
}
