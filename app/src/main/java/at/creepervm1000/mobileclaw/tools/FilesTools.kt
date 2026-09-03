package at.creepervm1000.mobileclaw.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import java.io.File

/**
 * Resolves a model-supplied path against the app's private files directory, rejecting
 * anything that escapes it. This is the jail every file tool runs inside: no absolute paths,
 * no "..", no symlinked targets outside the root.
 */
private fun resolveInAppDir(ctx: ToolContext, rawPath: String): File? {
    val root = runCatching { ctx.app.filesDir.canonicalFile }.getOrNull() ?: return null
    val relative = rawPath.trim().trimStart('/', '\\').replace('\\', '/')
    if (relative.isEmpty()) return root

    val canonical = runCatching { File(root, relative).canonicalFile }.getOrNull() ?: return null
    return if (canonical == root || canonical.path.startsWith(root.path + File.separator)) {
        canonical
    } else {
        null
    }
}

/** Files the agent must manage through their dedicated tools, not delete_file. */
private val PROTECTED_FILES = setOf("IDENTITY.md", "MEMORY.md", "crons.json", "conversation.json")

object ListFiles : AgentTool {
    override val name = "list_files"
    override val description =
        "List files and folders in your own private storage on this phone. This is separate " +
            "from IDENTITY.md and MEMORY.md — it is where you keep working files: notes, drafts, " +
            "downloaded data, anything you want to survive the conversation. Paths are relative " +
            "to your storage root; subfolders are listed recursively."
    override val schema = objectSchema {
        string("path", "Folder to list, relative to your storage root. Default: the root itself.")
        integer("limit", "Maximum entries to return. Default 200, max 1000.")
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val requested = args.str("path").orEmpty()
        val dir = resolveInAppDir(ctx, requested)
            ?: return@withContext err("Path escapes your private storage: \"$requested\"")
        if (!dir.exists()) return@withContext err("No such folder: \"${requested.ifBlank { "/" }}\"")
        if (!dir.isDirectory) return@withContext err("Not a folder: \"$requested\"")

        val limit = args.int("limit", 200).coerceIn(1, 1000)
        val rootPath = ctx.app.filesDir.canonicalPath
        val entries = dir.walkTopDown().filter { it != dir }.take(limit).toList()

        return@withContext ok {
            put("path", requested.ifBlank { "/" })
            put("shown", entries.size)
            put("entries", buildJsonArray {
                entries.forEach { file ->
                    addJsonObject {
                        put("path", file.canonicalPath.removePrefix("$rootPath/"))
                        put("type", if (file.isDirectory) "folder" else "file")
                        if (file.isFile) put("size_bytes", file.length())
                    }
                }
            })
            if (entries.size == limit) {
                put("note", "Stopped at the limit; more entries exist below this point.")
            }
        }
    }
}

object ReadFile : AgentTool {
    override val name = "read_file"
    override val description =
        "Read a text file from your own private storage (see list_files). Returns the raw " +
            "content, truncated to a maximum length. Binary files are refused rather than " +
            "returned as garbage."
    override val schema = objectSchema {
        string("path", "File to read, relative to your storage root.", required = true)
        integer("max_chars", "Maximum characters to return. Default 6000, max 20000.")
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val requested = args.str("path").orEmpty()
        val file = resolveInAppDir(ctx, requested)
            ?: return@withContext err("Path escapes your private storage: \"$requested\"")

        if (!file.exists()) return@withContext err("No such file: \"$requested\"")
        if (!file.isFile) return@withContext err("Not a file: \"$requested\"")
        if (file.length() > 1_000_000) {
            return@withContext err("File is ${file.length() / 1000} KB — too large to read whole. Use run_cmd to inspect it in pieces.")
        }

        val bytes = file.readBytes()
        if (bytes.contains(0)) {
            return@withContext err("This looks like a binary file, not text.")
        }

        val maxChars = args.int("max_chars", 6000).coerceIn(500, 20_000)
        val text = String(bytes, Charsets.UTF_8)

        return@withContext ok {
            put("path", requested)
            put("size_bytes", file.length())
            put("content", text.truncate(maxChars))
        }
    }
}

object WriteFile : AgentTool {
    override val name = "write_file"
    override val description =
        "Write a text file in your own private storage, creating parent folders as needed. " +
            "This is your durable scratch space — conversation history is trimmed and lost, " +
            "but files you write here survive restarts. For IDENTITY.md and MEMORY.md prefer " +
            "the dedicated memory tools, which understand those files."
    override val schema = objectSchema {
        string("path", "File to write, relative to your storage root.", required = true)
        string("content", "The full text to write.", required = true)
        boolean("append", "Append to the file instead of replacing it. Default false.")
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val requested = args.str("path").orEmpty()
        val content = args.str("content")
            ?: return@withContext err("Missing required argument: content")

        if (content.length > 100_000) {
            return@withContext err("Content too large (${content.length} chars). Keep files under 100,000 characters.")
        }

        val file = resolveInAppDir(ctx, requested)
            ?: return@withContext err("Path escapes your private storage: \"$requested\"")

        if (file.exists() && file.isDirectory) {
            return@withContext err("\"$requested\" is a folder, not a file.")
        }

        val append = args.bool("append", false)
        return@withContext runCatching {
            file.parentFile?.mkdirs()
            if (append) file.appendText(content) else file.writeText(content)
            ok {
                put("written", true)
                put("path", requested)
                put("chars", content.length)
                put("appended", append)
                put("size_bytes", file.length())
            }
        }.getOrElse { err("Could not write \"$requested\": ${it.message}") }
    }
}

object DeleteFile : AgentTool {
    override val name = "delete_file"
    override val description =
        "Permanently delete a file or folder from your own private storage. This cannot be " +
            "undone. IDENTITY.md, MEMORY.md, crons.json and conversation.json are refused — " +
            "those are managed by their dedicated tools. Deleting a folder requires " +
            "recursive=true."
    override val schema = objectSchema {
        string("path", "File or folder to delete, relative to your storage root.", required = true)
        boolean("recursive", "Allow deleting a folder with everything inside it. Default false.")
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val requested = args.str("path").orEmpty().trimStart('/', '\\').replace('\\', '/')
        if (requested in PROTECTED_FILES) {
            return@withContext err(
                "\"$requested\" is managed by a dedicated tool — use write_identity/write_memory " +
                    "for the markdown files, delete_cron for schedules, or ask the user to clear " +
                    "the conversation.",
            )
        }

        val file = resolveInAppDir(ctx, requested)
            ?: return@withContext err("Path escapes your private storage: \"$requested\"")

        if (!file.exists()) return@withContext err("No such file: \"$requested\"")

        val recursive = args.bool("recursive", false)
        if (file.isDirectory && file.listFiles()?.isNotEmpty() == true && !recursive) {
            return@withContext err(
                "\"$requested\" is a non-empty folder. Pass recursive=true to delete it and " +
                    "everything inside it.",
            )
        }

        val deleted = if (recursive) file.deleteRecursively() else file.delete()
        return@withContext if (deleted) {
            ok {
                put("deleted", true)
                put("path", requested)
            }
        } else {
            err("Could not delete \"$requested\" — the file system refused (it may be in use).")
        }
    }
}
