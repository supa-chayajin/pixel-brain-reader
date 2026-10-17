package cloud.wafflecommons.pixelbrainreader.data.remote

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.io.File

/**
 * SystemReader that delegates to the real one but serves EMPTY user/system/jgit
 * configs from a temp dir, so tests never read the developer machine's gitconfig
 * (init.defaultBranch, commit.gpgsign, lfs filters… must not leak into tests).
 * Install it in @Before with [SystemReader.setInstance] and reset it to null in @After.
 */
internal class IsolatedSystemReader(
    private val delegate: SystemReader,
    private val configDir: File
) : SystemReader() {
    override fun getHostname(): String = delegate.hostname
    override fun getenv(variable: String): String? = delegate.getenv(variable)
    override fun getProperty(key: String): String? = delegate.getProperty(key)
    override fun getCurrentTime(): Long = delegate.currentTime

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getTimezone(whenTime: Long): Int = delegate.getTimezone(whenTime)

    override fun openUserConfig(parent: Config?, fs: FS): FileBasedConfig =
        FileBasedConfig(parent, File(configDir, "user.gitconfig"), fs)

    override fun openSystemConfig(parent: Config?, fs: FS): FileBasedConfig =
        FileBasedConfig(parent, File(configDir, "system.gitconfig"), fs)

    override fun openJGitConfig(parent: Config?, fs: FS): FileBasedConfig =
        FileBasedConfig(parent, File(configDir, "jgit.gitconfig"), fs)
}
