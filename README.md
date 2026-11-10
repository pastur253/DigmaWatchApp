# DigmaWatchApp — Android-клиент для смарт-часов Digma Smartline D3

[![Android CI](https://github.com/pastur253/DigmaWatchApp/actions/workflows/android.yml/badge.svg)](https://github.com/pastur253/DigmaWatchApp/actions/workflows/android.yml)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026--35)-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4.svg)](https://developer.android.com/jetpack/compose)

Современное открытое Android-приложение для подключения, синхронизации и управления смарт-часами **Digma Smartline D3** (модели D3B / D3S на базе чипа **Telink TLSR8251**).

---

## 🎯 Зачем создано это приложение?

Официальное приложение **AdorHealth** (`com.adorone`):
- **Удалено из Google Play Store**, что вынуждает пользователей искать сомнительные APK в интернете.
- Страдает от вылетов на современных версиях Android (12–15+) из-за устаревшей модели BLE-разрешений.
- Часы сбрасывают время и дату при разрядке батареи и требуют сопряжения для работы.

**DigmaWatchApp** решает эти проблемы: чистое, быстрое приложение без рекламы и сторонних трекеров с открытым исходным кодом.

---

## 📱 Основные возможности

### 1. Главный дашборд (Dashboard)
- **Статус часов**: отображение подключения, уровня заряда батареи (%) и времени последней синхронизации.
- **Круговой прогресс шагомера**: наглядное кольцо шагов с дневной целью, счетчиком сожженных килокалорий и пройденной дистанцией в километрах.
- **Пульсометр и $SpO_2$**: отображение текущего пульса (уд/мин) и уровня кислорода в крови с возможностью запуска и остановки непрерывного замера в реальном времени.
- **Быстрые действия**:
  - `Синхронизация времени`: немедленная установка точного времени и даты смартфона в часы.
  - `Поиск D3`: запуск вибромотора на часах для быстрого поиска гаджета в комнате.
  - `Обновить активность`: принудительный опрос датчиков шагов и калорий.

### 2. Сканер и быстрое сопряжение (BLE Scanner)
- Автоматический поиск Bluetooth Low Energy устройств.
- Фильтрация и подсветка целевых устройств (`Smartline D3`, `D3`, `Digma`).
- Индикация мощности сигнала (RSSI в dBm).
- Подключение в один тап.

### 3. Пересылка уведомлений (Notification Relay)
- Полноценная системная служба `NotificationCollectorService` (`NotificationListenerService`).
- Перехват и форматирование уведомлений от мессенджеров (Telegram, WhatsApp, VK, Viber и др.), SMS и входящих звонков.
- Безопасная упаковка текста в UTF-8 с учетом лимитов экрана (240x240) и MTU часов.

### 4. Фоновая поддержка соединения (WatchSyncService)
- `Foreground Service` предотвращает засыпание соединения системой Android.
- Периодическая фоновая синхронизация (время, заряд, шаги).
- Быстрое автоматическое переподключение при возвращении часов в зону действия Bluetooth.

### 5. Система автообновления (GitHub Releases)
- **Фоновая автопроверка**: при старте приложения проверяется наличие свежего релиза в `pastur253/DigmaWatchApp`.
- **Ручная проверка**: кнопка «Проверить обновления» в настройках с отображением времени последней проверки.
- **Интерактивный диалог обновления**:
  - Сравнение версий (`v0.0.1 ➔ v0.0.2`), размер APK и список изменений (Release Notes).
  - Фоновая докачка APK с индикатором прогресса (в % и МБ) и поддержкой CDN-редиректов.
  - Безопасная установка через `FileProvider` (`content://`) и системный установщик Android.
  - Опции «Пропустить эту версию» или «Напомнить позже».

---

## 🛠️ Стек технологий и архитектура

- **Язык**: Kotlin 2.0.21.
- **UI**: Jetpack Compose, Material 3, Material Icons Extended.
- **Асинхронность**: Kotlin Coroutines, StateFlow, SharedFlow.
- **Bluetooth**: Android BLE API (GATT Client, Low Latency Scanner, MTU Negotiation, Auto-reconnect).
- **Службы**: `ForegroundService` (тип `connectedDevice`) + `NotificationListenerService`.
- **Архитектура**: Clean Architecture (UI -> ViewModel -> BLE Engine -> Protocol Codec).

---

## 📡 Протокол BLE и скрипт для ПК

Подробное описание пакетов, команд и контрольных сумм приведено в **[`docs/PROTOCOL_SPEC.md`](docs/PROTOCOL_SPEC.md)**.

В каталоге `scripts/` находится Python-утилита для исследования GATT-сервисов и тестирования часов прямо с компьютера:
```bash
# Установка зависимостей
pip install -r scripts/requirements.txt

# Сканирование и инспекция часов
python scripts/ble_inspector.py
```

---

## 🚀 Сборка проекта

### В Android Studio:
1. Откройте папку `DigmaWatchApp` в **Android Studio**.
2. Дождитесь завершения Gradle Sync.
3. Запустите сборку и отладку на физическом Android-устройстве или эмуляторе.

### Через командную строку:
```bash
# Запуск unit-тестов кодека протокола
./gradlew test

# Сборка Debug APK
./gradlew assembleDebug
```
Готовый APK-файл появится в `app/build/outputs/apk/debug/`.
