# Monolith Vanilla Launcher — Production-Grade Android Minecraft Runner

Лаконичный, строго горизонтальный (Landscape) мобильный лаунчер и среда исполнения для всех исторических и актуальных Vanilla-версий Minecraft (от Pre-Classic/Classic/Infdev/Alpha/Beta до современных Releases и Snapshots) на базе архитектуры PojavLauncher Core и строгого дизайна Material 3.

## User Review & Critical Decisions

> [!IMPORTANT]
> Ниже зафиксированы архитектурные и UX-решения, подтвержденные на этапе уточнения требований, которые определяют работу среды запуска, AI-инструментов и горизонтальной сетки интерфейса.

- **Confirmed Decision 1 — Среда запуска и PojavLauncher Core**: Реализуется реальный сетевой загрузчик официального `Mojang Version Manifest V2` (с проверкой SHA-1 и загрузкой метаданных/ассетов) в связке с низкоуровневым мостом `ProcessBuilder` (OpenJDK 8/17/21 + рендереры GL4ES / VirGL / ANGLE) и интерактивной песочницей исполнения с живой консолью логов и 3D/GL-превью, работающей даже до импорта внешних архивов `tar.xz`.
- **Confirmed Decision 2 — Гибридный AI-движок**: Все три ассистента (**AI Crash & Log Analyzer**, **AI Hardware Tuner**, **AI Skin Generator**) работают мгновенно и автономно за счет детерминированных эвристик, аппаратного профилирования Android (`ActivityManager`, `Build.SOC_MODEL`, GPU renderer) и процедурного пиксельного синтеза `64x64`, а при наличии ключа `GEMINI_API_KEY` автоматически обогащаются глубокой аналитикой через `gemini-3.5-flash`.
- **Confirmed Decision 3 — Строго горизонтальная (Landscape) навигация**: Приложение фиксируется в `sensorLandscape` с полноэкранным режимом (`enableEdgeToEdge`). Слева располагается постоянный `NavigationRail`, а основное пространство разделено на адаптивную двухпанельную сетку (Master-Detail / Control-Preview).

---

## 1. Overview & Core Concept

