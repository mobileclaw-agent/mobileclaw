package at.creepervm1000.mobileclaw.tools

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object GetAndroidVersion : AgentTool {
    override val name = "get_android_version"
    override val description =
        "Get the Android OS version of this device: marketing release (e.g. \"14\"), API level, " +
            "codename, security patch level and the underlying base OS."
    override val schema = NO_ARGS

    override suspend fun run(args: JsonObject, ctx: ToolContext): String = ok {
        put("release", Build.VERSION.RELEASE ?: "unknown")
        put("sdk_int", Build.VERSION.SDK_INT)
        put("codename", Build.VERSION.CODENAME)
        put("security_patch", Build.VERSION.SECURITY_PATCH ?: "unknown")
        put("base_os", Build.VERSION.BASE_OS.ifBlank { "unknown" })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            put("release_or_codename", Build.VERSION.RELEASE_OR_CODENAME)
        }
    }
}

object GetBuildVersion : AgentTool {
    override val name = "get_build_version"
    override val description =
        "Get the ROM/firmware build identifiers of this device: build display ID, build ID, " +
            "fingerprint, incremental version, build type/tags and build timestamp. Use this to " +
            "tell exactly which firmware image the phone is running."
    override val schema = NO_ARGS

    override suspend fun run(args: JsonObject, ctx: ToolContext): String = ok {
        put("display", Build.DISPLAY)
        put("id", Build.ID)
        put("fingerprint", Build.FINGERPRINT)
        put("incremental", Build.VERSION.INCREMENTAL)
        put("type", Build.TYPE)
        put("tags", Build.TAGS)
        put("bootloader", Build.BOOTLOADER)
        put("host", Build.HOST)
        put("user", Build.USER)
        put(
            "build_time",
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date(Build.TIME)),
        )
    }
}

object GetDeviceName : AgentTool {
    override val name = "get_device_name"
    override val description =
        "Get the human-readable name of this device — the name the user gave it (as shown for " +
            "Bluetooth/hotspot), plus the product and marketing names."
    override val schema = NO_ARGS

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val resolver = ctx.app.contentResolver
        val globalName = runCatching { Settings.Global.getString(resolver, "device_name") }.getOrNull()
        val bluetoothName = runCatching { Settings.Secure.getString(resolver, "bluetooth_name") }.getOrNull()

        return ok {
            put("device_name", globalName ?: bluetoothName ?: Build.MODEL)
            put("user_set_name", globalName ?: bluetoothName ?: "not set")
            put("product", Build.PRODUCT)
            put("device_codename", Build.DEVICE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                put("marketing_name", Build.MODEL)
            }
        }
    }
}

object GetDeviceModel : AgentTool {
    override val name = "get_device_model"
    override val description =
        "Get the hardware model of this device: model number, manufacturer, brand, board, " +
            "chipset (SoC) and supported CPU ABIs."
    override val schema = NO_ARGS

    override suspend fun run(args: JsonObject, ctx: ToolContext): String = ok {
        put("model", Build.MODEL)
        put("manufacturer", Build.MANUFACTURER)
        put("brand", Build.BRAND)
        put("device", Build.DEVICE)
        put("product", Build.PRODUCT)
        put("board", Build.BOARD)
        put("hardware", Build.HARDWARE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            put("soc_manufacturer", Build.SOC_MANUFACTURER)
            put("soc_model", Build.SOC_MODEL)
        }
        put("supported_abis", buildJsonArray { Build.SUPPORTED_ABIS.forEach { add(it) } })
        put("is_emulator", Build.FINGERPRINT.contains("generic") || Build.PRODUCT.contains("sdk"))
    }
}

object GetSystemStats : AgentTool {
    override val name = "get_system_stats"
    override val description =
        "Get live system resource usage: RAM total/available/low-memory state, internal storage " +
            "total/free, CPU core count and how long since boot (including time asleep)."
    override val schema = NO_ARGS

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val activityManager = ctx.app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val stat = StatFs(Environment.getDataDirectory().path)
        val totalBytes = stat.blockCountLong * stat.blockSizeLong
        val freeBytes = stat.availableBlocksLong * stat.blockSizeLong

        val uptimeMs = SystemClock.elapsedRealtime()

        return ok {
            put("ram_total_mb", memInfo.totalMem / 1024 / 1024)
            put("ram_available_mb", memInfo.availMem / 1024 / 1024)
            put("ram_low_memory", memInfo.lowMemory)
            put("storage_total_gb", String.format(Locale.US, "%.2f", totalBytes / 1e9))
            put("storage_free_gb", String.format(Locale.US, "%.2f", freeBytes / 1e9))
            put("cpu_cores", Runtime.getRuntime().availableProcessors())
            put("uptime_hours", String.format(Locale.US, "%.2f", uptimeMs / 3_600_000.0))
        }
    }
}

object GetTime : AgentTool {
    override val name = "get_time"
    override val description =
        "Get the device's current date, time and timezone. Call this whenever you need to know " +
            "what time it is — you have no other reliable clock."
    override val schema = NO_ARGS

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val now = Date()
        val local = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(now)
        return ok {
            put("local_time", local)
            put("iso8601", iso)
            put("timezone", TimeZone.getDefault().id)
            put("epoch_ms", now.time)
            put("day_of_week", SimpleDateFormat("EEEE", Locale.US).format(now))
        }
    }
}

object ListInstalledApps : AgentTool {
    override val name = "list_installed_apps"
    override val description =
        "List apps installed on this device. Optionally filter by a substring of the app label " +
            "or package name. System apps are excluded unless include_system is true."
    override val schema = objectSchema {
        string("filter", "Case-insensitive substring to match against app name or package.")
        boolean("include_system", "Include system/pre-installed apps. Default false.")
        integer("limit", "Maximum apps to return. Default 50, max 300.")
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): String {
        val filter = args.str("filter")?.lowercase()
        val includeSystem = args.bool("include_system", false)
        val limit = args.int("limit", 50).coerceIn(1, 300)

        val packageManager = ctx.app.packageManager
        val installed = runCatching {
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrElse { return err("Could not query packages: ${it.message}") }

        val matches = installed.asSequence()
            .filter { info ->
                includeSystem || (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            }
            .map { info -> info to packageManager.getApplicationLabel(info).toString() }
            .filter { (info, label) ->
                filter == null ||
                    label.lowercase().contains(filter) ||
                    info.packageName.lowercase().contains(filter)
            }
            .sortedBy { (_, label) -> label.lowercase() }
            .toList()

        return ok {
            put("total_matched", matches.size)
            put("shown", minOf(matches.size, limit))
            put("apps", buildJsonArray {
                matches.take(limit).forEach { (info, label) ->
                    addJsonObject {
                        put("label", label)
                        put("package", info.packageName)
                        put("system", (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                        put("enabled", info.enabled)
                    }
                }
            })
        }
    }
}
