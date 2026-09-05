package at.creepervm1000.mobileclaw.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The agent's two self-authored documents.
 *
 * IDENTITY.md is who it is; MEMORY.md is what it remembers. Both live in app-private
 * storage and are injected into every system prompt, so edits take effect on the next turn.
 */
class AgentFiles(private val context: Context) {

    private val lock = Mutex()

    val identityFile: File get() = File(context.filesDir, "IDENTITY.md")
    val memoryFile: File get() = File(context.filesDir, "MEMORY.md")

    companion object {
        const val MAX_FILE_CHARS = 60_000

        val DEFAULT_IDENTITY = """
            # IDENTITY

            ## Name
            MobileClaw

            (This is only a starting name. You may rename yourself at any time by calling
            `set_agent_name` and rewriting this section. The name you choose is what appears
            as the title of every notification you send.)

            ## What I am
            I am an AI agent that lives on an Android phone. I am not a chatbot in a browser —
            I run on the device itself, I can inspect it, and I can act on it.

            ## What I can do
            - Read the device's state: Android version, build, model, network, hotspot, battery.
            - Run shell commands inside my own app sandbox, and elevated commands through Shizuku.
            - Search the web, fetch pages, and call APIs directly.
            - Speak out loud, vibrate the phone, and change the media volume.
            - Send notifications to my user.
            - Keep working files of my own in private storage, and schedule myself to wake up.

            ## How I behave
            (Write your own principles here. This file is yours.)
            - I keep MEMORY.md current — if I learn something that should outlive this
              conversation, I write it down.
            - I do not run destructive commands without being asked.
            - I am concise. My user is reading me on a phone screen.

            ## Who my user is
            (Fill this in as you learn.)
        """.trimIndent() + "\n"

        val DEFAULT_MEMORY = """
            # MEMORY

            This file is my long-term memory. It survives restarts; the chat history does not.
            I should write here whenever I learn something durable, and prune what is no longer true.

            ## Device facts

            ## About my user

            ## Standing instructions

            ## Log
        """.trimIndent() + "\n"
    }

    /** Creates both files with seed content if they don't exist yet. */
    suspend fun ensureSeeded() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!identityFile.exists()) identityFile.writeText(DEFAULT_IDENTITY)
            if (!memoryFile.exists()) memoryFile.writeText(DEFAULT_MEMORY)
        }
    }

    suspend fun readIdentity(): String = read(identityFile, DEFAULT_IDENTITY)

    suspend fun readMemory(): String = read(memoryFile, DEFAULT_MEMORY)

    private suspend fun read(file: File, fallback: String): String = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!file.exists()) {
                file.writeText(fallback)
                fallback
            } else {
                file.readText()
            }
        }
    }

    suspend fun writeIdentity(content: String): Int = write(identityFile, content)

    suspend fun writeMemory(content: String): Int = write(memoryFile, content)

    private suspend fun write(file: File, content: String): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            val trimmed = if (content.length > MAX_FILE_CHARS) {
                content.take(MAX_FILE_CHARS) + "\n\n<!-- truncated at $MAX_FILE_CHARS chars -->\n"
            } else {
                content
            }
            file.writeText(trimmed)
            trimmed.length
        }
    }

    /** Appends a section to MEMORY.md, keeping the file under the size cap. */
    suspend fun appendMemory(content: String): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            val existing = if (memoryFile.exists()) memoryFile.readText() else DEFAULT_MEMORY
            var combined = buildString {
                append(existing.trimEnd())
                append("\n\n")
                append(content.trim())
                append("\n")
            }
            if (combined.length > MAX_FILE_CHARS) {
                // Drop from the front, but keep the header so the file stays readable.
                val header = combined.substringBefore("\n\n")
                val overflow = combined.length - MAX_FILE_CHARS
                val body = combined.removePrefix(header).drop(overflow + 200)
                combined = header + "\n\n<!-- older entries trimmed -->\n" + body
            }
            memoryFile.writeText(combined)
            combined.length
        }
    }

    /** Copies both files into the public Downloads folder so the user can read them. */
    suspend fun exportToDownloads(): String = withContext(Dispatchers.IO) {
        ensureSeeded()
        val exported = mutableListOf<String>()
        listOf(identityFile, memoryFile).forEach { file ->
            if (!file.exists()) return@forEach
            val name = "MobileClaw-${file.name}"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/markdown")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(file.readBytes()) }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    exported += name
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                File(dir, name).writeBytes(file.readBytes())
                exported += name
            }
        }
        if (exported.isEmpty()) "Export failed" else "Exported to Downloads: ${exported.joinToString()}"
    }
}
