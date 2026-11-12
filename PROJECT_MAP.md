# Карта проекта DigmaWatchApp (для локальной работы)

> Android-клиент для смарт-часов **Digma Smartline D3** (поддержка протоколов **SXR/KeepFit `000056ff`** и legacy **`0xAB` / `0000fff0`**).

---

## 1. Паспорт проекта

| Параметр | Значение |
|---|---|
| `applicationId` / `namespace` | `com.digma.watchapp` |
| Текущая версия | `0.1.0` (`versionCode = 9`) |
| Android SDK | `minSdk = 26`, `targetSdk = 35`, `compileSdk = 35` |
| Стек | Kotlin `2.0.21`, AGP `8.7.3`, Jetpack Compose + Material 3 (BOM `2024.11.00`), Coroutines `1.9.0` |
| JDK / Gradle | Java `17`, Gradle Wrapper `8.11.1` |
| Локальный Android SDK | `D:\Android\Sdk` (указан в `local.properties`) |
| Репозиторий автообновлений | `pastur253/DigmaWatchApp` (GitHub Releases) |

---

## 2. Архитектура и потоки данных

```
┌─────────────────────────── UI (Jetpack Compose) ───────────────────────────┐
│ MainActivity ──▶ DashboardScreen / ScanScreen / HistoryScreen / SettingsScreen │
│                         │                        │                         │
│                    UpdateDialog             WatchViewModel                 │
└─────────────────────────┼────────────────────────┼─────────────────────────┘
                          ▼                        ▼
                   UpdateManager         DigmaApp (Application singleton)
                 (Checker/Downloader    ├─ settings: StateFlow<WatchSettings>
                    /Installer)         ├─ bleScanner: BleScanner
                                        └─ bleManager: BleManager
                                               │
                 ┌─────────────────────────────┼─────────────────────────────┐
                 ▼                             ▼                             ▼
      WatchSyncService             NotificationCollectorService        Protocol Codecs
 (ForegroundService 15m sync)       (NotificationRelayPolicy)     (SxrKeepFitCodec / DigmaProtocolCodec)
```

- **Инициализация**: `DigmaApp.onCreate()` создаёт синглтоны `bleManager`, `bleScanner`, `updateManager`, загружает `WatchSettings` из `SharedPreferences("digma_watch_settings")` и регистрирует канал уведомлений `digma_watch_sync`.
- **BLE-обмен**: `BleManager` сериализует все GATT-записи/чтения через `commandQueue` (`Channel.UNLIMITED`) и `writeMutex`, поддерживает режим протокола `WatchProtocolMode` (`AUTO`, `SXR_KEEPFIT`, `AB_LEGACY`), держит keepalive раз в `15_000 ms` для SXR и хранит до `400` строк диагностических логов.
- **Пересылка уведомлений**: `NotificationCollectorService` перехватывает `StatusBarNotification` ➔ проверяет правила в `NotificationRelayPolicy.evaluateDecision()` ➔ извлекает заголовок и текст через `extractContent()` ➔ отправляет через `BleManager.sendNotification()`.
- **Автообновление**: `MainActivity.onCreate()` запускает `viewModel.checkForUpdates(isManual = false)` ➔ `UpdateChecker` запрашивает GitHub Releases API ➔ `UpdateDownloader` качает APK с поддержкой редиректов ➔ `UpdateInstaller` открывает системный установщик через `FileProvider` (`${applicationId}.fileprovider`).

---

## 3. Структура каталогов и файлов

### Корень, сборка и CI

| Путь | Назначение |
|---|---|
| `settings.gradle.kts` | Подключает модуль `:app`; репозитории настроены с зеркалами `maven.aliyun.com` + `google()` + `mavenCentral()` |
| `build.gradle.kts` | Корневая конфигурация плагинов (`android.application`, `kotlin.android`, `compose.compiler`) |
| `app/build.gradle.kts` | SDK, версия, зависимости, `signingConfigs.release` (используется и для `debug`, и для `release`) |
| `gradle/libs.versions.toml` | Каталог версий библиотек и плагинов |
| `gradle.properties` | JVM-аргументы Gradle (`-Xmx2048m`, UTF-8, `android.useAndroidX=true`) |
| `local.properties` | Локальный путь к Android SDK (`sdk.dir`), игнорируется Git |
| `keystore/release.jks` | Релизный keystore (подключён в `git`, резервный fallback-пароль в `app/build.gradle.kts`) |
| `keystore/GITHUB_SECRETS_GUIDE.md` | Инструкция по секретам GitHub Actions (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) |
| `.github/workflows/android.yml` | CI/CD: сборка `assembleDebug` + `assembleRelease`, прогон `./gradlew test`, публикация релиза на теги `v*` |

### Исходный код (`app/src/main/java/com/digma/watchapp/`)