- **What It Does**: Обеспечивает полный цикл запуска чистых (Vanilla) версий Minecraft на Android: парсинг и фильтрацию всех исторических эпох Mojang, автоматический подбор среды OpenJDK (Java 8 для до-1.16.5, Java 17 для 1.17–1.20.4, Java 21 для 1.20.5+), авторизацию через Microsoft/Xbox Live OAuth2 или Offline-профиль, кастомизацию экранного управления (Touch Overlay), редактирование 2D/3D скинов (Mojang API + Ely.by) и интеллектуальную диагностику крашей и JVM-флагов.
- **Target Audience / Persona**: Мобильные игроки, технические энтузиасты и ценители оригинального Vanilla Minecraft, которым необходим быстрый, чистый, предсказуемый инструмент уровня официального десктопного лаунчера без визуального шума и сторонних мод-лоадеров.
- **Key Value**: Объединение нативного раннера уровня PojavLauncher, тактильного редактора раскладки управления, полноценной студии скинов (64x64 / 64x32 + Ely.by) и инженерных AI-утилит в едином премиальном горизонтальном интерфейсе.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Главная панель запуска (Play & Version Hub)**: В левой колонке — логотип лаунчера с физически покачивающимся желтым сплэш-текстом (`splashes.txt`), переключатель эпох (`Releases`, `Snapshots`, `Beta`, `Alpha`, `Infdev`, `Classic`, `Pre-Classic`), список версий из реального API Mojang и карточка выбранного релиза (требуемая версия Java, графический бэкенд GL4ES/ANGLE, статус загрузки ассетов). В правой нижней части — крупная кнопка запуска с индикатором прогресса верификации SHA-1 и переходом в игровую сессию.
  2. **Игровая сессия и Touch Controller Overlay**: Полноэкранный режим с интерактивным GL/Canvas-рендером, вызываемой консолью `latest.log`, оверлеем производительности (FPS, память JVM, рендерер GPU) и виртуальным контроллером (аналоговый стик WASD, зона обзора, контекстные клавиши `Jump`, `Sneak`, `Break`, `Place`, `Inv`, `Chat`, `F3`, `F5`, `Tab`, `Esc`).
  3. **Интерактивный редактор раскладки (Controller Studio)**: Перемещение любой кнопки методом drag-and-drop по сетке, индивидуальная настройка размера (`50%–180%`) и прозрачности (`15%–100%`), тактильная отдача `HapticFeedback`, сохранение и переключение профилей раскладки.
  4. **Студия скинов (Skin & Cape Studio — Mojang + Ely.by)**: Двухпанельный экран. Слева — интерактивное 3D-превью модели игрока (Steve 4px / Alex 3px, вращение, анимация ходьбы, переключение второго слоя Overlay) и загрузка текстуры по никнейму через Mojang API или Ely.by. Справа — попиксельный 2D-холст (`64x64` и `64x32`) с инструментами карандаша, заливки, пипетки, ластика, генератором AI-скина по текстовому промпту и экспортом PNG.
  5. **Аккаунты (Dual Auth Selector)**: Переключение между профилями Microsoft OAuth2 (Xbox Live / XSTS / Minecraft Services flow) и локальными Offline-аккаунтами (детерминированная генерация `UUID v3` по алгоритму `OfflinePlayer:<nickname>`), с поддержкой нескольких сохраненных профилей.
  6. **AI Инженерный центр (Crash Analyzer & JVM Hardware Tuner)**:
     - *Hardware Tuner*: Сканирует доступный объем ОЗУ устройства, количество ядер CPU и модель мобильного GPU, автоматически формируя аргументы JVM (`-Xmx`, `-XX:+UseG1GC` или `-XX:+UseZGC`, флаги Aikar) для целевых 60/120 FPS.
     - *Crash & Log Analyzer*: Парсит стектрейсы (`OutOfMemoryError`, `UnsupportedClassVersionError`, `GLFW/GL4ES shader errors`, `Corrupted asset hash`), выводит понятный вердикт и кнопку **«Исправить в 1 клик»** (автоматически меняет версию OpenJDK, корректирует выделенную ОЗУ или переключает рендерер с VirGL на GL4ES).

- **Visual Identity & Theme**:
  - *Aesthetic Direction*: Строгий утилитарный минимализм в духе официального инженерного инструмента (Monolith Dark / Material You). Полный отказ от кислотных градиентов и перегруженных декоративных рамок.
  - *Color Palette & Mood*:
    - `--background`: Глубокий базальтовый графит (`#0E1113`) и темно-сланцевый фон панелей (`#151A1E`).
    - `--surface-elevated`: Матовый обсидиан (`#1D242B`) с тонкими контурами `1.dp` (`#2B353F`).
    - `--primary`: Фирменный изумрудный акцент Vanilla (`#38C76A`) для ключевых действий и активных индикаторов.
    - `--accent`: Классический золотисто-желтый (`#FFD84D`) исключительно для анимированного сплэш-текста и маркеров предупреждений.
    - `--danger`: Терракотово-красный (`#F05454`) для краш-репортов и ошибок процесса JVM.
  - *Typography & Hierarchy*:
    - Заголовки и навигация: Геометрический гротеск `Space Grotesk` (локально подключенный через `font-util`).
    - Основной интерфейс: Чистый и читаемый `Inter` / системный Sans.
    - Консоль JVM, флаги, SHA-1 хеши и стектрейсы: Моноширинный `JetBrains Mono`.
  - *Component Styling & Layout*: Строгая модульная сетка с отступами, кратными `8.dp` / `16.dp`, скругления карточек `12.dp`, четкое разделение левого навигационного рельса и двух рабочих колонок в горизонтальной ориентации.

