# MobileClaw

[English](README.md) | [中文文档](README_zh.md) | [Français](README_fr.md) | [Deutsch](README_de.md) | [Español](README_es.md) | [日本語](README_ja.md) | [Português](README_pt.md) | [Русский](README_ru.md)

Android 폰에서 사는 AI 에이전트입니다. OpenAI 호환 또는 Anthropic 호환 API 어디와도 통신하며, 진짜 도구들을 갖추고 있습니다: 기기 상태 읽기, 셸 명령 실행, Shizuku를 통한 상승 권한 접근, 웹 검색, 알림 전송, 그리고 스스로 깨어날 시간을 예약하는 것까지 가능합니다.

또한 자신만의 `IDENTITY.md`와 `MEMORY.md`를 직접 작성합니다 —— 이름까지 포함해서요. `MobileClaw`는 기본 이름일 뿐입니다.

## 빌드

```bash
export JAVA_HOME=/path/to/jdk21          # JDK 17–21
export ANDROID_HOME=/path/to/android-sdk # platform-35 + build-tools 35 필요
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

또는 Android Studio에서 프로젝트를 열고 Run을 누르세요.

- `minSdk` 26 (Android 8.0), `targetSdk`/`compileSdk` 35.
- Studio가 만들어주지 않으면 `sdk.dir=/path/to/android-sdk`가 담긴 `local.properties`를 생성하세요.

## 설정

**설정**을 열고 세 항목을 채우세요:

| 공급자 | 베이스 URL | 호출되는 엔드포인트 |
|---|---|---|
| OpenAI 호환 | `https://api.openai.com/v1` | `POST {base}/chat/completions` |
| Anthropic | `https://api.anthropic.com/v1` | `POST {base}/messages` |

OpenAI 경로는 OpenRouter, Groq, Together, Ollama, LM Studio, vLLM에서 동작합니다 —— 베이스 URL을 해당 서비스로 향하게 하고 모델 이름을 설정하세요. 그다음 알림 권한을 허용하고, 백그라운드 동작을 원하면 **에이전트 깨어 있기**를 켜세요.

## 도구

**기기** — `get_android_version`, `get_build_version`, `get_device_name`, `get_device_model`, `get_system_stats`, `get_time`, `list_installed_apps`

**앱 동작** — `open_url`, `launch_app`, `open_app_settings`

**클립보드** — `set_clipboard`, `get_clipboard`

**연결** — `get_connection_method` (wlan / mobile_net / ethernet / vpn, 종량제 여부, 검증됨), `is_hotspot_running`

**전원** — `get_battery_info`

**셸** — `run_cmd` (비특권, 앱 샌드박스 내부), `get_shizuku_status`, `connect_shizuku`, `run_shizuku_cmd` (상승)

**웹** — `web_search` (DuckDuckGo HTML 프론트엔드, lite 프론트엔드 폴백), `web_fetch`

**알림** — `check_notification_permission`, `request_notification_permission`, `send_notification`

**자기 자신** — `read_identity`, `write_identity`, `read_memory`, `append_memory`, `write_memory`, `set_agent_name`

**스케줄링** — `create_cron`, `list_crons`, `delete_cron`, `set_cron_enabled`

### Shizuku

Shizuku는 특권이 없는 평범한 앱에 ADB 수준(또는 root) 셸을 부여합니다. 사용자가 무선 디버깅, ADB 또는 root로 시작하는 서비스에 의존합니다.

에이전트는 의도적으로 정확히 이 토큰들을 받습니다:

- `connect_shizuku` → `shizuku_conned_success`: 특권 명령을 사용할 수 있을 때
- `connect_shizuku` → `shizuku_notreachable`: 서비스가 실행 중이 아니거나 사용자가 거부한 경우
- `run_shizuku_cmd` → `shizuku not connected`: 연결 없이 호출하면 아무것도 실행되지 않음

### 자기 스케줄링

`create_cron`은 **분 단위** 간격과 에이전트가 미래의 자신을 위해 작성하는 프롬프트를 받습니다. 각 간격마다 그 프롬프트가 `[EVENT]`로 전달되고 에이전트는 완전한 턴을 얻습니다.

