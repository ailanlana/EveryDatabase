package br.com.finalcraft.everydatabase.libby;

import net.byteflux.libby.Library;
import net.byteflux.libby.LibraryManager;
import net.byteflux.libby.classloader.URLClassLoaderHelper;
import net.byteflux.libby.logging.adapters.JDKLogAdapter;

import java.io.File;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * A {@link LibraryManager} that downloads library jars at runtime and injects
 * them into a {@link URLClassLoader} (typically a Bukkit/Bungee plugin's
 * classloader, which is a {@code URLClassLoader} subclass).
 *
 * <p>This is the entry point of the {@code everydatabase-libby} distribution
 * flavor: instead of bundling the heavy runtime dependencies (HikariCP, MongoDB
 * driver, H2, Jackson) inside the consumer's jar, they are fetched from Maven
 * Central on first startup and cached in a local folder. See
 * {@link EveryDatabaseDependencies} for ready-made bundles covering everything
 * {@code everydatabase-core} needs.</p>
 *
 * <p>Typical usage in a plugin's {@code onLoad}:</p>
 * <pre>{@code
 * DependencyManager manager = new DependencyManager(
 *         getName(),            // used only for the log channel name
 *         getDataFolder(),      // root folder; jars land in <root>/<libsFolderName>
 *         "libs",               // cache sub-folder name
 *         getClassLoader());    // the plugin's classloader
 * EveryDatabaseDependencies.loadAll(manager);
 * }</pre>
 *
 * <p>Logging goes through {@code java.util.logging} via {@link JDKLogAdapter},
 * so no logging framework is required on the classpath.</p>
 */
public class DependencyManager extends LibraryManager {

    /** Size of the fixed thread pool used by {@link #loadLibrary(Collection)} for parallel downloads. */
    private static final int DOWNLOAD_THREADS = 3;

    private final URLClassLoaderHelper classLoaderHelper;

    /**
     * Creates a manager that injects libraries into the classloader that loaded
     * this class (usually the right one when the consumer shades or depends on
     * {@code everydatabase-libby} directly).
     *
     * @param name           a display name used in the log channel ({@code DependencyManager_<name>})
     * @param rootFolder     root folder under which downloaded jars are cached
     * @param libsFolderName name of the cache sub-folder created inside {@code rootFolder}
     */
    public DependencyManager(String name, File rootFolder, String libsFolderName) {
        this(name, rootFolder, libsFolderName, DependencyManager.class.getClassLoader());
    }

    /**
     * Creates a manager that injects libraries into the given classloader.
     *
     * @param name           a display name used in the log channel ({@code DependencyManager_<name>})
     * @param rootFolder     root folder under which downloaded jars are cached
     * @param libsFolderName name of the cache sub-folder created inside {@code rootFolder}
     * @param classLoader    target classloader; must be a {@link URLClassLoader}
     *                       (Bukkit/Bungee plugin classloaders are)
     */
    public DependencyManager(String name, File rootFolder, String libsFolderName, ClassLoader classLoader) {
        super(
                new JDKLogAdapter(Logger.getLogger("DependencyManager_" + name)),
                rootFolder.toPath(),
                libsFolderName
        );
        this.classLoaderHelper = new URLClassLoaderHelper((URLClassLoader) classLoader, this);
    }

    /**
     * Adds a jar file to the target classloader's classpath.
     *
     * @param file the jar file to add
     */
    @Override
    protected void addToClasspath(Path file) {
        this.classLoaderHelper.addToClasspath(file);
    }

    /**
     * Loads a set of library jars into the target classpath, downloading the
     * missing ones in parallel (a small fixed pool of {@value #DOWNLOAD_THREADS}
     * threads). The call blocks until every library has been processed.
     *
     * <p>If a provided library has relocations, a relocated jar is created and
     * loaded instead, exactly as {@link #loadLibrary(Library)} does.</p>
     *
     * <p>A failure to load one library is logged and does not prevent the
     * remaining libraries from loading.</p>
     *
     * @param libraries the libraries to load
     * @see #loadLibrary(Library)
     */
    public void loadLibrary(Collection<Library> libraries) {
        Objects.requireNonNull(libraries, "libraries");

        if (libraries.isEmpty()) {
            return;
        }

        CountDownLatch latch = new CountDownLatch(libraries.size());
        ExecutorService executor = Executors.newFixedThreadPool(DOWNLOAD_THREADS);

        for (Library library : libraries) {
            executor.execute(() -> {
                try {
                    loadLibrary(library);
                } catch (Throwable e) {
                    logger.error("Unable to load dependency " + library + ".", e);
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        executor.shutdown();
    }

}
