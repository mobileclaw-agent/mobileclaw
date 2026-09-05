package at.creepervm1000.mobileclaw.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/**
 * Talks to any endpoint implementing the OpenAI `/v1/chat/completions` shape:
 * OpenAI, OpenRouter, Groq, Together, Ollama, LM Studio, vLLM, ...
 */
class OpenAiClient(private val config: LlmConfig) : LlmClient {

    override suspend fun send(
        system: String,
        messages: List<Msg>,
        tools: List<ToolSpec>,
    ): LlmReply = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", config.model)
            put("temperature", config.temperature)
            put("max_tokens", config.maxTokens)
            put("messages", buildMessages(system, messages))
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    tools.forEach { spec ->
                        addJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", spec.name)
                                put("description", spec.description)
                                put("parameters", spec.schema)
                            }
                        }
                    }
                })
                put("tool_choice", "auto")
            }
        }

        val builder = Request.Builder()
            .url("${Http.normalizeBase(config.baseUrl)}/chat/completions")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")

        // OpenRouter app attribution
        if (config.baseUrl.contains("openrouter", ignoreCase = true)) {
            builder.addHeader("HTTP-Referer", "https://github.com/mobileclaw-agent/mobileclaw")
            builder.addHeader("X-Title", "MobileClaw")
        }

        val request = builder
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        val raw = try {
            Http.client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw LlmException("HTTP ${response.code}: ${text.take(600)}")
                }
                text
            }
        } catch (e: IOException) {
            throw LlmException("Network error: ${e.message}", e)
        }

        parseReply(raw)
    }

    private fun buildMessages(system: String, messages: List<Msg>): JsonArray = buildJsonArray {
        addJsonObject {
            put("role", "system")
            put("content", system)
        }
        messages.forEach { msg ->
            when (msg) {
                is Msg.User -> addJsonObject {
                    put("role", "user")
                    put("content", msg.text)
                }

                is Msg.Assistant -> addJsonObject {
                    put("role", "assistant")
                    // Some servers reject an assistant turn with neither content nor tool_calls.
                    if (msg.text.isEmpty()) put("content", JsonNull) else put("content", msg.text)
                    if (msg.toolCalls.isNotEmpty()) {
                        put("tool_calls", buildJsonArray {
                            msg.toolCalls.forEach { call ->
                                addJsonObject {
                                    put("id", call.id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", call.name)
                                        put("arguments", call.arguments)
                                    }
                                }
                            }
                        })
                    }
                }

                is Msg.ToolResult -> addJsonObject {
                    put("role", "tool")
                    put("tool_call_id", msg.id)
                    put("name", msg.name)
                    put("content", msg.content)
                }
            }
        }
    }

    /**
     * `content` is normally a string, but some OpenAI-compatible servers send an array of
     * parts ([{type: "text", text: …}]) or null alongside tool calls; both shapes must work.
     */
    private fun contentText(content: JsonElement?): String = when (content) {
        null, is JsonNull -> ""
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.joinToString("") { part ->
            runCatching {
                part.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }.getOrDefault("")
        }
        else -> ""
    }

    private fun parseReply(raw: String): LlmReply {
        val root = try {
            AgentJson.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            throw LlmException("Malformed response: ${raw.take(400)}", e)
        }

        root["error"]?.let { err ->
            val message = err.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: err.toString()
            throw LlmException("API error: $message")
        }

        val message = root["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject
            ?: throw LlmException("No choices in response: ${raw.take(400)}")

        val text = contentText(message["content"])

        val calls = (message["tool_calls"]?.jsonArray ?: JsonArray(emptyList()))
            .mapIndexedNotNull { index, element ->
            val obj = element.jsonObject
            val fn = obj["function"]?.jsonObject ?: return@mapIndexedNotNull null
            ToolCall(
                id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "call_$index",
                name = fn["name"]?.jsonPrimitive?.contentOrNull ?: return@mapIndexedNotNull null,
                arguments = fn["arguments"]?.let { arg ->
                    // Most servers send a JSON string; a few send a bare object.
                    arg.jsonPrimitive.contentOrNull ?: arg.toString()
                } ?: "{}",
            )
        }

        return LlmReply(text, calls)
    }
}

