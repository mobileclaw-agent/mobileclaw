package at.creepervm1000.mobileclaw.tools

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object GetScreenState : AgentTool {
    override val name = "get_screen_state"
    override val description =
        "Whether the screen is on and whether the device is locked. Useful before doing " +
            "something the user can only see or respond to on-screen (a dialog, a notification, " +
            "an app launch), or to decide whether they are likely holding the phone right now."
    override val schema = NO_ARGS

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val power = ctx.app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = ctx.app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        return ok {
            put("screen_on", power.isInteractive)
            put("locked", keyguard.isKeyguardLocked)
            put("secure_lock", keyguard.isKeyguardSecure)
            put("user_present", power.isInteractive && !keyguard.isKeyguardLocked)
        }
    }
}

object GetVolumes : AgentTool {
    override val name = "get_volumes"
    override val description =
        "Read the current volume levels of the audio streams (media, ring, alarm, system, " +
            "call) plus the ringer mode (normal / vibrate / silent). Read-only; use " +
            "set_media_volume to change the media stream."
    override val schema = NO_ARGS

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val audio = ctx.app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        fun stream(name: String, streamType: Int) = buildJsonObject {
            put("current", runCatching { audio.getStreamVolume(streamType) }.getOrDefault(-1))
            put("max", runCatching { audio.getStreamMaxVolume(streamType) }.getOrDefault(-1))
        }

        return ok {
            put("media", stream("media", AudioManager.STREAM_MUSIC))
            put("ring", stream("ring", AudioManager.STREAM_RING))
            put("alarm", stream("alarm", AudioManager.STREAM_ALARM))
            put("system", stream("system", AudioManager.STREAM_SYSTEM))
            put("call", stream("call", AudioManager.STREAM_VOICE_CALL))
            put(
                "ringer_mode",
                when (audio.ringerMode) {
                    AudioManager.RINGER_MODE_NORMAL -> "normal"
                    AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                    AudioManager.RINGER_MODE_SILENT -> "silent"
                    else -> "unknown"
                },
            )
        }
    }
}

object SetMediaVolume : AgentTool {
    override val name = "set_media_volume"
    override val description =
        "Set the media (music/video) volume as a percentage, 0-100. This is the volume of " +
            "playback and TTS speech, not the ringer — Android does not let an ordinary app " +
            "change the ringer, so don't try. Read the current level with get_volumes first " +
            "if you care about being gentle with it."
    override val schema = objectSchema {
        integer("volume_percent", "Media volume from 0 (muted) to 100 (maximum).", required = true)
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val percent = args.int("volume_percent", -1)
        if (percent !in 0..100) {
            return err("volume_percent must be between 0 and 100.")
        }

        val audio = ctx.app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(-1)
        if (max <= 0) return err("Could not read the media volume scale on this device.")

        val target = Math.round(max * percent / 100.0).toInt()
        return runCatching {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            ok {
                put("set", true)
                put("requested_percent", percent)
                put("resulting_volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                put("max_volume", max)
            }
        }.getOrElse { err("Could not set media volume: ${it.message}") }
    }
}

object Vibrate : AgentTool {
    override val name = "vibrate"
    override val description =
        "Make the phone vibrate for a short duration — a silent way to get the user's " +
            "attention when the phone may be in their pocket or face-down. Use sparingly; this " +
            "is a physical poke, not a UI effect. Does not work while the device is in total " +
            "silence mode on some Android versions."
    override val schema = objectSchema {
        integer("duration_ms", "How long to vibrate, in milliseconds. Default 500, max 5000.")
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val duration = args.int("duration_ms", 500).coerceIn(1, 5000)

        val vibrator: Vibrator? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx.app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

        if (vibrator?.hasVibrator() != true) return err("This device has no vibrator (or it is off).")

        return runCatching {
            vibrator.vibrate(
                VibrationEffect.createOneShot(duration.toLong(), VibrationEffect.DEFAULT_AMPLITUDE),
            )
            ok {
                put("vibrated", true)
                put("duration_ms", duration)
            }
        }.getOrElse { err("Could not vibrate: ${it.message}") }
    }
}

/**
 * One TextToSpeech engine per app process. Created lazily on first use; the engine is kept
 * alive because shutting it down would cut off queued speech mid-sentence.
 */
object TtsSpeaker {

