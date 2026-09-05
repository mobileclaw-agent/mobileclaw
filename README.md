[中文文档](README_zh.md) | [Français](README_fr.md) | [Deutsch](README_de.md) | [Español](README_es.md) | [日本語](README_ja.md) | [한국어](README_ko.md) | [Português](README_pt.md) | [Русский](README_ru.md)

# MobileClaw

An AI agent that lives on an Android phone. It talks to any OpenAI-compatible or
Anthropic-compatible API, and it has real tools: it can read the device's state, run shell
commands, reach for elevated privileges through Shizuku, search the web and call APIs, notify
you or speak out loud, keep its own files, and schedule itself to wake up.

It also writes its own `IDENTITY.md` and `MEMORY.md` — including its own name. `MobileClaw`
is only the default.

## Build

```bash
export JAVA_HOME=/path/to/jdk21          # JDK 17–21
export ANDROID_HOME=/path/to/android-sdk # needs platform-35 + build-tools 35
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio and hit Run.

- `minSdk` 26 (Android 8.0), `targetSdk`/`compileSdk` 35.
- Create `local.properties` with `sdk.dir=/path/to/android-sdk` if Studio doesn't.

## Setup

Open **Settings** and fill in three fields:

| Provider | Base URL | Endpoint called |
|---|---|---|
| OpenAI-compatible | `https://api.openai.com/v1` | `POST {base}/chat/completions` |
| Anthropic | `https://api.anthropic.com/v1` | `POST {base}/messages` |

The OpenAI path works with OpenRouter, Groq, Together, Ollama, LM Studio and vLLM — point the
base URL at them and set the model name. Then grant notification permission and switch on
**Keep the agent awake** if you want background behaviour.

## Tools

**Device** — `get_android_version`, `get_build_version`, `get_device_name`, `get_device_model`,
`get_system_stats`, `get_time`, `list_installed_apps`

**App actions** — `open_url`, `launch_app`, `open_app_settings`

**Clipboard** — `set_clipboard`, `get_clipboard`

**Connectivity** — `get_connection_method` (wlan / mobile_net / ethernet / vpn, metered,
validated), `get_local_ips`, `is_hotspot_running`

**Power** — `get_battery_info`

**Screen & audio** — `get_screen_state`, `get_volumes`, `set_media_volume`, `vibrate`

**Speech** — `speak` (text-to-speech, with language/pitch/rate control)

**Files** — `list_files`, `read_file`, `write_file`, `delete_file` — the agent's own durable
scratch space, jailed to the app's private storage

**Shell** — `run_cmd` (unprivileged, inside the app sandbox), `get_shizuku_status`,
`connect_shizuku`, `run_shizuku_cmd` (elevated)

**Web** — `web_search` (DuckDuckGo HTML frontend, with the lite frontend as fallback),
`web_fetch`, `http_request` (raw HTTP for APIs and other machine-readable endpoints)

**Notifications** — `check_notification_permission`, `request_notification_permission`,
`send_notification`

**Self** — `read_identity`, `write_identity`, `read_memory`, `append_memory`, `write_memory`,
`set_agent_name`

**Scheduling** — `create_cron`, `list_crons`, `delete_cron`, `set_cron_enabled`

### Shizuku

Shizuku gives an ordinary app an ADB-level (or root) shell without the app being privileged. It
depends on a service the user starts via wireless debugging, ADB or root.

The agent gets these exact tokens back, by design:

- `connect_shizuku` → `shizuku_conned_success` when privileged commands are available
- `connect_shizuku` → `shizuku_notreachable` if the service isn't running, or the user denied
- `run_shizuku_cmd` → `shizuku not connected` if called without a connection; nothing runs

### Self-scheduling

`create_cron` takes an interval **in minutes** and a prompt the agent writes for its future
self. Every interval, that prompt is delivered as an `[EVENT]` and the agent gets a full turn.

`interval_minutes = 0` is permitted but fires roughly every 10 seconds; the tool returns an
explicit warning, and so does anything under 5 minutes. Crons only fire while the foreground
service is running.

## Battery alerts

The service checks every 5 minutes, and also reacts immediately to battery-level broadcasts:

| Level | Severity | Behaviour |
|---|---|---|
| 15% | notice | Agent is told; it decides what to do |
| 5% | warning | Agent is told |
| 2%, 1%, 0% | critical | A high-priority system notification fires **directly**, and the agent is told a notification was already shown |

Critical alerts don't depend on an API round-trip succeeding — a 1% warning shouldn't be lost
because a request timed out.

Each threshold fires once per discharge cycle and re-arms only after the battery climbs 3
points clear of it, or the phone goes on charge. Charging phones never alert.

## IDENTITY.md and MEMORY.md

Both live in app-private storage (`/data/data/at.creepervm1000.mobileclaw/files/`) and are injected into
every system prompt, so an edit takes effect on the very next turn. Settings has an **Export**
button that copies both to Downloads so you can read them.

The system prompt tells the agent to maintain them actively: to append to memory when it learns
something durable, to rewrite its identity when its sense of itself changes, and that its name
is its own to choose. Clearing the conversation does not touch either file — that's the point
of them.

## Notes and limits

- No permanent wake lock is held. That's deliberate: a battery-monitoring app that drains the
  battery to watch it is self-defeating. Doze can therefore defer timers; exempt the app from
  battery optimisation if you need strict five-minute accuracy.
- The foreground service is typed `specialUse`, not `dataSync`. On Android 15 a `dataSync`
  service is force-stopped after 6 hours per day, which would silently kill the agent. Play
  Store distribution would require justifying `specialUse` to review; sideloading doesn't.
- `is_hotspot_running` is best-effort. `isWifiApEnabled` is a hidden API; when the platform
  blocks it, the tool falls back to probing tether interfaces and reports `confident: false`.
- Web search scrapes DuckDuckGo's HTML frontend. It can be rate-limited or served a CAPTCHA;
  the tool says so rather than returning silence.
- Requests are not streamed — replies appear when complete.
- `QUERY_ALL_PACKAGES` backs `list_installed_apps`. It's fine for sideloading; Play Store
  distribution would require justifying or dropping it.
- Cleartext HTTP is permitted, so `web_fetch` can reach a local model server on `127.0.0.1`, a
  LAN box, or an http-only page. From `targetSdk` 28 on Android blocks those by default, which
  surfaced as an opaque network failure while the same URL loaded fine in a browser.

## Licence

MIT — see [LICENSE](LICENSE).
