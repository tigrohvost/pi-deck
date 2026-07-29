<div align="center">

# π//DECK

**Локальный coding agent для Android через Termux**
**A local Android coding agent powered by Termux**

[![build](https://img.shields.io/github/actions/workflow/status/tigrohvost/pi-deck/build.yml?branch=main)](https://github.com/tigrohvost/pi-deck/actions/workflows/build.yml)
[![license](https://img.shields.io/github/license/tigrohvost/pi-deck)](LICENSE)

</div>

Агент [Pi](https://github.com/earendil-works/pi) живёт в телефоне целиком.
Модель считает сам аппарат через встроенный `llama-server`, а shell, файлы и
сессии — в Termux, без root. Промпт никуда не уходит.

> [!WARNING]
> Локальный инференс — не сетевая изоляция. В зависимости от профиля доступа
> инструменты работают правами пользователя Termux и могут ходить в сеть.

## Как это выглядит

| Запрос на холодном ядре уходит сам | Агент выполняет команду |
|:--:|:--:|
| <img src="docs/screenshots/warm-start.png" alt="Запрос встал в очередь, ядро греется само" width="300"> | <img src="docs/screenshots/agent-shell.png" alt="Агент выполнил uname -m и вернул aarch64" width="300"> |
| **Ядро: модель, режим, автозапуск** | **Вторая палитра — DECK** |
| <img src="docs/screenshots/core-autostart.png" alt="Экран ЯДРО с переключателем автозапуска в палитре Nord" width="300"> | <img src="docs/screenshots/deck-core.png" alt="Экран ЯДРО в палитре DECK" width="300"> |

## Что внутри

- инференс — в foreground-сервисе самого приложения, поэтому открытая дека
  получает CPU-set `top-app`, а не фоновые ядра Termux;
- Pi, shell, python и сессии — в Termux; связь через authenticated RPC на
  `127.0.0.1` с 256-битным токеном;
- GGUF лежит в приватном хранилище приложения read-only, хэш проверяется при
  установке;
- нативный `llama.cpp b10092` для arm64 вшит в APK и сверяется по SHA-256 на
  каждой сборке.

## Требования

- Android 8.0 / API 26+, arm64;
- [Termux 0.118.0+ с F-Droid](https://f-droid.org/packages/com.termux/)
  (подпись проверяется, неизвестная блокируется);
- Node.js 22.19.0+ внутри Termux;
- место под GGUF: скачанный файл, приватная копия и запас.

## Быстрый старт

1. Установите Termux, выдайте PI//DECK разрешение `RUN_COMMAND`.
2. Выполните в Termux строку, которую дека копирует по кнопке `COPY + OPEN`:

   ```sh
   mkdir -p ~/.termux && \
     (grep -q '^allow-external-apps=true$' ~/.termux/termux.properties 2>/dev/null || \
     printf '\nallow-external-apps=true\n' >> ~/.termux/termux.properties) && \
     termux-reload-settings && \
     ([ -d "$HOME/storage/downloads" ] || termux-setup-storage)
   ```

3. `INSTALL CORE` — разворачивает Pi, Python и git. Ваш
   `~/.pideck/workspace/AGENTS.md` не перезаписывается.
4. Выберите модель, скачайте, дождитесь SHA-256 и нажмите `INSTALL PRIVATE`.
5. Дальше просто пишите запрос. Ядро прогреется само и отправит его, когда Pi
   поднимется. Кому нужен прогрев заранее — `ЯДРО → Автозапуск`.

Где что лежит:

```text
~/.pideck/workspace/     рабочая папка агента и AGENTS.md
~/.pideck/sessions/      сессии Pi
~/.pideck/runtime/       versioned Python/Pi runtime
~/.pideck/logs/          диагностика компонентов
Android app data/        приватная read-only GGUF и лог сервера
```

## Профили доступа

| Профиль | Поведение |
|---|---|
| `AUTONOMOUS` | **по умолчанию**; полный shell в правах Termux UID, без подтверждения каждой команды |
| `CONFIRM_CHANGES` | bash/edit/write требуют одноразового Android-подтверждения с TTL |
| `READ_ONLY` | только чтение, поиск и ограниченные `web_search`/`weather` |

Профиль меняется в `ЯДРО → Доступ`. Экран согласия показывается до первого
запуска и описывает ровно то, что агент сможет.

> [!WARNING]
> При обновлении с alpha7 дека, где профиль ни разу не меняли вручную,
> перейдёт на `AUTONOMOUS` сама. Это намеренное расширение прав обновлением.

Рабочая папка — удобная граница, но не песочница ОС. Закрытие UI, timeout,
disconnect или restart bridge означают отказ в подтверждении.

## Настройки в `ЯДРО`

- **Режим** — `Агент` (файлы и команды) или `Чат` (быстрый ответ без
  инструментов);
- **Автозапуск** — грузить модель при открытии деки; по умолчанию выключен,
  чтобы не жечь батарею, когда вы зашли перечитать переписку;
- **Системный промпт** — дополнить встроенный промпт Pi или заменить целиком;
- **Язык** — русский или английский, тексты пользователя и агента не трогаются;
- **Скорость** — не гасить экран во время ответа.

## Модели

Qwen3.5 0.8B/2B/4B/9B помечены `EXPERIMENTAL`: байты и SHA закреплены, но
полный provenance и device benchmark ещё не закончены. Дека не подменяет
неизвестный model ID рекомендацией.

Новая модель добавляется только через [`tools/pin_model.py`](tools/pin_model.py),
проверку лицензии и 28-задачный device benchmark. Популярность репозитория
основанием не является.

## Сборка и тесты

Нативные arm64-библиотеки в репозитории не хранятся — восстановите их из
закреплённого `llama.cpp b10092`:

```sh
ANDROID_NDK_ROOT="$ANDROID_HOME/ndk/27.1.12297006" \
  ./tools/vendor_llama_android.sh
```

```sh
./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64 \
  testDebugUnitTest lintDebug assembleDebug assembleRelease
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/runtime -v
python3 tools/validate_benchmark.py
```

JDK 21 обязателен: на JDK 25 Kotlin DSL падает при разборе версии JVM. Без
production keystore release APK намеренно остаётся unsigned — debug key в
release не подставляется никогда.

## English

PI//DECK runs the Pi coding agent entirely on the phone: inference in the app's
own foreground service, shell and sessions in Termux, no root, and an
authenticated loopback RPC bridge between them. Your prompt never leaves the
device.

Type a prompt at a cold deck and it is queued, warms the core itself, and is
dispatched as soon as Pi answers — no taps. `Core → Autostart` (off by default)
warms the model when the deck opens instead.

The default access profile is `AUTONOMOUS`: the agent runs shell commands and
edits files visible to the Termux user without a per-action approval, which is
what the consent screen describes before the first run. Upgrading a deck that
never changed the profile by hand moves it to `AUTONOMOUS` — an intentional
privilege change made by an update. `CONFIRM_CHANGES` and `READ_ONLY` are one
tap away in `Core → Access`.

Models are `EXPERIMENTAL` until provenance and a real-device benchmark are
complete. Release builds require externally supplied signing secrets, a
GitHub-verified signed tag, checksums and a CycloneDX SBOM.

## Документация

- [Changelog](CHANGELOG.md)
- [Architecture](docs/architecture.md)
- [Security model](docs/security-model.md)
- [RPC bridge](docs/rpc-bridge.md)
- [Model admission](docs/model-admission.md)
- [Compatibility matrix](docs/compatibility-matrix.md)
- [ADB performance evidence](docs/performance.md)
- [Release process](docs/release-process.md)
- [Architecture decisions](docs/adr/README.md)

## License

[MIT](LICENSE)
