package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** The vault JSON list files the app rewrites whole from Room (13.4): what Repair's write-back names. */
enum class VaultListFile { HABITS, ROOMS, CHORES }

/** What one of those files holds on disk, read the way its import reads it. */
internal sealed interface ListOnDisk {
    /** Missing or blank: the import leaves the app's copy as it is. */
    data object Absent : ListOnDisk

    /** It parses, with [count] items (a `null` literal holds none). */
    data class Items(val count: Int) : ListOnDisk

    /** It doesn't parse (or couldn't be read): kept aside before anything is written over it. */
    data object Unreadable : ListOnDisk
}

/**
 * The habits' `config.json` and the home's `rooms.json` and `chores.json`, written whole from Room
 * (13.4). A vault JSON file that no longer parses is never overwritten: it is moved aside first
 * ([FileRepository.moveAsideCorrupt], `<name>.corrupt-<time>`), and a write that can't keep it
 * stops with an IOException. Repair's write-back also never replaces a file's items with an app
 * that holds none of them.
 */
internal object VaultListFiles {

    /** [raw] as [parse] (the import's own reader) sees it. Pure. */
    fun classify(raw: String?, parse: (String) -> List<*>?): ListOnDisk {
        if (raw.isNullOrBlank()) return ListOnDisk.Absent
        return try {
            ListOnDisk.Items(parse(raw)?.size ?: 0)
        } catch (e: Exception) {
            ListOnDisk.Unreadable
        }
    }

    /**
     * Whether Repair may replace a file holding [onDisk] with the app's [appItems] items: never an
     * app that holds none over a file that holds some, or may (one that doesn't parse). Pure.
     */
    fun mayWriteBack(onDisk: ListOnDisk, appItems: Int): Boolean =
        appItems > 0 || onDisk == ListOnDisk.Absent || onDisk == ListOnDisk.Items(0)

    /** What [path] holds now. A read that fails counts as unreadable: kept aside, never overwritten. */
    suspend fun read(fileRepository: FileRepository, path: String, parse: (String) -> List<*>?): ListOnDisk {
        val raw = try {
            fileRepository.readFile(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ListOnDisk.Unreadable
        }
        return classify(raw, parse)
    }

    /** A save (every habit, room and chore change): [content] over [path], an unreadable file kept aside first. */
    suspend fun replace(fileRepository: FileRepository, path: String, content: String, parse: (String) -> List<*>?) {
        write(fileRepository, path, content, read(fileRepository, path, parse))
    }

    /**
     * Repair's write-back of one file: as [replace], unless the app holds none of its items
     * ([appItems] 0) while the file holds some or may; then nothing is written or moved, and false.
     */
    suspend fun writeBack(
        fileRepository: FileRepository,
        path: String,
        appItems: Int,
        content: String,
        parse: (String) -> List<*>?
    ): Boolean {
        val onDisk = read(fileRepository, path, parse)
        if (!mayWriteBack(onDisk, appItems)) return false
        write(fileRepository, path, content, onDisk)
        return true
    }

    /**
     * [content] over [path], whose state [onDisk] was just read. A read-only device refuses before
     * anything moves (the same refusal the write itself would give).
     */
    private suspend fun write(fileRepository: FileRepository, path: String, content: String, onDisk: ListOnDisk) {
        VaultWriteGate.check()
        if (onDisk == ListOnDisk.Unreadable && fileRepository.moveAsideCorrupt(path) == null) {
            throw IOException("An unreadable $path couldn't be kept aside; not overwriting it")
        }
        fileRepository.saveFileLocally(path, content)
    }
}
