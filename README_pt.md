# MobileClaw

[English](README.md) | [中文文档](README_zh.md) | [Français](README_fr.md) | [Deutsch](README_de.md) | [Español](README_es.md) | [日本語](README_ja.md) | [한국어](README_ko.md) | [Русский](README_ru.md)

Um agente de IA que vive num telefone Android. Ele conversa com qualquer API compatível com OpenAI ou Anthropic, e tem ferramentas reais: pode ler o estado do dispositivo, executar comandos de shell, alcançar privilégios elevados através do Shizuku, pesquisar na web, notificá-lo e agendar-se para acordar.

Ele também escreve o seu próprio `IDENTITY.md` e `MEMORY.md` — incluindo o próprio nome. `MobileClaw` é apenas o nome predefinido.

## Compilação

```bash
export JAVA_HOME=/path/to/jdk21          # JDK 17–21
export ANDROID_HOME=/path/to/android-sdk # precisa de platform-35 + build-tools 35
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Ou abra o projeto no Android Studio e clique em Run.

- `minSdk` 26 (Android 8.0), `targetSdk`/`compileSdk` 35.
- Crie `local.properties` com `sdk.dir=/path/to/android-sdk` se o Studio não o fizer.

## Configuração

Abra as **Definições** e preencha três campos:

| Fornecedor | URL base | Endpoint chamado |
|---|---|---|
| Compatível com OpenAI | `https://api.openai.com/v1` | `POST {base}/chat/completions` |
| Anthropic | `https://api.anthropic.com/v1` | `POST {base}/messages` |

O caminho OpenAI funciona com OpenRouter, Groq, Together, Ollama, LM Studio e vLLM — aponte a URL base para eles e defina o nome do modelo. Depois conceda a permissão de notificação e ligue **Manter o agente acordado** se quiser comportamento em segundo plano.

## Ferramentas

**Dispositivo** — `get_android_version`, `get_build_version`, `get_device_name`, `get_device_model`, `get_system_stats`, `get_time`, `list_installed_apps`

**Ações de app** — `open_url`, `launch_app`, `open_app_settings`

**Área de transferência** — `set_clipboard`, `get_clipboard`

**Conectividade** — `get_connection_method` (wlan / mobile_net / ethernet / vpn, medido, validado), `is_hotspot_running`

**Energia** — `get_battery_info`

**Shell** — `run_cmd` (sem privilégios, dentro da sandbox da app), `get_shizuku_status`, `connect_shizuku`, `run_shizuku_cmd` (elevado)

**Web** — `web_search` (frontend HTML do DuckDuckGo, com o frontend lite como recurso), `web_fetch`

**Notificações** — `check_notification_permission`, `request_notification_permission`, `send_notification`

**Si mesmo** — `read_identity`, `write_identity`, `read_memory`, `append_memory`, `write_memory`, `set_agent_name`

**Agendamento** — `create_cron`, `list_crons`, `delete_cron`, `set_cron_enabled`

### Shizuku

O Shizuku dá a uma app comum uma shell de nível ADB (ou root) sem que a app seja privilegiada. Depende de um serviço que o utilizador inicia através da depuração sem fios, ADB ou root.

O agente recebe exatamente estes tokens de volta, propositadamente:

- `connect_shizuku` → `shizuku_conned_success` quando comandos privilegiados estão disponíveis
- `connect_shizuku` → `shizuku_notreachable` se o serviço não estiver a correr, ou o utilizador negou
- `run_shizuku_cmd` → `shizuku not connected` se chamado sem ligação; nada corre

### Auto-agendamento

O `create_cron` recebe um intervalo **em minutos** e um prompt que o agente escreve para o seu eu futuro. A cada intervalo, esse prompt é entregue como um `[EVENT]` e o agente recebe uma volta completa.

`interval_minutes = 0` é permitido mas dispara aproximadamente a cada 10 segundos; a ferramenta devolve um aviso explícito, e o mesmo se aplica a tudo abaixo de 5 minutos. Os crons só disparam enquanto o serviço em primeiro plano está a correr.

## Alertas de bateria

O serviço verifica a cada 5 minutos, e também reage imediatamente a difusões de nível de bateria:

| Nível | Gravidade | Comportamento |
|---|---|---|
| 15% | aviso | O agente é informado; decide o que fazer |
| 5% | advertência | O agente é informado |
| 2%, 1%, 0% | crítico | Uma notificação de sistema de alta prioridade dispara **diretamente**, e é dito ao agente que já foi mostrada uma notificação |

Os alertas críticos não dependem de uma viagem de ida e volta à API ter sucesso — um aviso de 1% não deve perder-se porque um pedido expirou.

Cada limiar dispara uma vez por ciclo de descarga e só se rearma depois de a bateria subir 3 pontos acima, ou do telefone começar a carregar. Telefones a carregar nunca alertam.

## IDENTITY.md e MEMORY.md

Ambos vivem no armazenamento privado da app (`/data/data/at.creepervm1000.mobileclaw/files/`) e são injetados em cada prompt de sistema, por isso uma edição entra em vigor na própria volta seguinte. As Definições têm um botão **Exportar** que copia ambos para as Descargas para que os possa ler.

O prompt de sistema diz ao agente para os manter ativamente: acrescentar à memória quando aprende algo duradouro, reescrever a sua identidade quando a sua noção de si mesmo muda, e que o nome é dele para escolher. Limpar a conversa não toca em nenhum dos ficheiros — é esse o ponto deles.

## Notas e limites

- Nenhum wake lock permanente é mantido. É deliberado: uma app de monitorização de bateria que esgota a bateria para a monitorizar derrota-se a si própria. O Doze pode por isso adiar temporizadores; isente a app da otimização de bateria se precisar de precisão estrita de cinco minutos.
- O serviço em primeiro plano é do tipo `specialUse`, não `dataSync`. No Android 15, um serviço `dataSync` é parado à força após 6 horas por dia, o que mataria o agente em silêncio. A distribuição na Play Store exigiria justificar `specialUse` à revisão; a instalação lateral não.
- `is_hotspot_running` é o melhor esforço possível. `isWifiApEnabled` é uma API oculta; quando a plataforma a bloqueia, a ferramenta recorre a sondar interfaces de partilha e reporta `confident: false`.
- A pesquisa web faz scraping do frontend HTML do DuckDuckGo. Pode ser limitada em taxa ou servir um CAPTCHA; a ferramenta diz isso em vez de devolver silêncio.
- Os pedidos não são transmitidos em streaming — as respostas aparecem quando estão completas.
- `QUERY_ALL_PACKAGES` suporta `list_installed_apps`. É aceitável para instalação lateral; a distribuição na Play Store exigiria justificá-lo ou largá-lo.
- HTTP em texto simples é permitido, por isso o `web_fetch` pode alcançar um servidor de modelos local em `127.0.0.1`, uma máquina da LAN, ou uma página apenas-http. A partir do `targetSdk` 28 o Android bloqueia esses por predefinição, o que se manifestou como uma falha de rede opaca enquanto a mesma URL carregava bem no navegador.

## Licença

MIT — ver [LICENSE](LICENSE).