- **Interactive Feedback & Motion**:
  - Непрерывная пружинная/синусоидальная пульсация сплэш-текста (`infiniteRepeatable` scale + легкий наклон `-12°`).
  - Тактильный отклик (`LocalHapticFeedback`) при нажатии игровых клавиш оверлея и привязке кнопок к сетке в редакторе контроллера.
  - Плавные переходы между вкладками и состояниями загрузки ассетов через `AnimatedContent` и `spring()`.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Многомодульная архитектура Gradle (`:app`, `:pojav-runtime`, `:auth`, `:controller`, `:skin-editor`)**
  - *Chosen Approach*: Разделение кодовой базы на 5 специализированных Gradle-модулей с чистыми публичными контрактами.
  - *Why*: Обеспечивает строгую изоляцию ответственности (рантайм и `ProcessBuilder` отделены от Compose-оверлея контроллера и графического движка скинов), ускоряет инкрементальную сборку и полностью соответствует требованиям промышленной архитектуры.
  - *Alternatives Considered*: Монолитный модуль `:app` — отклонен, так как смешивает низкоуровневую логику запуска JVM, сетевую авторизацию и UI-редакторы.

- **Decision 2: Автоматический маршрутизатор версий Java (OpenJDK 8 / 17 / 21)**
  - *Chosen Approach*: Детерминированный селектор среды исполнения на основе метаданных версии Mojang (`javaVersion.majorVersion`) и даты релиза (до `1.16.5` -> JRE 8; `1.17`–`1.20.4` -> JRE 17; `1.20.5+` и новые снапшоты -> JRE 21) с возможностью ручного переопределения в настройках.
  - *Why*: Исключает самую частую причину падения мобильных лаунчеров (`UnsupportedClassVersionError`), сохраняя полный контроль для опытных пользователей.
  - *Alternatives Considered*: Единый JRE 21 для всех версий — отклонен, так как старые версии Alpha/Beta/1.7.10 требуют `LegacyLauncher` и специфичных классов Java 8.

- **Decision 3: Реальный сетевой клиент Mojang Manifest V2 + локальное кеширование**
  - *Chosen Approach*: Загрузка живого списка версий с `https://piston-meta.mojang.com/mc/game/version_manifest_v2.json` с резервным встроенным каталогом знаковых исторических версий (rd-132211, c0.30, inf-20100618, a1.2.6, b1.7.3, 1.7.10, 1.8.9, 1.12.2, 1.16.5, 1.20.4, 1.21.4) на случай отсутствия сети.
  - *Why*: Гарантирует 100% работоспособность как при живом интернет-соединении, так и в полностью автономном режиме.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Architecture & Component Diagram**:

```
┌───────────────────────────────────────────────────────────────────────────────────┐
│                 :app (Landscape UI Shell, NavigationRail, AI Hub)                 │
│  ┌─────────────────┐  ┌──────────────────┐  ┌─────────────────┐  ┌──────────────┐ │
│  │ Version Hub &   │  │ AI Crash & JVM   │  │ Game Session &  │  │ Settings &   │ │
│  │ Splash Header   │  │ Hardware Tuner   │  │ Live Log Drawer │  │ JRE Manager  │ │
│  └────────┬────────┘  └────────┬─────────┘  └────────┬────────┘  └──────┬───────┘ │
└───────────┼────────────────────┼─────────────────────┼──────────────────┼─────────┘
            │                    │                     │                  │
     ┌──────▼──────────┐  ┌──────▼──────────┐   ┌──────▼──────────┐ ┌─────▼────────┐
     │ :pojav-runtime  │  │      :auth      │   │   :controller   │ │ :skin-editor │
     ├─────────────────┤  ├─────────────────┤   ├─────────────────┤ ├──────────────┤
     │ • Mojang V2 API │  │ • MS OAuth2 PKCE│   │ • Virtual Stick │ │ • 64x64/64x32│
     │ • JRE 8/17/21   │  │ • Xbox/XSTS/MC  │   │ • Key Bindings  │ │   2D Canvas  │
     │ • GL4ES / ANGLE │  │ • Offline UUIDv3│   │ • Drag & Drop   │ │ • 3D Preview │
     │ • ProcessBuilder│  │ • Profile Store │   │   Layout Editor │ │ • Mojang &   │
     │ • Asset Verifier│  │                 │   │ • Haptic Engine │ │   Ely.by API │
     └─────────────────┘  └─────────────────┘   └─────────────────┘ └──────────────┘
```