    private val lock = Mutex()
    private var engine: TextToSpeech? = null

    suspend fun speak(
        app: Context,
        text: String,
        queue: Boolean,
        languageTag: String?,
        pitch: Double,
        rate: Double,
    ): String = lock.withLock {
        val tts = ensureEngine(app) ?: return@withLock err(
            "The text-to-speech engine did not initialise within 10 seconds. No voice data " +
                "may be installed — suggest the user install a TTS engine (e.g. Google TTS).",
        )

        if (!languageTag.isNullOrBlank()) {
            val result = runCatching { tts.setLanguage(Locale.forLanguageTag(languageTag)) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                return@withLock err("Language \"$languageTag\" is not available on this device's TTS engine.")
            }
        }

        tts.setPitch(pitch.toFloat())
        tts.setSpeechRate(rate.toFloat())

        val mode = if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        val rc = runCatching {
            tts.speak(text, mode, null, "mobileclaw_${System.nanoTime()}")
        }.getOrDefault(TextToSpeech.ERROR)

        if (rc != TextToSpeech.SUCCESS) {
            return@withLock err("The TTS engine refused the speech request (code $rc).")
        }

        ok {
            put("spoken", true)
            put("chars", text.length)
            put("queued_behind_existing", queue)
            put("language", languageTag ?: "device default")
        }
    }

    private suspend fun ensureEngine(app: Context): TextToSpeech? {
        engine?.let { return it }

        val holder = AtomicReference<TextToSpeech?>()
        val initialised = withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { continuation ->
                holder.set(
                    TextToSpeech(app.applicationContext) { status ->
                        if (!continuation.isActive) return@TextToSpeech
                        if (status == TextToSpeech.SUCCESS) {
                            continuation.resume(Unit)
                        } else {
                            runCatching { holder.get()?.shutdown() }
                            continuation.resumeWithException(
                                IllegalStateException("TTS init failed (status=$status)"),
                            )
                        }
                    },
                )
                continuation.invokeOnCancellation { runCatching { holder.get()?.shutdown() } }
            }
        }

        val tts = holder.get()
        if (initialised == null || tts == null) {
            runCatching { tts?.shutdown() }
            return null
        }
        engine = tts
        return tts
    }
}

object Speak : AgentTool {
    override val name = "speak"
    override val description =
        "Say something out loud through the phone's speakers using text-to-speech. The user " +
            "hears this even with the app in the background or the screen off — it is your " +
            "voice, louder than a notification. Keep spoken text short and natural; people " +
            "can't re-hear a paragraph. Speech keeps playing after this returns."
    override val schema = objectSchema {
        string("text", "What to say out loud. Short, natural sentences.", required = true)
        boolean("queue", "Wait for any in-progress speech to finish instead of interrupting it. Default false.")
        string("language", "BCP-47 language tag such as en-US or de-DE. Defaults to the device's language.")
        number("pitch", "Voice pitch, 0.5 (low) to 2.0 (high). Default 1.0.")
        number("rate", "Speaking speed, 0.5 (slow) to 2.0 (fast). Default 1.0.")
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val text = args.str("text")?.takeIf { it.isNotBlank() }
            ?: return err("Missing required argument: text")
        if (text.length > 2000) {
            return err("Speech text is too long (${text.length} chars). Keep it under 2000.")
        }

        return TtsSpeaker.speak(
            app = ctx.app,
            text = text,
            queue = args.bool("queue", false),
            languageTag = args.str("language"),
            pitch = args.double("pitch", 1.0).coerceIn(0.5, 2.0),
            rate = args.double("rate", 1.0).coerceIn(0.5, 2.0),
        )
    }
}
