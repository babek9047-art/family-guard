# Сборка APK в облаке (GitHub Actions)

Это способ собрать подписанный APK **без своего компьютера** — на серверах GitHub. Нужен бесплатный аккаунт GitHub. Я уже подготовил всё в проекте:
- `.github/workflows/build-apk.yml` — сборка и подпись;
- `.gitignore` — чтобы ключ подписи не попал в репозиторий.

Весь процесс — один раз ~15 минут. Дальше новые APK собираются одной кнопкой.

---

## Шаг 1. Аккаунт и репозиторий
1. Зарегистрируйся на https://github.com (если ещё нет).
2. Нажми **New repository** → имя, например `family-guard` → выбери **Private** → **Create repository**.

## Шаг 2. Загрузить проект
Самый простой путь без терминала:
1. На странице репозитория: **Add file → Upload files**.
2. Перетащи туда папку проекта `family-guard` целиком (можно перетащить содержимое).
   - **Важно:** НЕ загружай файлы `signing/family-guard-release.jks`, `signing/keystore.properties`, `signing/keystore.base64.txt` — ключ пойдёт в секреты (Шаг 4). Папки `android/app/build` и `android/.gradle` тоже не нужны.
3. Внизу **Commit changes**.

(Если умеешь работать с git — просто `git init`, `git add .`, `git commit`, `git push`. `.gitignore` уже исключит ключ.)

## Шаг 3. Подготовить ключ (делаешь ты — я не могу трогать ключ)
На своём компьютере в PowerShell, находясь в папке `family-guard`, выполни:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("signing\family-guard-release.jks")) | Set-Content signing\keystore.base64.txt -NoNewline
```

Откроется файл `signing/keystore.base64.txt` — его содержимое (одна длинная строка) понадобится в Шаге 4. После настройки секретов этот файл удали.

## Шаг 4. Добавить секреты
В репозитории: **Settings → Secrets and variables → Actions → New repository secret**. Создай 4 секрета:

| Имя секрета | Значение |
|---|---|
| `KEYSTORE_BASE64` | всё содержимое файла `signing/keystore.base64.txt` |
| `KEYSTORE_PASSWORD` | строка `storePassword=...` из `signing/keystore.properties` (только значение после `=`) |
| `KEY_ALIAS` | `family-guard` |
| `KEY_PASSWORD` | строка `keyPassword=...` из `signing/keystore.properties` (значение после `=`) |

## Шаг 5. Запустить сборку
1. Вкладка **Actions** → слева **Build Family Guard APK** → **Run workflow** → **Run**.
2. Через 3–6 минут сборка завершится (зелёная галочка).
3. Открой завершённый запуск → внизу раздел **Artifacts** → скачай **FamilyGuard-1.0.0-apks** (zip с lite и full APK).

Готово — внутри `FamilyGuard-1.0.0-lite-release.apk` (ставь его) и `FamilyGuard-1.0.0-full-release.apk`.

---

## Если сборка упала (красный крестик)
Открой упавший шаг, скопируй строки с `e:` или `error:` и пришли мне — поправлю. Так как я писал код без компилятора, возможна мелкая опечатка; её видно только при первой реальной сборке.

## Безопасность ключа
- Ключ (`.jks`) и пароли хранятся только в секретах GitHub — в коде их нет.
- Файл `keystore.base64.txt` удали после настройки.
- Никому не передавай `.jks` и пароли: без них нельзя выпускать обновления приложения, но и потеря/утечка ключа — это потеря контроля над подписью.
