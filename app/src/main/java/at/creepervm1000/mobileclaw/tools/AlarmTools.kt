package at.creepervm1000.mobileclaw.tools

import android.content.Intent
import android.provider.AlarmClock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import java.util.Calendar

private val WEEKDAY_NAMES = mapOf(
    "monday" to Calendar.MONDAY,
    "tuesday" to Calendar.TUESDAY,
    "wednesday" to Calendar.WEDNESDAY,
    "thursday" to Calendar.THURSDAY,
    "friday" to Calendar.FRIDAY,
    "saturday" to Calendar.SATURDAY,
    "sunday" to Calendar.SUNDAY,
)

/**
 * Handing these off to whatever clock app the user has installed — rather than building our
 * own alarm/timer scheduling — means it survives a reboot, rings even if MobileClaw is killed,
 * and shows up in the same UI the user already checks.
 */
private fun launchClockIntent(ctx: ToolContext, intent: Intent, what: String): String {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (intent.resolveActivity(ctx.app.packageManager) == null) {
        return err("No clock app on this device can handle $what.")
    }
    return runCatching {
        ctx.app.startActivity(intent)
        ok { put("launched", true) }
    }.getOrElse {
        err(
            "Could not launch the clock app for $what: ${it.message}. This usually means the " +
                "com.android.alarm.permission.SET_ALARM permission is missing.",
        )
    }
}

object SetAlarm : AgentTool {
    override val name = "set_alarm"
    override val description =
        "Set an alarm in the user's clock app. This hands off to whatever clock app is installed " +
            "— it rings even if MobileClaw is closed or the phone is rebooted — rather than " +
            "MobileClaw tracking the time itself. skip_ui=true (the default) sets it silently; " +
            "skip_ui=false opens the clock app's own alarm screen so the user can review and " +
            "confirm it themselves before it's created."
    override val schema = objectSchema {
        integer("hour", "Hour of the alarm, 0-23 (24-hour clock).", required = true)
        integer("minute", "Minute of the alarm, 0-59.", required = true)
        string("label", "A short label for the alarm, e.g. \"Wake up\".")
        stringArray(
            "days",
            "Weekdays to repeat on, e.g. [\"monday\",\"wednesday\",\"friday\"]. Omit for a one-time alarm.",
            enum = WEEKDAY_NAMES.keys.toList(),
        )
        boolean("skip_ui", "Set it silently without opening the clock app's confirmation screen. Default true.")
        boolean("vibrate", "Whether the alarm should vibrate in addition to ringing. Default true.")
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val hour = args.int("hour", -1)
        val minute = args.int("minute", -1)
        if (hour !in 0..23) return err("hour must be between 0 and 23.")
        if (minute !in 0..59) return err("minute must be between 0 and 59.")

        val dayNames = args.strList("days")
        val unknownDays = dayNames.filter { it.lowercase() !in WEEKDAY_NAMES }
        if (unknownDays.isNotEmpty()) {
            return err("Unrecognised day(s): ${unknownDays.joinToString(", ")}. Use full weekday names.")
        }
        val calendarDays = dayNames.map { WEEKDAY_NAMES.getValue(it.lowercase()) }

        val skipUi = args.bool("skip_ui", true)
        val label = args.str("label")

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
            putExtra(AlarmClock.EXTRA_VIBRATE, args.bool("vibrate", true))
            if (label != null) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            if (calendarDays.isNotEmpty()) {
                putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(calendarDays))
            }
        }

        val result = launchClockIntent(ctx, intent, "setting an alarm")
        if (result.contains("\"launched\":true")) {
            return ok {
                put("launched", true)
                put("hour", hour)
                put("minute", minute)
                if (label != null) put("label", label)
                if (dayNames.isNotEmpty()) put("days", dayNames.joinToString(", "))
                put(
                    "note",
                    if (skipUi) {
                        "Set silently. Not guaranteed to have succeeded on every clock app — ask " +
                            "the user to confirm if it matters, or call skip_ui=false next time."
                    } else {
                        "The clock app's alarm screen was opened for the user to confirm."
                    },
                )
            }
        }
        return result
    }
}

object SetTimer : AgentTool {
    override val name = "set_timer"
    override val description =
        "Start a countdown timer in the user's clock app. Hands off to whatever clock app is " +
            "installed, so it keeps running even if MobileClaw is closed. skip_ui=true (the " +
            "default) starts it silently; skip_ui=false opens the clock app's timer screen first."
    override val schema = objectSchema {
        integer("seconds", "Timer duration in seconds, 1 to 86400 (24 hours) — the range AlarmClock supports.", required = true)
        string("label", "A short label for the timer, e.g. \"Pasta\".")
        boolean("skip_ui", "Start it silently without opening the clock app. Default true.")
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val seconds = args.int("seconds", -1)
        if (seconds !in 1..86_400) return err("seconds must be between 1 and 86400 (24 hours).")

        val skipUi = args.bool("skip_ui", true)
        val label = args.str("label")

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
            if (label != null) putExtra(AlarmClock.EXTRA_MESSAGE, label)
        }

        val result = launchClockIntent(ctx, intent, "starting a timer")
        if (result.contains("\"launched\":true")) {
            return ok {
                put("launched", true)
                put("seconds", seconds)
                if (label != null) put("label", label)
                put(
                    "note",
                    if (skipUi) {
                        "Started silently. Not guaranteed to have succeeded on every clock app — " +
                            "ask the user to confirm if it matters, or call skip_ui=false next time."
                    } else {
                        "The clock app's timer screen was opened for the user to confirm."
                    },
                )
            }
        }
        return result
    }
}
