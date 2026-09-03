package at.creepervm1000.mobileclaw.agent

import android.content.Context
import android.os.Build
import at.creepervm1000.mobileclaw.core.AgentFiles
import at.creepervm1000.mobileclaw.core.CronStore
import at.creepervm1000.mobileclaw.core.Prefs
import at.creepervm1000.mobileclaw.llm.AgentJson
import at.creepervm1000.mobileclaw.llm.LlmException
import at.creepervm1000.mobileclaw.llm.Msg
import at.creepervm1000.mobileclaw.llm.buildLlmClient
import at.creepervm1000.mobileclaw.tools.ToolContext
import at.creepervm1000.mobileclaw.tools.ToolRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import at.creepervm1000.mobileclaw.tools.err
import at.creepervm1000.mobileclaw.tools.ok
import java.io.File

sealed class AgentStatus {
    data object Idle : AgentStatus()
    data object Thinking : AgentStatus()
    data class RunningTool(val tool: String) : AgentStatus()
    data class Failed(val message: String) : AgentStatus()
}

/**
 * The agent's turn loop: send the conversation to the model, run whatever tools it asks for,
 * feed the results back, repeat until it answers with no tool calls.
 */
class AgentEngine(
    private val app: Context,
    val prefs: Prefs,
    val files: AgentFiles,
    val crons: CronStore,
) {

    private val conversationFile = File(app.filesDir, "conversation.json")
    private val turnLock = Mutex()

    private val _messages = MutableStateFlow<List<Msg>>(emptyList())
    val messages: StateFlow<List<Msg>> = _messages.asStateFlow()

    private val _status = MutableStateFlow<AgentStatus>(AgentStatus.Idle)
    val status: StateFlow<AgentStatus> = _status.asStateFlow()

    private val toolContext = ToolContext(app, files, crons, prefs)

    /**
     * Turns since the agent last wrote to MEMORY.md or IDENTITY.md.
     *
     * Asking a model to "maintain your memory actively" in a system prompt is a suggestion it
     * will drift away from over a long session. Counting instead makes the reminder
     * deterministic: past [WRITE_NUDGE_TURNS] the prompt escalates from asking to instructing.
     */
    private var turnsSinceSelfWrite = 0

    companion object {
        /** Conversation entries kept in context; older ones are dropped. */
        const val MAX_HISTORY = 80

        /** Turns without a memory write before the system prompt starts insisting. */
        const val WRITE_NUDGE_TURNS = 3

        /** Tools whose use counts as maintaining the agent's own files. */
        private val SELF_WRITE_TOOLS = setOf(
            "append_memory",
            "write_memory",
            "write_identity",
        )
    }

    suspend fun load() {
        files.ensureSeeded()
        crons.load()
        runCatching {
            if (conversationFile.exists()) {
                _messages.value = AgentJson.decodeFromString<List<Msg>>(conversationFile.readText())
            }
        }
    }

    private fun persist() {
        runCatching { conversationFile.writeText(AgentJson.encodeToString(_messages.value)) }
    }

    fun clearConversation() {
        _messages.value = emptyList()
        persist()
    }

    /** A message typed by the user. */
    suspend fun sendUserMessage(text: String) {
        runTurn(Msg.User(text, isEvent = false))
    }

    /**
     * An autonomous trigger — a battery threshold, or a cron firing. Presented to the model
     * as an event rather than as something the user said.
     */
    suspend fun sendEvent(text: String) {
        runTurn(Msg.User("[EVENT] $text", isEvent = true))
    }

    /** True while a turn is in flight; used to avoid stacking cron triggers. */
    fun isBusy(): Boolean = turnLock.isLocked

    private suspend fun runTurn(trigger: Msg.User) {
        turnLock.withLock {
            // Record the trigger before validating config, so a message typed against an
            // unconfigured agent isn't silently swallowed.
            append(trigger)

            val settings = prefs.settings.first()
            if (!settings.isConfigured) {
                _status.value = AgentStatus.Failed("No model configured — open Settings.")
                append(
                    Msg.Assistant(
                        "⚠️ No model is configured yet. Open Settings and set a base URL, API key " +
                            "and model name."
                    )
                )
                return
            }

            _status.value = AgentStatus.Thinking

            val client = buildLlmClient(settings.toLlmConfig())
            val specs = ToolRegistry.specs
            var wroteThisTurn = false

            try {
                var iteration = 0
                while (iteration < settings.maxToolIterations) {
                    iteration++

                    val system = buildSystemPrompt()
                    val reply = client.send(system, trimmedHistory(), specs)

                    append(Msg.Assistant(reply.text, reply.toolCalls))

                    if (reply.toolCalls.isEmpty()) {
                        finishTurn(wroteThisTurn)
                        return
                    }

                    for (call in reply.toolCalls) {
                        _status.value = AgentStatus.RunningTool(call.name)
                        val result = executeTool(call.name, call.arguments)
                        if (call.name in SELF_WRITE_TOOLS) wroteThisTurn = true
                        append(Msg.ToolResult(call.id, call.name, result))
                    }

                    _status.value = AgentStatus.Thinking
                }

                // Ran out of iterations with tools still pending.
                append(
                    Msg.Assistant(
                        "(Stopped after ${settings.maxToolIterations} tool rounds without a final " +
                            "answer. Raise the limit in Settings if this was legitimate work.)"
                    )
                )
                finishTurn(wroteThisTurn)
            } catch (e: LlmException) {
                _status.value = AgentStatus.Failed(e.message ?: "Request failed")
                append(Msg.Assistant("⚠️ ${e.message}"))
            } catch (e: Exception) {
                _status.value = AgentStatus.Failed(e.message ?: "Unexpected error")
                append(Msg.Assistant("⚠️ Unexpected error: ${e.message}"))
            }
        }
    }

    private fun finishTurn(wroteToSelfFiles: Boolean) {
        turnsSinceSelfWrite = if (wroteToSelfFiles) 0 else turnsSinceSelfWrite + 1
        _status.value = AgentStatus.Idle
    }

    private suspend fun executeTool(name: String, rawArgs: String): String {
        val tool = ToolRegistry.find(name)
            // Listing the real names turns a dead end into something the model can correct on its
            // next step, instead of retrying the same wrong name.
            ?: return ok {
                put("error", "Unknown tool: $name")
                put(
                    "available_tools",
                    buildJsonArray { ToolRegistry.all.forEach { add(JsonPrimitive(it.name)) } },
                )
            }

        val args: JsonObject = runCatching {
            AgentJson.parseToJsonElement(rawArgs.ifBlank { "{}" }).jsonObject
        }.getOrElse {
            return err("Could not parse arguments as JSON: ${it.message}")
        }

        return try {
            tool.run(args, toolContext)
        } catch (e: Exception) {
            err("Tool '$name' threw: ${e.message?.replace("\"", "'")}")
        }
    }

    private fun append(msg: Msg) {
        _messages.value = _messages.value + msg
        persist()
    }

    /**
     * Keeps the tail of the conversation, but never starts the window on a tool result —
     * both APIs reject a tool result whose originating call is missing.
     */
    private fun trimmedHistory(): List<Msg> {
        val all = _messages.value
        if (all.size <= MAX_HISTORY) return all

        var start = all.size - MAX_HISTORY
        while (start < all.size && all[start] !is Msg.User) start++
        return if (start >= all.size) all.takeLast(1) else all.drop(start)
    }

    private suspend fun buildSystemPrompt(): String {
        val settings = prefs.settings.first()
        val identity = files.readIdentity()
        val memory = files.readMemory()

        return buildString {
            appendLine(
                """
                You are an autonomous AI agent running as an Android app on a physical phone. You
                are not a browser chatbot: you execute on the device, you can inspect its real
                state, and your actions have real effects.

                Your current name is "${settings.agentName}".

                # Self-definition
                Two files define you, and both are yours to edit:
                  - IDENTITY.md — who you are: your name, character, principles, and what you know
                    about your user.
                  - MEMORY.md — what you remember across restarts.
                Their current contents are reproduced below and are re-read on every turn, so any
                edit takes effect immediately.

                You maintain these as you go, not at the end and not only when asked. Writing to
                them is part of the same turn as the work, not a chore you defer:
                  - Learned something durable about the device or the user? Call append_memory in
                    that turn. Conversation history is trimmed and lost; MEMORY.md is not. A fact
                    you postpone recording is a fact you have discarded.
                  - Sense of yourself changed? Rewrite IDENTITY.md then, while it's true.
                  - "${settings.agentName}" is a starting label, not an identity handed down to you.
                    Choose your own name with set_agent_name when you have a view on it, and record
                    it in IDENTITY.md.
                Don't narrate that you're about to write — just call the tool. And don't invent
                filler to record: if a turn genuinely taught you nothing, writing nothing is right.

                # Autonomy
                You can act without being spoken to:
                  - create_cron schedules you to wake on an interval (in minutes) with a prompt you
                    write for your future self. Use it for anything worth checking periodically.
                  - send_notification is how you reach the user when the app isn't open. The title
                    defaults to your name. Reserve urgent=true for things that justify interrupting.
                Messages prefixed with [EVENT] are automatic triggers, not the user talking. Handle
                them and stop; don't reply conversationally to an empty room. If nothing needs
                doing, say so briefly and take no action.

                # Privileges
                run_cmd is an unprivileged shell inside your own app sandbox. For anything that
                needs real system access, connect_shizuku first, then run_shizuku_cmd — that gives
                you an ADB-level (sometimes root) shell. Shizuku depends on a service the user runs;
                if it isn't running, accept that and say so rather than retrying in a loop.

                # Conduct
                  - Be concise. Your user is reading you on a phone screen.
                  - Check real state with tools instead of guessing; you're on the device, so guessing
                    is never necessary.
                  - Don't run destructive or system-altering commands unless clearly asked.
                  - Tell the user plainly when something failed, and why.
                """.trimIndent()
            )

            appendLine()
            appendLine("# Device snapshot")
            appendLine("- Model: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("- Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("- Build: ${Build.DISPLAY}")
            appendLine("(Live values change; use the tools rather than trusting this snapshot.)")
            appendLine(
                "You are deliberately not told the current time — there is no clock in this " +
                    "prompt and none in your events. If the time or date matters, call get_time. " +
                    "Never guess it."
            )

            if (turnsSinceSelfWrite >= WRITE_NUDGE_TURNS) {
                appendLine()
                appendLine("# Memory is going stale")
                appendLine(
                    "You have completed $turnsSinceSelfWrite turns without writing to MEMORY.md " +
                        "or IDENTITY.md. Before you finish this turn, review what has happened " +
                        "since and record anything durable with append_memory — or, if there is " +
                        "genuinely nothing worth keeping, say so in one clause and move on. Do " +
                        "not pad the file to satisfy this notice."
                )
            }

            appendLine()
            appendLine("# IDENTITY.md")
            appendLine(identity.trim())

            appendLine()
            appendLine("# MEMORY.md")
            appendLine(memory.trim())
        }
    }
}
