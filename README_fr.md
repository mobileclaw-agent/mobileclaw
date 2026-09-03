# MobileClaw

[English](README.md) | [中文文档](README_zh.md) | [Deutsch](README_de.md) | [Español](README_es.md) | [日本語](README_ja.md) | [한국어](README_ko.md) | [Português](README_pt.md) | [Русский](README_ru.md)

Un agent IA qui vit sur un téléphone Android. Il dialogue avec n'importe quelle API compatible OpenAI ou Anthropic, et il dispose de véritables outils : il peut lire l'état de l'appareil, exécuter des commandes shell, atteindre des privilèges élevés via Shizuku, rechercher sur le web, vous notifier et se planifier des réveils.

Il écrit également ses propres fichiers `IDENTITY.md` et `MEMORY.md` — y compris son propre nom. `MobileClaw` n'est que le nom par défaut.

## Compilation

```bash
export JAVA_HOME=/path/to/jdk21          # JDK 17–21
export ANDROID_HOME=/path/to/android-sdk # nécessite platform-35 + build-tools 35
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Ou ouvrez le projet dans Android Studio et cliquez sur Run.

- `minSdk` 26 (Android 8.0), `targetSdk`/`compileSdk` 35.
- Créez un fichier `local.properties` avec `sdk.dir=/path/to/android-sdk` si Studio ne le fait pas.

## Configuration

Ouvrez les **Paramètres** et remplissez trois champs :

| Fournisseur | URL de base | Endpoint appelé |
|---|---|---|
| Compatible OpenAI | `https://api.openai.com/v1` | `POST {base}/chat/completions` |
| Anthropic | `https://api.anthropic.com/v1` | `POST {base}/messages` |

Le chemin OpenAI fonctionne avec OpenRouter, Groq, Together, Ollama, LM Studio et vLLM — pointez l'URL de base vers eux et définissez le nom du modèle. Accordez ensuite la permission de notification et activez **Garder l'agent éveillé** si vous souhaitez un comportement en arrière-plan.

## Outils

**Appareil** — `get_android_version`, `get_build_version`, `get_device_name`, `get_device_model`, `get_system_stats`, `get_time`, `list_installed_apps`

**Actions applicatives** — `open_url`, `launch_app`, `open_app_settings`

**Presse-papiers** — `set_clipboard`, `get_clipboard`

**Connectivité** — `get_connection_method` (wlan / mobile_net / ethernet / vpn, facturé, validé), `is_hotspot_running`

**Alimentation** — `get_battery_info`

**Shell** — `run_cmd` (non privilégié, dans le bac à sable de l'application), `get_shizuku_status`, `connect_shizuku`, `run_shizuku_cmd` (élevé)

**Web** — `web_search` (frontal HTML DuckDuckGo, avec le frontal lite en repli), `web_fetch`

**Notifications** — `check_notification_permission`, `request_notification_permission`, `send_notification`

**Soi** — `read_identity`, `write_identity`, `read_memory`, `append_memory`, `write_memory`, `set_agent_name`

**Planification** — `create_cron`, `list_crons`, `delete_cron`, `set_cron_enabled`

### Shizuku

Shizuku donne à une application ordinaire un shell de niveau ADB (ou root) sans que l'application soit privilégiée. Cela dépend d'un service que l'utilisateur démarre via le débogage sans fil, ADB ou root.

L'agent reçoit exactement ces jetons en retour, volontairement :

- `connect_shizuku` → `shizuku_conned_success` lorsque les commandes privilégiées sont disponibles
- `connect_shizuku` → `shizuku_notreachable` si le service ne fonctionne pas, ou si l'utilisateur a refusé
- `run_shizuku_cmd` → `shizuku not connected` si appelé sans connexion ; rien ne s'exécute

### Auto-planification

`create_cron` prend un intervalle **en minutes** et un prompt que l'agent écrit pour son futur lui-même. À chaque intervalle, ce prompt est délivré en tant qu'`[EVENT]` et l'agent obtient un tour complet.

`interval_minutes = 0` est permis mais se déclenche environ toutes les 10 secondes ; l'outil renvoie un avertissement explicite, et il en va de même pour tout ce qui est en dessous de 5 minutes. Les crons ne se déclenchent que lorsque le service de premier plan est actif.

## Alertes de batterie

Le service vérifie toutes les 5 minutes et réagit également immédiatement aux diffusions de niveau de batterie :

| Niveau | Gravité | Comportement |
|---|---|---|
| 15% | avis | L'agent est informé ; il décide quoi faire |
| 5% | avertissement | L'agent est informé |
| 2%, 1%, 0% | critique | Une notification système haute priorité se déclenche **directement**, et l'agent est informé qu'une notification a déjà été affichée |

Les alertes critiques ne dépendent pas du succès d'un aller-retour API — un avertissement à 1% ne doit pas être perdu parce qu'une requête a expiré.

Chaque seuil se déclenche une fois par cycle de décharge et ne se réarme qu'après que la batterie remonte de 3 points au-dessus, ou que le téléphone est en charge. Les téléphones en charge ne alertent jamais.

## IDENTITY.md et MEMORY.md

Les deux vivent dans le stockage privé de l'application (`/data/data/at.creepervm1000.mobileclaw/files/`) et sont injectés dans chaque prompt système, de sorte qu'une modification prend effet dès le tour suivant. Les Paramètres ont un bouton **Exporter** qui copie les deux dans Téléchargements pour que vous puissiez les lire.

Le prompt système demande à l'agent de les maintenir activement : d'ajouter à la mémoire lorsqu'il apprend quelque chose de durable, de réécrire son identité lorsque sa perception de lui-même change, et que son nom lui appartient. Effacer la conversation ne touche ni l'un ni l'autre fichier — c'est le but.

## Notes et limites

- Aucun wake lock permanent n'est maintenu. C'est délibéré : une application de surveillance de batterie qui épuise la batterie pour la surveiller est autodestructrice. Doze peut donc différer les minuteurs ; exemptez l'application de l'optimisation de batterie si vous avez besoin d'une précision stricte de cinq minutes.
- Le service de premier plan est typé `specialUse`, pas `dataSync`. Sur Android 15, un service `dataSync` est arrêté de force après 6 heures par jour, ce qui tuerait silencieusement l'agent. La distribution Play Store exigerait de justifier `specialUse` auprès de la révision ; le sideloading ne l'exige pas.
- `is_hotspot_running` est au meilleur effort. `isWifiApEnabled` est une API cachée ; lorsque la plateforme la bloque, l'outil revient à sonder les interfaces de partage et rapporte `confident: false`.
- La recherche web scrappe le frontal HTML de DuckDuckGo. Elle peut être limitée en débit ou servir un CAPTCHA ; l'outil le dit plutôt que de renvoyer du silence.
- Les requêtes ne sont pas diffusées en streaming — les réponses apparaissent une fois terminées.
- `QUERY_ALL_PACKAGES` prend en charge `list_installed_apps`. C'est acceptable pour le sideloading ; la distribution Play Store exigerait de le justifier ou de le supprimer.
- Le HTTP en clair est permis, donc `web_fetch` peut atteindre un serveur de modèle local sur `127.0.0.1`, une machine LAN ou une page http-only. À partir de `targetSdk` 28, Android bloque ceux-ci par défaut, ce qui s'est manifesté comme un échec réseau opaque alors que la même URL se chargeait bien dans un navigateur.

## Licence

MIT — voir [LICENSE](LICENSE).
