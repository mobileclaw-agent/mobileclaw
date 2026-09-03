# MobileClaw

[English](README.md) | [中文文档](README_zh.md) | [Français](README_fr.md) | [Español](README_es.md) | [日本語](README_ja.md) | [한국어](README_ko.md) | [Português](README_pt.md) | [Русский](README_ru.md)

Ein KI-Agent, der auf einem Android-Telefon lebt. Er spricht mit jeder OpenAI-kompatiblen oder Anthropic-kompatiblen API und hat echte Werkzeuge: Er kann den Gerätestatus lesen, Shell-Befehle ausführen, über Shizuku erhöhte Rechte erreichen, das Web durchsuchen, dich benachrichtigen und sich selbst aufwecken.

Er schreibt auch seine eigene `IDENTITY.md` und `MEMORY.md` — einschließlich seines eigenen Namens. `MobileClaw` ist nur der Standardname.

## Build

```bash
export JAVA_HOME=/path/to/jdk21          # JDK 17–21
export ANDROID_HOME=/path/to/android-sdk # braucht platform-35 + build-tools 35
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Oder öffne das Projekt in Android Studio und drücke Run.

- `minSdk` 26 (Android 8.0), `targetSdk`/`compileSdk` 35.
- Erstelle `local.properties` mit `sdk.dir=/path/to/android-sdk`, falls Studio es nicht tut.

## Einrichtung

Öffne die **Einstellungen** und fülle drei Felder aus:

| Anbieter | Basis-URL | Aufgerufener Endpoint |
|---|---|---|
| OpenAI-kompatibel | `https://api.openai.com/v1` | `POST {base}/chat/completions` |
| Anthropic | `https://api.anthropic.com/v1` | `POST {base}/messages` |

Der OpenAI-Pfad funktioniert mit OpenRouter, Groq, Together, Ollama, LM Studio und vLLM — richte die Basis-URL darauf und setze den Modellnamen. Erteile dann die Benachrichtigungsberechtigung und schalte **Agent wach halten** ein, wenn du Hintergrundverhalten willst.

## Werkzeuge

**Gerät** — `get_android_version`, `get_build_version`, `get_device_name`, `get_device_model`, `get_system_stats`, `get_time`, `list_installed_apps`

**App-Aktionen** — `open_url`, `launch_app`, `open_app_settings`

**Zwischenablage** — `set_clipboard`, `get_clipboard`

**Konnektivität** — `get_connection_method` (wlan / mobile_net / ethernet / vpn, getaktet, validiert), `is_hotspot_running`

**Energie** — `get_battery_info`

**Shell** — `run_cmd` (unprivilegiert, in der App-Sandbox), `get_shizuku_status`, `connect_shizuku`, `run_shizuku_cmd` (erhöht)

**Web** — `web_search` (DuckDuckGo-HTML-Frontend, mit dem Lite-Frontend als Rückfallebene), `web_fetch`

**Benachrichtigungen** — `check_notification_permission`, `request_notification_permission`, `send_notification`

**Selbst** — `read_identity`, `write_identity`, `read_memory`, `append_memory`, `write_memory`, `set_agent_name`

**Planung** — `create_cron`, `list_crons`, `delete_cron`, `set_cron_enabled`

### Shizuku

Shizuku gibt einer gewöhnlichen App eine ADB-level- (oder Root-) Shell, ohne dass die App privilegiert ist. Es hängt von einem Dienst ab, den der Benutzer über drahtloses Debugging, ADB oder Root startet.

Der Agent bekommt genau diese Token zurück, mit Absicht:

- `connect_shizuku` → `shizuku_conned_success`, wenn privilegierte Befehle verfügbar sind
- `connect_shizuku` → `shizuku_notreachable`, wenn der Dienst nicht läuft oder der Benutzer abgelehnt hat
- `run_shizuku_cmd` → `shizuku not connected` bei Aufruf ohne Verbindung; nichts wird ausgeführt

### Selbst-Planung

`create_cron` nimmt ein Intervall **in Minuten** und einen Prompt, den der Agent für sein zukünftiges Ich schreibt. Bei jedem Intervall wird dieser Prompt als `[EVENT]` zugestellt und der Agent bekommt eine volle Runde.