#### Корневой пакет и модели
- `DigmaApp.kt` *(110 строк)* — `Application`: хранит `bleManager`, `bleScanner`, `updateManager`, `settings` (`SharedPreferences`), проверяет доступ к уведомлениям (`isNotificationListenerEnabled()`).
- `model/Models.kt` *(49 строк)* — data-классы `WatchDevice`, `HealthMetrics`, `WatchSettings` (включая `DEFAULT_ALLOWED_PACKAGES`), `ActivityHistoryItem`.

#### `ble/` — Bluetooth Low Energy
- `ble/BleConnectionState.kt` *(9 строк)* — `sealed class BleConnectionState` (`Disconnected`, `Scanning`, `Connecting`, `Connected`, `Syncing`, `Error`).
- `ble/BleScanner.kt` *(83 строки)* — BLE-сканер (`SCAN_MODE_LOW_LATENCY`), сортирует найденные устройства: сначала по ключевым словам (`smartline`, `digma`, `d3`, `ador`, `watch`), затем по `RSSI`.
- `ble/BleManager.kt` *(1151 строка)* — центральный GATT-клиент:
  - Автопоиск vendor-сервисов (`000056ff` SXR KeepFit, `0000fff0`, Telink `00010203...`, Nordic `6e400001...` и др.), игнорирует стандартные SIG `000018xx` при выборе vendor-канала.
  - Переключение протоколов (`WatchProtocolMode`: `AUTO`, `SXR_KEEPFIT`, `AB_LEGACY`), ручной swap характеристик (`swapCharacteristics()`).
  - Команды: `syncTime()`, `syncAll()`, `queryBattery()`, `requestActivity()`, `findWatch()`, `toggleHeartRateMeasurement()`, `sendNotification()`, тестовые методы диагностики.

#### `protocol/` — Кодеки пакетов
- `protocol/SxrKeepFitCodec.kt` *(496 строк)* — кодек SXR / KeepFit (`000056ff`, характеристики `000033f3` write / `000033f4` notify / `000033f5` / `000033f6`): фиксированные 20-байтовые фреймы (little-endian), handshake (`0x48` AppId, `0x4B` BindInfo), время (`0x01`), спорт/шаги (`0x03`, `0x51`, `0x10`), пульс/`SpO2` (`0x14`, `0x15`, `0x23`, `0x24`, `0x3E`, `0x3F`), уведомления (`0x12`), keepalive (`0x3A`).
- `protocol/DigmaProtocolCodec.kt` *(280 строк)* — legacy-кодек с заголовком `0xAB` (и `0x5A` RX) и XOR-чексуммой: время (`0x01`), поиск часов (`0x09`), пульс (`0x31`), активность (`0x51`), уведомления (`0x72`), батарея (`0x91`), события часов (`0xFE`).

#### `service/` — Системные и фоновые службы
- `service/WatchSyncService.kt` *(104 строки)* — `ForegroundService` (`foregroundServiceType="connectedDevice"`), обновляет статусное уведомление и запускает `bleManager.syncAll()` каждые 15 минут.
- `service/NotificationCollectorService.kt` *(92 строки)* — `NotificationListenerService`, перехватывает уведомления системы и извлекает `MessagingStyle` (`EXTRA_MESSAGES`).
- `service/NotificationRelayPolicy.kt` *(145 строк)* — чистая бизнес-логика фильтрации (`evaluateDecision`), классификации (`CALL` / `SMS` / `APP_MESSAGE`), списка популярных мессенджеров (`POPULAR_MESSENGERS`) и нормализации текста (`extractContent`, лимиты: 64 символа заголовок, 220 символов тело).

#### `ui/` — Интерфейс (Compose + Material 3)
- `ui/MainActivity.kt` *(177 строк)* — точка входа Activity, запрос runtime-разрешений (BLE, `POST_NOTIFICATIONS`, `REQUEST_INSTALL_PACKAGES`), 4 вкладки (`NavigationTab`: `DASHBOARD`, `SCAN`, `HISTORY`, `SETTINGS`), интеграция `UpdateDialog`.
- `ui/viewmodel/WatchViewModel.kt` *(135 строк)* — проксирует `StateFlow` и действия между UI, `BleManager`, `BleScanner`, `DigmaApp` и `UpdateManager`.
- `ui/screens/DashboardScreen.kt` *(415 строк)* — статус часов, батарея, прогресс шагов/калорий/дистанции, пульс и `SpO2`, быстрые действия, pull-to-refresh.
- `ui/screens/ScanScreen.kt` *(656 строк)* — поиск BLE-устройств, подключение/отключение, выбор `WatchProtocolMode`, просмотр GATT-сервисов, терминал диагностических логов и тестовые кнопки.
- `ui/screens/HistoryScreen.kt` *(137 строк)* — сводка активности и экран статистики.
- `ui/screens/SettingsScreen.kt` *(495 строк)* — настройки часов, доступ к уведомлениям, выбор приложений-мессенджеров / режим «все приложения», тестовое уведомление, блок проверки обновлений.
- `ui/dialogs/UpdateDialog.kt` *(379 строк)* — диалоги наличия обновления, прогресса загрузки, готовности к установке и ошибок.
- `ui/theme/Color.kt` *(16 строк)* и `ui/theme/Theme.kt` *(44 строки)* — тёмная и светлая цветовые палитры.

