<div align="center">

# π//DECK

**Локальный coding agent для Android через Termux**
**A local Android coding agent powered by Termux**

[![build](https://img.shields.io/github/actions/workflow/status/tigrohvost/pi-deck/build.yml?branch=main)](https://github.com/tigrohvost/pi-deck/actions/workflows/build.yml)
[![license](https://img.shields.io/github/license/tigrohvost/pi-deck)](LICENSE)

</div>

PI//DECK is a native Android Views console for a pinned
[Pi](https://github.com/earendil-works/pi) coding agent. Inference runs on the
phone through `llama-server`; the agent runtime lives in Termux without root.

> Local inference is not network isolation. Depending on the selected access
> profile, tools running as the Termux user may access files and the network.

## Русский

### Что изменено в 0.3.0-alpha1

- каждый callback, event, watchdog и abort связан с полным UUIDv4
  `operationId`;
- операции сохраняются атомарно по отдельным app-private файлам, поздний
  результат не завершает новый turn;
- prompt передаётся по authenticated RPC, а не через argv;
- Pi закреплён на `@earendil-works/pi-coding-agent@0.82.1` с npm integrity и
  опубликованным shrinkwrap; `@latest` не используется;
- `llama-server` и Pi управляются только по проверенной PID/process-group
  identity; чужой процесс на порту не завершается;
- GGUF копируется из shared incoming в приватный Termux store с повторным
  SHA-256, fsync, atomic rename и полным hash перед каждым новым стартом;
- Android UI учитывает нижний system-bar inset на edge-to-edge Android;
- один строгий `models-v2.json` задаёт artifact, runtime и sampling;
- потоковые ответы, reconnect/event journal и структурированный abort работают
  через локальный Pi RPC bridge;
- доступны `READ_ONLY`, `CONFIRM_CHANGES` и явно подтверждаемый `AUTONOMOUS`.

### Требования

- Android 8.0 / API 26 или новее, 64-битный ABI;
- Termux `0.118.0+` из
  [F-Droid](https://f-droid.org/packages/com.termux/);
- Node.js `22.19.0+` внутри Termux;
- свободное место для incoming GGUF, приватной копии, временного файла и
  safety margin.

Приложение проверяет package name, версию и signing certificate Termux. GitHub
build с публичным shared test key распознаётся отдельно и показывает
предупреждение; неизвестная подпись блокируется.

### Первый запуск

1. Установите Termux и выдайте PI//DECK разрешение `RUN_COMMAND`.
2. Выполните один раз скопированную приложением команду:

   ```sh
   mkdir -p ~/.termux && \
     (grep -q '^allow-external-apps=true$' ~/.termux/termux.properties 2>/dev/null || \
     printf '\nallow-external-apps=true\n' >> ~/.termux/termux.properties) && \
     termux-reload-settings && \
     ([ -d "$HOME/storage/downloads" ] || termux-setup-storage)
   ```

3. Нажмите `INSTALL CORE`. Installer создаёт versioned runtime и не
   перезаписывает пользовательский `~/.pideck/workspace/AGENTS.md`.
4. Выберите модель, скачайте её, дождитесь Android SHA-256 и нажмите
   `INSTALL PRIVATE`.
5. Запустите server и authenticated Pi RPC bridge.

Пути:

```text
~/.pideck/runtime/       versioned Python/Pi runtime
~/.pideck/models/        private read-only GGUF
~/.pideck/workspace/     agent workspace and AGENTS.md
~/.pideck/sessions/      Pi sessions
~/.pideck/logs/          private component diagnostics
```

### Профили доступа

| Профиль | Поведение |
|---|---|
| `READ_ONLY` | default; только `read`, `grep`, `find`, `ls` |
| `CONFIRM_CHANGES` | mutating built-ins отключены; bash/edit/write требуют одноразового Android approval с TTL |
| `AUTONOMOUS` | явный high-risk opt-in; полный shell в пределах прав Termux UID |

Workspace — удобная рабочая директория, но не системная песочница. Закрытие UI,
timeout, disconnect, duplicate approval или restart bridge означают deny.

### Модели

Текущие Qwen3.5 0.8B/2B/4B/9B сохранены для совместимости, но остаются
`EXPERIMENTAL`: их bytes/SHA закреплены, а полный conversion provenance и
актуальный device benchmark ещё не завершены. Приложение не подменяет
неизвестный model ID рекомендацией.

Новые модели добавляются только через
[`tools/pin_model.py`](tools/pin_model.py), проверку лицензии/provenance и
28-задачный device benchmark. Название репозитория или публичный leaderboard не
являются admission gate.

### Сборка и тесты

```sh
./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64 \
  testDebugUnitTest lintDebug assembleDebug assembleRelease
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests/runtime -v
python3 tools/validate_benchmark.py
```

Без production keystore release APK намеренно остаётся unsigned. Debug key
никогда не подставляется в release. Процесс подписанного релиза описан в
[`docs/release-process.md`](docs/release-process.md).

## English

PI//DECK 0.3.0-alpha1 hardens operation identity, process supervision, private
GGUF installation and runtime updates, then moves interactive turns to Pi
0.82.1's authenticated JSONL RPC bridge. Prompts are sent in request bodies,
not process arguments. Streaming events survive Activity recreation, and
unknown outcomes are reconciled without automatic replay.

The default `READ_ONLY` profile has no mutating tools. `CONFIRM_CHANGES`
disables the built-in mutators and exposes one-time approval-gated equivalents.
`AUTONOMOUS` is an explicit high-risk opt-in with all permissions available to
the Termux UID.

All existing Qwen3.5 entries are conservatively marked `EXPERIMENTAL`; no model
is promoted without complete artifact provenance and a real-device report.
Production releases require externally supplied signing secrets, a
GitHub-verified signed tag, APK signature verification, checksums and a
CycloneDX SBOM.

## Documentation

- [Architecture](docs/architecture.md)
- [Security model](docs/security-model.md)
- [RPC bridge](docs/rpc-bridge.md)
- [Model admission](docs/model-admission.md)
- [Compatibility matrix](docs/compatibility-matrix.md)
- [Release process](docs/release-process.md)
- [Implementation baseline](docs/implementation-baseline.md)
- [Implementation report](IMPLEMENTATION_REPORT.md)
- [Architecture decisions](docs/adr/README.md)

## License

[MIT](LICENSE)