- **Data Model & State**:
  - `MinecraftVersion`: `id`, `type` (`release`, `snapshot`, `old_beta`, `old_alpha`, `classic`, `pre_classic`, `infdev`), `url`, `releaseTime`, `sha1`, `requiredJavaMajor` (`8`, `17`, `21`), `isInstalled`.
  - `LauncherAccount`: `id`, `username`, `uuid`, `accountType` (`MICROSOFT`, `OFFLINE`, `ELY_BY`), `accessToken`, `skinUrl`, `isSlimModel`.
  - `ControllerButtonConfig`: `id`, `label`, `keyCode` (`GLFW_KEY_*`), `normalizedX`, `normalizedY`, `scale`, `alpha`, `isToggleable`, `isVisible`.
  - `JvmRuntimeConfig`: `selectedJavaVersion`, `renderer` (`GL4ES_114`, `VIRGL_MESA`, `ANGLE_VULKAN`), `allocatedMemoryMb`, `gcType` (`G1GC`, `ZGC`, `PARALLEL`), `customFlags`.
  - `SkinDocument`: `width` (`64`), `height` (`64` или `32`), `pixels` (`IntArray`), `showOverlayLayer`, `modelVariant` (`CLASSIC_4PX`, `SLIM_3PX`).
  - `CrashDiagnosis`: `errorCategory`, `summaryTitle`, `technicalReason`, `recommendedActionLabel`, `autoFixPayload`.

- **Interactive Component & State Mapping**:
  - **Выбор и фильтрация версий**: Переключение чипов эпох (`Release`, `Snapshot`, `Beta`, `Alpha`, `Classic`) мгновенно фильтрует поток `StateFlow<List<MinecraftVersion>>` и обновляет карточку совместимости JRE/GL-рендерера.
  - **Запуск и `ProcessBuilder` пайплайн**: Нажатие `LAUNCH` запускает проверку/скачивание манифеста версии, формирует массив окружения (`LIBGL_ES=2`, `POJAV_RENDERER`, `JAVA_HOME`, `LD_LIBRARY_PATH`) и командную строку JVM через `PojavProcessLauncher`, переводит экран в режим игровой сессии с активным `TouchControllerOverlay` и потоковым чтением `stdout/stderr` в консоль.
  - **Редактор контроллера**: Включение режима `Edit Layout` переводит кнопки оверлея в интерактивные `pointerInput(detectDragGestures)` узлы; выбор любого узла открывает компактную инспектор-панель со слайдерами `Scale`, `Opacity` и переключателем `Sticky Toggle`, сохраняя координаты в нормализованном диапазоне `[0f..1f]` для независимости от разрешения экрана.
  - **2D/3D Редактор скинов и AI-генератор**: Касание по сетке `64x64` мутирует буфер пикселей с историей `Undo/Redo` и синхронно перерисовывает 3D-проекцию частей тела (`Head`, `Body`, `Arms`, `Legs` + `Overlay`). Кнопка загрузки по нику запрашивает профиль в `Ely.by` / `Mojang API`, декодирует PNG-развертку и помещает её на холст.
  - **AI Crash Analyzer & 1-Click Fix**: При обнаружении ошибки в `latest.log` (или при выборе диагностического сценария) анализатор выделяет проблемную строку и при нажатии «Применить исправление» атомарно обновляет `JvmRuntimeConfig` (например, снижает `-Xmx` при нехватке нативной кучи или переключает Java 8 -> Java 21) и перезапускает профиль.
