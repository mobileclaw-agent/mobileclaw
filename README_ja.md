# MobileClaw

[English](README.md) | [中文文档](README_zh.md) | [Français](README_fr.md) | [Deutsch](README_de.md) | [Español](README_es.md) | [한국어](README_ko.md) | [Português](README_pt.md) | [Русский](README_ru.md)

Android スマートフォン上で動く AI エージェント。OpenAI 互換および Anthropic 互換のあらゆる API とやり取りでき、本物のツールを備えています。デバイスの状態の読み取り、シェルコマンドの実行、Shizuku による昇格権限の取得、ウェブ検索、通知、そして自分自身を起動するスケジュール管理が可能です。

さらに、自分自身の `IDENTITY.md` と `MEMORY.md` —— 名前さえも —— を自分で書きます。`MobileClaw` はデフォルトの名前にすぎません。

## ビルド

```bash
export JAVA_HOME=/path/to/jdk21          # JDK 17–21
export ANDROID_HOME=/path/to/android-sdk # platform-35 + build-tools 35 が必要
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

または、Android Studio でプロジェクトを開いて Run を押します。

- `minSdk` 26（Android 8.0）、`targetSdk`/`compileSdk` 35。
- Studio が作成しない場合は、`sdk.dir=/path/to/android-sdk` を含む `local.properties` を作成してください。

## セットアップ

**設定** を開き、3 つの項目を入力します:

| プロバイダー | ベース URL | 呼び出されるエンドポイント |
|---|---|---|
| OpenAI 互換 | `https://api.openai.com/v1` | `POST {base}/chat/completions` |
| Anthropic | `https://api.anthropic.com/v1` | `POST {base}/messages` |

OpenAI パスは OpenRouter、Groq、Together、Ollama、LM Studio、vLLM で動作します —— ベース URL をそれらに向けて、モデル名を設定してください。その後、通知権限を許可し、バックグラウンド動作を望む場合は **エージェントを起動し続ける** をオンにします。

## ツール

**デバイス** — `get_android_version`、`get_build_version`、`get_device_name`、`get_device_model`、`get_system_stats`、`get_time`、`list_installed_apps`

**アプリ操作** — `open_url`、`launch_app`、`open_app_settings`

**クリップボード** — `set_clipboard`、`get_clipboard`

**接続** — `get_connection_method`（wlan / mobile_net / ethernet / vpn、従量制、検証済み）、`is_hotspot_running`

**電源** — `get_battery_info`

**シェル** — `run_cmd`（非特権、アプリサンドボックス内）、`get_shizuku_status`、`connect_shizuku`、`run_shizuku_cmd`（昇格）

**ウェブ** — `web_search`（DuckDuckGo の HTML フロントエンド、lite フロントエンドにフォールバック）、`web_fetch`

**通知** — `check_notification_permission`、`request_notification_permission`、`send_notification`

**自己** — `read_identity`、`write_identity`、`read_memory`、`append_memory`、`write_memory`、`set_agent_name`

**スケジュール** — `create_cron`、`list_crons`、`delete_cron`、`set_cron_enabled`

### Shizuku

Shizuku は、特権を持たない通常のアプリに ADB レベル（または root）のシェルを与えます。ユーザーがワイヤレスデバッグ、ADB、または root で起動するサービスに依存します。

エージェントには、設計どおりに次のトークンがそのまま返されます:

- `connect_shizuku` → `shizuku_conned_success`：特権コマンドが利用可能な場合
- `connect_shizuku` → `shizuku_notreachable`：サービスが起動していないか、ユーザーが拒否した場合
- `run_shizuku_cmd` → `shizuku not connected`：接続なしで呼び出した場合。何も実行されません

### 自己スケジューリング

`create_cron` は **分単位** の間隔と、エージェントが未来の自分のために書くプロンプトを受け取ります。各間隔で、そのプロンプトは `[EVENT]` として配信され、エージェントは完全なターンを得ます。

`interval_minutes = 0` は許可されていますが、約 10 秒ごとに発火します。ツールは明示的な警告を返し、5 分未満の値も同様です。cron はフォアグラウンドサービスが動作している間のみ発火します。

## バッテリーアラート

サービスは 5 分ごとにチェックし、バッテリーレベルのブロードキャストにも即座に反応します:

| レベル | 深刻度 | 動作 |
|---|---|---|
| 15% | 通知 | エージェントに伝える。対処はエージェントが判断 |
| 5% | 警告 | エージェントに伝える |
| 2%、1%、0% | 重大 | 高優先度のシステム通知が **直接** 発火し、エージェントには通知が既に表示されたことが伝えられる |

重大なアラートは API 往復の成功に依存しません —— リクエストのタイムアウトのせいで 1% の警告が失われてはなりません。

各しきい値は放電サイクルごとに 1 回だけ発火し、バッテリーがその 3 ポイント上まで回復するか、充電が開始された後にのみ再武装します。充電中のスマートフォンはアラートを出しません。

## IDENTITY.md と MEMORY.md

両方ともアプリ専用ストレージ（`/data/data/at.creepervm1000.mobileclaw/files/`）に置かれ、すべてのシステムプロンプトに注入されるため、編集は次のターンから即座に反映されます。設定には **エクスポート** ボタンがあり、両ファイルを Downloads にコピーして読めるようにします。

システムプロンプトは、エージェントにこれらを能動的に維持するよう指示します。持続的なことを学んだらメモリに追記する、自己認識が変化したらアイデンティティを書き換える、そしてその名前は自分で選べる、と。会話をクリアしてもどちらのファイルにも触れません —— それがこれらのファイルの存在意義です。

## ノートと制限

- 永続的な wake lock は保持しません。これは意図的です。監視のためにバッテリーを消耗するバッテリー監視アプリは自己矛盾です。そのため Doze はタイマーを遅延させることがあります。厳密な 5 分精度が必要なら、バッテリー最適化からアプリを除外してください。
- フォアグラウンドサービスは `dataSync` ではなく `specialUse` 型です。Android 15 では `dataSync` サービスは 1 日 6 時間後に強制停止され、エージェントが静かに死にます。Play Store で配布するには `specialUse` の正当性を審査に示す必要があります。サイドローディングでは不要です。
- `is_hotspot_running` はベストエフォートです。`isWifiApEnabled` は非公開 API で、プラットフォームがブロックすると、ツールはテザリングインターフェースの検出にフォールバックし `confident: false` を報告します。
- ウェブ検索は DuckDuckGo の HTML フロントエンドをスクレイピングします。レート制限や CAPTCHA を食らうことがあります。ツールは沈黙を返すのではなく、その旨を伝えます。
- リクエストはストリーミングされません —— 返信は完了時に表示されます。
- `QUERY_ALL_PACKAGES` は `list_installed_apps` のためのものです。サイドローディングでは問題ありません。Play Store 配布では正当化するか削除する必要があります。
- 平文 HTTP が許可されているため、`web_fetch` は `127.0.0.1` のローカルモデルサーバー、LAN のマシン、http 専用ページに到達できます。`targetSdk` 28 以降、Android はこれらをデフォルトでブロックします。同じ URL がブラウザーでは問題なく読めるのに、アプリでは不透明なネットワークエラーとして現れました。

## ライセンス

MIT —— [LICENSE](LICENSE) を参照。
