package com.srctool.sinew.security

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.srctool.sinew.models.PlatformContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.GeneralSecurityException

internal actual fun platformFieldCipher(platform: PlatformContext, keyAlias: String): FieldCipher =
    FieldCipher.create(appDataDirectory().resolve("sinew"), keyAlias)

/**
 * A desktop cipher whose Tink keyset is the file `<keyAlias>.keyset.json` in [directory], readable by its owner only
 * where the file system has POSIX permissions. Anyone who can read that file can decrypt: this protects against
 * casual reading, not against code running as the user.
 */
public fun FieldCipher.Companion.create(directory: Path, keyAlias: String = "sinew.field"): FieldCipher {
    require(keyAlias.isNotBlank()) { "keyAlias must not be blank" }
    val file = directory.resolve("$keyAlias.keyset.json")
    return LayoutFieldCipher { withContext(Dispatchers.IO) { TinkKeys(FileKeyset.open(file)) } }
}

/** The OS's per-user app-data folder: Application Support on macOS, `%APPDATA%` on Windows, XDG elsewhere. */
private fun appDataDirectory(): Path {
    val home = Paths.get(System.getProperty("user.home"))
    val os = System.getProperty("os.name").lowercase()
    return when {
        "mac" in os -> home.resolve("Library/Application Support")
        "win" in os -> System.getenv("APPDATA")?.let { Paths.get(it) } ?: home.resolve("AppData/Roaming")
        else -> System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
            ?: home.resolve(".local/share")
    }
}

/** A keyset in a JSON file, written atomically with owner-only permissions. */
internal class FileKeyset private constructor(private val file: Path, override var handle: KeysetHandle) : TinkKeyset {
    override fun rotate() {
        val next = handle.withNewPrimary()
        write(file, next)
        handle = next
    }

    companion object {
        /**
         * The keyset in [file]; a new one when there's none, or when the file isn't a keyset (its values are lost, as
         * on Android when the master key is). A failure to read the file is thrown, so a passing I/O error never
         * replaces a good key.
         */
        fun open(file: Path): FileKeyset {
            registerAead()                                      // parsing needs the key managers too
            val handle = if (Files.exists(file)) {
                try {
                    parse(String(Files.readAllBytes(file), Charsets.UTF_8))
                } catch (_: GeneralSecurityException) {
                    null
                }
            } else {
                null
            }
            return FileKeyset(file, handle ?: newKeyset().also { write(file, it) })
        }

        private fun parse(json: String): KeysetHandle =
            TinkJsonProtoKeysetFormat.parseKeyset(json, InsecureSecretKeyAccess.get())

        private fun write(file: Path, handle: KeysetHandle) {
            val directory = file.parent
            val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()
            if (posix) {
                Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            } else {
                Files.createDirectories(directory)
            }
            val temp = if (posix) {
                Files.createTempFile(directory, file.fileName.toString(), ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            } else {
                Files.createTempFile(directory, file.fileName.toString(), ".tmp")
            }
            try {
                Files.writeString(temp, TinkJsonProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get()))
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temp)
            }
        }
    }
}