`interval_minutes = 0`은 허용되지만 약 10초마다 발화합니다. 도구는 명시적인 경고를 반환하며, 5분 미만의 값도 마찬가지입니다. cron은 포그라운드 서비스가 실행 중일 때만 발화합니다.

## 배터리 알림

서비스는 5분마다 확인하고, 배터리 레벨 브로드캐스트에도 즉시 반응합니다:

| 레벨 | 심각도 | 동작 |
|---|---|---|
| 15% | 알림 | 에이전트에게 전달. 대처는 에이전트가 결정 |
| 5% | 경고 | 에이전트에게 전달 |
| 2%, 1%, 0% | 심각 | 고우선순위 시스템 알림이 **직접** 발화하고, 에이전트에게는 알림이 이미 표시되었다고 전달됨 |

심각 알림은 API 왕복 성공에 의존하지 않습니다 —— 요청 타임아웃 때문에 1% 경고가 사라져서는 안 됩니다.

각 임계값은 방전 주기당 한 번만 발화하며, 배터리가 그보다 3포인트 위로 올라가거나 폰이 충전을 시작한 후에만 재무장합니다. 충전 중인 폰은 절대 알리지 않습니다.

## IDENTITY.md와 MEMORY.md

두 파일 모두 앱 전용 저장소(`/data/data/at.creepervm1000.mobileclaw/files/`)에 있으며 모든 시스템 프롬프트에 주입되므로, 수정사항이 다음 턴에 즉시 반영됩니다. 설정에는 두 파일을 다운로드 폴더로 복사해 볼 수 있게 하는 **내보내기** 버튼이 있습니다.

시스템 프롬프트는 에이전트에게 이것들을 능동적으로 유지하라고 지시합니다: 지속적인 것을 학습하면 메모리에 추가하고, 자기 인식이 변하면 정체성을 다시 쓰며, 이름은 스스로 선택할 수 있습니다. 대화를 지워도 두 파일은 건드리지 않습니다 —— 그것이 이 파일들의 존재 이유입니다.

## 참고사항과 한계

- 영구 wake lock은 유지하지 않습니다. 의도된 설계입니다: 감시를 위해 배터리를 소모하는 배터리 모니터링 앱은 자기모순입니다. 따라서 Doze가 타이머를 지연시킬 수 있습니다. 엄격한 5분 정확도가 필요하면 배터리 최적화에서 앱을 제외하세요.
- 포그라운드 서비스는 `dataSync`가 아닌 `specialUse` 타입입니다. Android 15에서는 `dataSync` 서비스가 하루 6시간 후 강제 중단되어 에이전트가 조용히 죽습니다. Play Store 배포는 `specialUse`를 심사에 정당화해야 하지만, 사이드로딩은 그렇지 않습니다.
- `is_hotspot_running`은 최선 노력 방식입니다. `isWifiApEnabled`는 숨겨진 API이며, 플랫폼이 차단하면 도구는 테더링 인터페이스 탐지로 폴백하고 `confident: false`를 보고합니다.
- 웹 검색은 DuckDuckGo의 HTML 프론트엔드를 스크래핑합니다. 속도 제한이나 CAPTCHA를 만날 수 있습니다. 도구는 침묵을 반환하는 대신 그 사실을 말합니다.
- 요청은 스트리밍되지 않습니다 —— 응답은 완료 시 표시됩니다.
- `QUERY_ALL_PACKAGES`는 `list_installed_apps`를 뒷받침합니다. 사이드로딩에서는 문제없지만, Play Store 배포는 정당화하거나 제거해야 합니다.
- 평문 HTTP가 허용되므로, `web_fetch`는 `127.0.0.1`의 로컬 모델 서버, LAN 장비, http 전용 페이지에 도달할 수 있습니다. `targetSdk` 28부터 Android는 이런 요청을 기본 차단하며, 같은 URL이 브라우저에서는 잘 로드되는데 앱에서는 불투명한 네트워크 실패로 나타났습니다.

## 라이선스

MIT —— [LICENSE](LICENSE) 참조.