`interval_minutes = 0` ist erlaubt, feuert aber ungefähr alle 10 Sekunden; das Werkzeug gibt eine explizite Warnung zurück, und alles unter 5 Minuten ebenso. Crons feuern nur, während der Vordergrunddienst läuft.

## Batterie-Warnungen

Der Dienst prüft alle 5 Minuten und reagiert auch sofort auf Batteriestand-Broadcasts:

| Level | Schwere | Verhalten |
|---|---|---|
| 15% | Hinweis | Der Agent wird informiert; er entscheidet, was zu tun ist |
| 5% | Warnung | Der Agent wird informiert |
| 2%, 1%, 0% | kritisch | Eine hochprioritäre Systembenachrichtigung wird **direkt** ausgelöst, und dem Agenten wird gesagt, dass bereits eine Benachrichtigung gezeigt wurde |

Kritische Warnungen hängen nicht vom Erfolg eines API-Rundgangs ab — eine 1%-Warnung sollte nicht verloren gehen, weil eine Anfrage abgelaufen ist.

Jeder Schwellenwert feuert einmal pro Entladezyklus und wird erst wieder scharf geschaltet, nachdem die Batterie 3 Punkte darüber geklettert ist oder das Telefon lädt. Ladende Telefone warnen nie.

## IDENTITY.md und MEMORY.md

Beide leben im privaten App-Speicher (`/data/data/at.creepervm1000.mobileclaw/files/`) und werden in jeden Systemprompt injiziert, sodass eine Änderung schon in der nächsten Runde wirkt. Die Einstellungen haben eine **Exportieren**-Schaltfläche, die beide in die Downloads kopiert, damit du sie lesen kannst.

Der Systemprompt sagt dem Agenten, sie aktiv zu pflegen: an den Speicher anzuhängen, wenn er etwas Dauerhaftes lernt, seine Identität umzuschreiben, wenn sich sein Selbstbild ändert, und dass sein Name ihm selbst gehört. Das Löschen der Unterhaltung berührt keine der beiden Dateien — das ist ihr Sinn.

## Hinweise und Grenzen

- Es wird kein permanenter Wake-Lock gehalten. Das ist Absicht: Eine Batterieüberwachungs-App, die die Batterie leert, um sie zu überwachen, ist selbstzerstörerisch. Doze kann daher Timer verzögern; befreie die App von der Batterieoptimierung, wenn du strikte Fünf-Minuten-Genauigkeit brauchst.
- Der Vordergrunddienst ist als `specialUse` getippt, nicht `dataSync`. Auf Android 15 wird ein `dataSync`-Dienst nach 6 Stunden pro Tag zwangsgestoppt, was den Agenten still töten würde. Play-Store-Vertrieb würde erfordern, `specialUse` gegenüber der Prüfung zu rechtfertigen; Sideloading nicht.
- `is_hotspot_running` ist nach bestem Bemühen. `isWifiApEnabled` ist eine versteckte API; wenn die Plattform sie blockiert, fällt das Werkzeug auf das Abfragen von Tethering-Schnittstellen zurück und meldet `confident: false`.
- Die Websuche scrappt DuckDuckGos HTML-Frontend. Sie kann ratenlimitiert sein oder ein CAPTCHA serviert bekommen; das Werkzeug sagt es, statt Stille zurückzugeben.
- Anfragen werden nicht gestreamt — Antworten erscheinen, wenn sie vollständig sind.
- `QUERY_ALL_PACKAGES` stützt `list_installed_apps`. Für Sideloading ist es in Ordnung; Play-Store-Vertrieb würde erfordern, es zu rechtfertigen oder fallen zu lassen.
- Klartext-HTTP ist erlaubt, daher kann `web_fetch` einen lokalen Modellserver auf `127.0.0.1`, eine LAN-Kiste oder eine http-only-Seite erreichen. Ab `targetSdk` 28 blockiert Android diese standardmäßig, was sich als undurchsichtiger Netzwerkfehler äußerte, während dieselbe URL im Browser fein lud.

## Lizenz

MIT — siehe [LICENSE](LICENSE).