/** Anthropic Messages API (`/v1/messages`). */
class AnthropicClient(private val config: LlmConfig) : LlmClient {

    override suspend fun send(
        system: String,
        messages: List<Msg>,
        tools: List<ToolSpec>,
    ): LlmReply = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", config.model)
            put("max_tokens", config.maxTokens)
            put("temperature", config.temperature)
            put("system", system)
            put("messages", buildMessages(messages))
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    tools.forEach { spec ->
                        addJsonObject {
                            put("name", spec.name)
                            put("description", spec.description)
                            put("input_schema", spec.schema)
                        }
                    }
                })
            }
        }

        val request = Request.Builder()
            .url("${Http.normalizeBase(config.baseUrl)}/messages")
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        val raw = try {
            Http.client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw LlmException("HTTP ${response.code}: ${text.take(600)}")
                }
                text
            }
        } catch (e: IOException) {
            throw LlmException("Network error: ${e.message}", e)
        }

        parseReply(raw)
    }

    /**
     * Anthropic requires tool results to arrive as `user` turns. Consecutive results are
     * merged into a single turn, otherwise the API rejects the sequence.
     */
    private fun buildMessages(messages: List<Msg>): JsonArray = buildJsonArray {
        var index = 0
        while (index < messages.size) {
            when (val msg = messages[index]) {
                is Msg.User -> {
                    addJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            addJsonObject {
                                put("type", "text")
                                put("text", msg.text)
                            }
                        })
                    }
                    index++
                }

                is Msg.Assistant -> {
                    // Anthropic rejects a message whose content array is empty; an assistant
                    // turn with neither text nor tool calls carries nothing, so drop it.
                    if (msg.text.isBlank() && msg.toolCalls.isEmpty()) {
                        index++
                    } else {
                        addJsonObject {
                            put("role", "assistant")
                            put("content", buildJsonArray {
                                if (msg.text.isNotBlank()) {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", msg.text)
                                    }
                                }
                                msg.toolCalls.forEach { call ->
                                    addJsonObject {
                                        put("type", "tool_use")
                                        put("id", call.id)
                                        put("name", call.name)
                                        put("input", parseArgs(call.arguments))
                                    }
                                }
                            })
                        }
                        index++
                    }
                }

                is Msg.ToolResult -> {
                    val batch = mutableListOf<Msg.ToolResult>()
                    while (index < messages.size && messages[index] is Msg.ToolResult) {
                        batch += messages[index] as Msg.ToolResult
                        index++
                    }
                    addJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            batch.forEach { result ->
                                addJsonObject {
                                    put("type", "tool_result")
                                    put("tool_use_id", result.id)
                                    put("content", result.content)
                                }
                            }
                        })
                    }
                }
            }
        }
    }

    private fun parseArgs(arguments: String): JsonObject = try {
        AgentJson.parseToJsonElement(arguments).jsonObject
    } catch (_: Exception) {
        buildJsonObject { }
    }

    private fun parseReply(raw: String): LlmReply {
        val root = try {
            AgentJson.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            throw LlmException("Malformed response: ${raw.take(400)}", e)
        }

        if (root["type"]?.jsonPrimitive?.contentOrNull == "error") {
            val message = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            throw LlmException("API error: ${message ?: raw.take(400)}")
        }

        val content = root["content"]?.jsonArray
            ?: throw LlmException("No content in response: ${raw.take(400)}")

        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()

        content.forEach { element ->
            val block = element.jsonObject
            when (block["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> text.append(block["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                "tool_use" -> calls += ToolCall(
                    id = block["id"]?.jsonPrimitive?.contentOrNull ?: "call_${calls.size}",
                    name = block["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach,
                    arguments = (block["input"] as? JsonObject)?.toString() ?: "{}",
                )
            }
        }

        return LlmReply(text.toString(), calls)
    }
}

fun buildLlmClient(config: LlmConfig): LlmClient = when (config.provider) {
    Provider.OPENAI -> OpenAiClient(config)
    Provider.ANTHROPIC -> AnthropicClient(config)
}