#### `update/` — Автообновление через GitHub Releases
- `update/ReleaseInfo.kt` *(26 строк)* — модели `ReleaseInfo` и `UpdateState`.
- `update/UpdatePreferences.kt` *(32 строки)* — `SharedPreferences("digma_update_prefs")` (автопроверка, время последней проверки, пропущенная версия, owner/repo).
- `update/UpdateChecker.kt` *(152 строки)* — запрос `https://api.github.com/repos/$owner/$repo/releases/latest`, подбор `.apk` (предпочтение release перед debug), сравнение версий.
- `update/UpdateDownloader.kt` *(125 строк)* — скачивание APK с прогрессом в `externalFilesDir/Download` (или `cacheDir/updates`) и обработкой HTTP-редиректов.
- `update/UpdateInstaller.kt` *(57 строк)* — проверка `canRequestPackageInstalls()` и запуск установки через `FileProvider`.
- `update/UpdateManager.kt` *(128 строк)* — координатор жизненного цикла обновления.

### Манифест, ресурсы и тесты
- `app/src/main/AndroidManifest.xml` *(71 строка)* — разрешения (BLE 12+ `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`, legacy BLE+Location `maxSdkVersion=30`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`, `REQUEST_INSTALL_PACKAGES`, `INTERNET`), объявление `WatchSyncService`, `NotificationCollectorService`, `FileProvider`.
- `app/src/main/res/` — `drawable/ic_watch.xml`, `values/strings.xml`, `xml/file_paths.xml`.
- `app/src/test/java/com/digma/watchapp/`:
  - `protocol/DigmaProtocolCodecTest.kt` *(148 строк)*
  - `protocol/SxrKeepFitCodecTest.kt` *(453 строки)*
  - `service/NotificationRelayPolicyTest.kt` *(102 строки)*
  - `update/UpdateVersionCompareTest.kt` *(47 строк)*

### Документация и Python-инструменты
- `docs/PROTOCOL_SPEC.md` — спецификация legacy BLE-протокола `0xAB`.
- `scripts/ble_inspector.py` + `scripts/requirements.txt` — Python-утилита на `bleak` для сканирования, просмотра GATT-атрибутов и отправки тестовых пакетов с ПК.

---

## 4. Команды для локальной работы (Windows / PowerShell)

### Сборка и тесты Android
```powershell
# Запустить unit-тесты (JVM, без эмулятора)
.\gradlew.bat test

# Собрать Debug APK -> app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat assembleDebug

# Собрать Release APK -> app\build\outputs\apk\release\app-release.apk
.\gradlew.bat assembleRelease

# Очистить артефакты сборки
.\gradlew.bat clean
```

> **Про подпись сборок:** и `debug`, и `release` подписаны `signingConfigs.release` из `app/build.gradle.kts`, который автоматически подхватывает `keystore/release.jks` (если не заданы переменные окружения `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). Это позволяет ставить локальные APK поверх релизных без конфликта подписи.

### Установка и логи через ADB
```powershell
# Установить Debug APK на подключённый телефон
adb install -r app\build\outputs\apk\debug\app-debug.apk

# Смотреть BLE и уведомления в logcat
adb logcat -s BleManager NotifCollector UpdateChecker UpdateInstaller
```

### Инспекция часов с ПК (Python)
```powershell
pip install -r scripts\requirements.txt
python scripts\ble_inspector.py
```

---

## 5. Навигатор по задачам: что где менять

| Задача | Куда смотреть в первую очередь |
|---|---|
| Добавить/исправить команду или парсинг байтов часов (SXR `000056ff`) | `protocol/SxrKeepFitCodec.kt` + `protocol/SxrKeepFitCodecTest.kt` + `ble/BleManager.kt` |
| Добавить/исправить команду legacy-протокола (`0xAB`) | `protocol/DigmaProtocolCodec.kt` + `protocol/DigmaProtocolCodecTest.kt` + `docs/PROTOCOL_SPEC.md` |
| Поменять логику подключения, MTU, keepalive, очередь GATT | `ble/BleManager.kt`, состояния в `ble/BleConnectionState.kt` |
| Изменить фильтрацию или форматирование уведомлений | `service/NotificationRelayPolicy.kt` + `NotificationRelayPolicyTest.kt`, `service/NotificationCollectorService.kt`, дефолтные пакеты в `model/Models.kt` |
| Доработать экран / UI / вкладки | `ui/MainActivity.kt`, `ui/screens/*.kt`, `ui/viewmodel/WatchViewModel.kt` |
| Изменить логику автообновлений или репозиторий релизов | `update/UpdatePreferences.kt`, `update/UpdateChecker.kt`, `update/UpdateDownloader.kt`, `update/UpdateInstaller.kt` |
| Поднять `versionCode` / `versionName` перед релизом | `app/build.gradle.kts` (и создать git-тег `vX.Y.Z` для CI) |
