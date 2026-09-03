# MobileClaw

[English](README.md) | [中文文档](README_zh.md) | [Français](README_fr.md) | [Deutsch](README_de.md) | [日本語](README_ja.md) | [한국어](README_ko.md) | [Português](README_pt.md) | [Русский](README_ru.md)

Un agente de IA que vive en un teléfono Android. Habla con cualquier API compatible con OpenAI o Anthropic, y tiene herramientas reales: puede leer el estado del dispositivo, ejecutar comandos de shell, alcanzar privilegios elevados a través de Shizuku, buscar en la web, notificarte y programarse para despertarse.

También escribe sus propios archivos `IDENTITY.md` y `MEMORY.md` — incluyendo su propio nombre. `MobileClaw` es solo el nombre predeterminado.

## Compilación

```bash
export JAVA_HOME=/path/to/jdk21          # JDK 17–21
export ANDROID_HOME=/path/to/android-sdk # necesita platform-35 + build-tools 35
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

O abre el proyecto en Android Studio y pulsa Run.

- `minSdk` 26 (Android 8.0), `targetSdk`/`compileSdk` 35.
- Crea `local.properties` con `sdk.dir=/path/to/android-sdk` si Studio no lo hace.

## Configuración

Abre **Ajustes** y rellena tres campos:

| Proveedor | URL base | Endpoint llamado |
|---|---|---|
| Compatible con OpenAI | `https://api.openai.com/v1` | `POST {base}/chat/completions` |
| Anthropic | `https://api.anthropic.com/v1` | `POST {base}/messages` |

La ruta OpenAI funciona con OpenRouter, Groq, Together, Ollama, LM Studio y vLLM — apunta la URL base hacia ellos y establece el nombre del modelo. Luego concede el permiso de notificaciones y activa **Mantener despierto el agente** si quieres comportamiento en segundo plano.

## Herramientas

**Dispositivo** — `get_android_version`, `get_build_version`, `get_device_name`, `get_device_model`, `get_system_stats`, `get_time`, `list_installed_apps`

**Acciones de app** — `open_url`, `launch_app`, `open_app_settings`

**Portapapeles** — `set_clipboard`, `get_clipboard`

**Conectividad** — `get_connection_method` (wlan / mobile_net / ethernet / vpn, con tarifa medida, validado), `is_hotspot_running`

**Batería** — `get_battery_info`

**Shell** — `run_cmd` (sin privilegios, dentro del sandbox de la app), `get_shizuku_status`, `connect_shizuku`, `run_shizuku_cmd` (elevado)

**Web** — `web_search` (frontend HTML de DuckDuckGo, con el frontend lite como respaldo), `web_fetch`

**Notificaciones** — `check_notification_permission`, `request_notification_permission`, `send_notification`

**Sí mismo** — `read_identity`, `write_identity`, `read_memory`, `append_memory`, `write_memory`, `set_agent_name`

**Programación** — `create_cron`, `list_crons`, `delete_cron`, `set_cron_enabled`

### Shizuku

Shizuku le da a una app ordinaria un shell de nivel ADB (o root) sin que la app tenga privilegios. Depende de un servicio que el usuario inicia mediante depuración inalámbrica, ADB o root.

El agente recibe exactamente estos tokens de vuelta, a propósito:

- `connect_shizuku` → `shizuku_conned_success` cuando los comandos privilegiados están disponibles
- `connect_shizuku` → `shizuku_notreachable` si el servicio no está en ejecución, o el usuario lo denegó
- `run_shizuku_cmd` → `shizuku not connected` si se llama sin conexión; no se ejecuta nada

### Autoprogramación

`create_cron` toma un intervalo **en minutos** y un prompt que el agente escribe para su yo futuro. En cada intervalo, ese prompt se entrega como un `[EVENT]` y el agente obtiene un turno completo.

`interval_minutes = 0` está permitido pero se dispara aproximadamente cada 10 segundos; la herramienta devuelve una advertencia explícita, y también lo hace cualquier valor por debajo de 5 minutos. Los crons solo se disparan mientras el servicio en primer plano está en ejecución.

## Alertas de batería

El servicio comprueba cada 5 minutos, y también reacciona inmediatamente a las difusiones de nivel de batería:

| Nivel | Gravedad | Comportamiento |
|---|---|---|
| 15% | aviso | Se informa al agente; decide qué hacer |
| 5% | advertencia | Se informa al agente |
| 2%, 1%, 0% | crítico | Una notificación del sistema de alta prioridad se dispara **directamente**, y se le dice al agente que ya se mostró una notificación |

Las alertas críticas no dependen de que un viaje de ida y vuelta a la API tenga éxito — una advertencia al 1% no debería perderse porque una solicitud expirara.

Cada umbral se dispara una vez por ciclo de descarga y solo se rearma después de que la batería suba 3 puntos por encima, o el teléfono se ponga a cargar. Los teléfonos cargando nunca alertan.

## IDENTITY.md y MEMORY.md

Ambos viven en el almacenamiento privado de la app (`/data/data/at.creepervm1000.mobileclaw/files/`) y se inyectan en cada prompt del sistema, así que una edición surte efecto en el siguiente turno. Ajustes tiene un botón **Exportar** que copia ambos a Descargas para que puedas leerlos.

El prompt del sistema le dice al agente que los mantenga activamente: añadir a la memoria cuando aprenda algo duradero, reescribir su identidad cuando cambie su sentido de sí mismo, y que su nombre es suyo para elegir. Borrar la conversación no toca ninguno de los dos archivos — ese es el punto de ellos.

## Notas y límites

- No se mantiene ningún wake lock permanente. Es deliberado: una app que monitoriza la batería y la agota para monitorizarla se derrota a sí misma. Por lo tanto, Doze puede retrasar los temporizadores; exime a la app de la optimización de batería si necesitas precisión estricta de cinco minutos.
- El servicio en primer plano está tipado como `specialUse`, no `dataSync`. En Android 15, un servicio `dataSync` se detiene a la fuerza después de 6 horas por día, lo que mataría al agente en silencio. La distribución en Play Store requeriría justificar `specialUse` ante la revisión; la instalación lateral no.
- `is_hotspot_running` es de mejor esfuerzo. `isWifiApEnabled` es una API oculta; cuando la plataforma la bloquea, la herramienta recurre a sondear interfaces de anclaje y reporta `confident: false`.
- La búsqueda web raspa el frontend HTML de DuckDuckGo. Puede recibir limitación de tasa o un CAPTCHA; la herramienta lo dice en lugar de devolver silencio.
- Las solicitudes no se transmiten en streaming — las respuestas aparecen cuando están completas.
- `QUERY_ALL_PACKAGES` respalda `list_installed_apps`. Está bien para instalación lateral; la distribución en Play Store requeriría justificarlo o eliminarlo.
- Se permite HTTP en texto claro, así que `web_fetch` puede alcanzar un servidor de modelos local en `127.0.0.1`, una máquina de la LAN, o una página solo-http. Desde `targetSdk` 28 Android los bloquea por defecto, lo que se manifestó como un fallo de red opaco mientras la misma URL cargaba bien en el navegador.

## Licencia

MIT — ver [LICENSE](LICENSE).
