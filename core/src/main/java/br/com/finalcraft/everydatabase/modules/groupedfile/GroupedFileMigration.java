package br.com.finalcraft.everydatabase.modules.groupedfile;

import br.com.finalcraft.everydatabase.schema.Migration;
import br.com.finalcraft.everydatabase.schema.MigrationContext;

import java.nio.file.Path;

/**
 * Convenience base class for GroupedFile migrations.
 *
 * <p>Subclasses implement {@link #executeOnStorage(GroupedFileStorage)} to access:
 * <ul>
 *   <li>The {@link GroupedFileStorage} itself - use {@code storage.repository(descriptor)} for
 *       high-level CRUD (recommended; layout-agnostic).</li>
 *   <li>The base directory {@link Path} - use {@code storage.baseDirectory()} for low-level file
 *       work. Remember the layout is <b>key-major</b>: each file is {@code <base>/<key>.<ext>} holding
 *       a map {@code collection -> entity}, not {@code <base>/<collection>/<key>.<ext>}.</li>
 * </ul>
 *
 * <pre>{@code
 * public final class V001_SeedAdmins extends GroupedFileMigration {
 *
 *     public String version()     { return "001"; }
 *     public String description() { return "Seed admin profiles"; }
 *
 *     protected void executeOnStorage(GroupedFileStorage storage) {
 *         storage.repository(PROFILE_DESCRIPTOR).saveAll(ADMINS).join();
 *     }
 * }
 * }</pre>
 */
public abstract class GroupedFileMigration implements Migration {

    @Override
    public final void execute(MigrationContext context) throws Exception {
        GroupedFileStorage storage = context.getNativeClient(GroupedFileStorage.class);
        executeOnStorage(storage);
    }

    /**
     * Performs the migration using the given {@link GroupedFileStorage}.
     *
     * @param storage the storage to migrate
     * @throws Exception if the migration fails
     */
    protected abstract void executeOnStorage(GroupedFileStorage storage) throws Exception;
}
