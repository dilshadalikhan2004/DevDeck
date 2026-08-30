package com.devdeck.app.voice

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

object VoskModelStore {
    private const val ASSET_ZIP = "vosk/vosk-model-small-en-us-0.15.zip"
    private const val MARKER = "conf/model.conf"

    fun modelDir(context: Context): File = File(context.filesDir, "vosk/en-us")

    fun isReady(context: Context): Boolean = File(modelDir(context), MARKER).isFile

    fun installFromAssets(context: Context): File {
        val dest = modelDir(context)
        if (isReady(context)) return dest
        dest.parentFile?.mkdirs()
        val tmp = File(context.cacheDir, "vosk-en-us-unpack")
        if (tmp.exists()) tmp.deleteRecursively()
        tmp.mkdirs()
        context.assets.open(ASSET_ZIP).use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val outFile = File(tmp, name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        val unpackedRoot = tmp.walkTopDown().firstOrNull { File(it, MARKER).isFile }
            ?: throw IllegalStateException("Vosk zip did not contain conf/model.conf")
        if (dest.exists()) dest.deleteRecursively()
        dest.parentFile?.mkdirs()
        if (!unpackedRoot.renameTo(dest)) {
            unpackedRoot.copyRecursively(dest, overwrite = true)
            unpackedRoot.deleteRecursively()
        }
        tmp.deleteRecursively()
        if (!isReady(context)) {
            throw IllegalStateException("Voice model failed to unpack onto the device.")
        }
        return dest
    }
}
