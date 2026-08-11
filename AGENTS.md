# AGENTS.md — PunishNotify

Инструкции и правила для нейросети (ассистента), работающего с репозиторием PunishNotify.

---

## 📌 Обязательные правила

### 🧠 1. Использование Brain Vault (Minecraft-Brain)

> ⚠️ **ОБЯЗАТЕЛЬНЫЙ ШАГ** перед написанием нового кода или внесением изменений в код по Minecraft-разработке:
> 1. **ДО написания/изменения кода** — проверить Brain vault (`C:\Minecraft\Minecraft-Brain`) по теме задачи.
> 2. **ДО выхода в интернет** — проверить Brain vault.
> 3. Только если нужной информации в Brain нет — идти в интернет.
> *При простом чтении/анализе кода без изменений — Brain vault не обязателен.*

---

### 📝 2. Коммиты и пуши в Git

1. **После ЛЮБОГО изменения кода** (новая фича, багфикс, рефакторинг, настройки, доки) ты **ОБЯЗАН** закоммитить изменения и запушить их в GitHub.
2. Не оставляй незакоммиченные изменения. Рабочее дерево должно быть чистым (`git status` — чисто) после завершения каждой задачи.
3. Коммиты пиши по-русски, кратко и по делу: `git add -A; git commit -m "..."`.
4. Пушь сразу после коммита: `git push`.
5. Пушь в ветку `main` (в репозитории одна основная ветка).
6. Если локальные изменения конфликтуют с удалёнными — сначала сделай `git pull --rebase`, затем снова пушь.

---

### 🔨 3. Сборка

7. Сборка плагина: `./gradlew build` (артефакт — `build/libs/PunishNotify-<version>.jar`).
8. **Никогда не коммить** собранный `.jar` файл из `build/libs/` в этот репозиторий — только исходный код и графику `assets/`.

---

### 🚀 4. Публикация и Релизы (Modrinth & GitHub Releases)

9. **Когда задача требует выкладки новой версии плагина** (пользователь просит «сделай релиз», «зарелизь», «выложи версию» и т.п.):
   - Обнови номер версии в `build.gradle.kts` (`version`) и в `src/main/resources/plugin.yml` (`version`).
   - Актуализируй `README.md` (добавь новые команды/пермишены/конфиги) и описание проекта на **Modrinth**.
   - Закоммить и запушь изменения в `main`.
   - Создай Git-тег `v<версия>` по схеме `vMAJOR.MINOR.PATCH` (например `v1.2.0`), затем запуши тег:
     ```bash
     git tag v<версия>
     git push origin main --tags
     ```
10. Пуш тега запускает GitHub Actions workflow `.github/workflows/release.yml`, который автоматически соберёт плагин, создаст GitHub Release и опубликует релиз на **Modrinth**.
11. Также дублируй публикацию на **Modrinth** напрямую через CLI скилла `modrinth-api`:
    ```bash
    python C:\Users\Kiril\.gemini\config\skills\modrinth-api\scripts\modrinth_cli.py publish \
      --project punishnotify \
      --file build/libs/PunishNotify-<version>.jar \
      --version-number <version> \
      --name "PunishNotify v<version>" \
      --game-versions "1.21,1.21.1,1.21.2,1.21.3,1.21.4,1.21.11" \
      --loaders "paper,spigot,purpur,folia" \
      --version-type release \
      --changelog "<Описание изменений на русском и английском языках>" \
      --featured
    ```

---

### 🖥️ 5. Обновление сервера после релиза

12. **После каждого релиза ты ОБЯЗАН** скопировать свежий собранный плагин `build/libs/PunishNotify-<version>.jar` в папку сервера:
    `C:\Minecraft\Paper-1.21.11\plugins\PunishNotify.jar` (перезаписать старый файл).
13. Затем в репозитории сервера `C:\Minecraft\Paper-1.21.11` закоммить это изменение и запушить в репозиторий сервера (`git add -A; git commit -m "Update PunishNotify.jar to v<version>"; git push`). Пушь в основную ветку этого репозитория.
14. Если после обновления сервер нужно перезапустить — действуй по инструкциям из AGENTS.md сервера (в `C:\Minecraft\Paper-1.21.11` / на VPS через `./mcs.sh`).

---

### 📚 6. Поддержка README.md и описания на Modrinth

15. **Каждый раз при добавлении новых механик, команд, пермишенов или параметров конфигурации**:
    - **В обязательном порядке** актуализируй `README.md` в этом репозитории (поддерживай двуязычный формат EN/RU с детальными таблицами команд и конфигов).
    - **При необходимости** (изменение описания, добавление функций, изменение гайдов) актуализируй описание страницы проекта на **Modrinth**, обновив проект вызовом `PATCH /project/punishnotify` с новым содержимым `body` из `README.md` или вызовом CLI:
      ```bash
      python C:\Users\Kiril\.gemini\config\skills\modrinth-api\scripts\modrinth_cli.py create-project \
        --slug punishnotify \
        --title PunishNotify \
        --summary "Discord punishment notification plugin with interactive web evidence attachment for Paper" \
        --readme README.md \
        --categories "management,utility" \
        --project-type mod
      ```

---

## 🏗️ Архитектура плагина PunishNotify

```
PunishNotifyPlugin             — Главный JavaPlugin класс (life-cycle, event registration)
├── PendingPunishment          — POJO отслеживаемого наказания (UUID, тип, причина, модератор)
├── PunishmentType             — Enum типов наказаний (BAN, TEMPBAN, UNBAN, MUTE, UNMUTE, KICK, WARN, JAIL, UNJAIL)
├── evidence/
│   ├── EvidenceManager        — Управление токенами загрузки, таймаутами и файлами доказательств
│   └── HttpUploadServer       — Встроенный Java HttpServer для веб-интерфейса Drag & Drop
├── webhook/
│   └── DiscordWebhookService  — Формирование Discord Embed и отправка вебхуков с повторными попытками (Retry Queue)
├── listener/                  — Перехват событий Bukkit / EssentialsX (BanListener, MuteListener и др.)
└── command/                   — Команда /punishnotify <reload|skip>
```

---

## 📋 Порядок действий для релиза (Чек-лист)

1. Обнови версию в `build.gradle.kts` и `plugin.yml`.
2. Актуализируй `README.md` (новые механики/команды/пермишены/конфиги) и описание проекта на Modrinth.
3. Собери плагин: `./gradlew build`.
4. `git add -A; git commit -m "Релиз vX.Y.Z"; git push`.
5. `git tag vX.Y.Z; git push origin main --tags`.
6. Опубликуй на Modrinth через CLI `modrinth-api`.
7. Скопируй `build/libs/PunishNotify-X.Y.Z.jar` в `C:\Minecraft\Paper-1.21.11\plugins\PunishNotify.jar`, закоммить и запушить в репозиторий сервера.

---

## ⚠️ Важно

- Никогда не коммить: `build/`, `bin/`, `.gradle/`, `.classpath`, `.project`, `.settings/`, `logs/` (см. `.gitignore`).
- Не пушить с `--force`, не переписывать историю.
- Все изменения вноси сначала локально, собирай и проверяй локально, затем коммить/пушь/релизь.
