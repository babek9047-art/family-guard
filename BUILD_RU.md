# Сборка приложения Family Guard 1.0.0

Код приложения обновлён (Этап 2). Готовый APK нужно собрать там, где есть среда Android — на этой машине её нет.

## Что нужно
- **Android Studio** (проще всего), либо командная строка с: JDK 17, Android SDK (platform android-35), build-tools 35.0.0.
- Папка `signing` рядом с `android` — она уже на месте: `signing/keystore.properties` и `signing/family-guard-release.jks`. Файл `build.gradle.kts` сам берёт ключ оттуда для release-подписи.

## Вариант 1 — Android Studio
1. Открой папку `android` как проект (File → Open).
2. Дождись синхронизации Gradle.
3. Меню **Build → Build Bundle(s) / APK(s) → Build APK(s)** — release соберётся подписанным (ключ подхватывается автоматически).
4. Готовые файлы: `app/build/outputs/apk/lite/release/app-lite-release.apk` и `.../full/release/app-full-release.apk`.

## Вариант 2 — командная строка
Из папки `android`:

```bash
./gradlew assembleLiteRelease assembleFullRelease
```

На Windows:

```bash
gradlew.bat assembleLiteRelease assembleFullRelease
```

Выходные файлы:
- `app/build/outputs/apk/lite/release/app-lite-release.apk`
- `app/build/outputs/apk/full/release/app-full-release.apk`

Переименуй в `FamilyGuard-1.0.0-lite-release.apk` и `FamilyGuard-1.0.0-full-release.apk`.

## Проверка подписи (необязательно)
```bash
apksigner verify --print-certs app/build/outputs/apk/lite/release/app-lite-release.apk
```

## Что нового в коде 1.0.0
- Общий модуль дизайн-системы `Ui.kt` (цвета, карточки, кнопки, шапка-градиент).
- Главный экран-дашборд в стиле Prey: статус защиты, заряд, экранное время с полосой лимита, разрешения, запуск/остановка.
- Кнопка **SOS** — отправляет родителю событие с геолокацией (эндпоинт `/event` в плагине 1.0.0).
- Экран **«Экранное время по приложениям»** (видно YouTube и часы по каждому приложению).
- Отдельный экран **«Настройки»** (интервал, лимит, PIN, фон/батарея, защита от удаления, отключение от сайта).
- Версия поднята до 1.0.0 (versionCode 8).

Для работы SOS и геозон на сайте должен стоять плагин Family Guard **1.0.0**.

> Примечание: код собран и проверен на баланс структуры, но без компиляции (нет Android SDK на машине сборки у ассистента). Если Gradle укажет на ошибку — пришли текст, поправлю точечно.
