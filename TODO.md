# TODO: Улучшения для Scala AI Platform

## 0. Недавно завершенные задачи

- [x] **Улучшение обработки файлового контекста:** Добавлен механизм сбора, валидации и передачи файлового контекста в запросы к AI модели.
- [x] **Подтверждение добавления контекста:** Добавлено диалоговое окно с предпросмотром и подтверждением перед добавлением содержимого файла в контекст.
- [x] **Исправление работы с Flash моделями:** Улучшен механизм переключения моделей с добавлением умного поиска подходящих вариантов и fallback механизмов.

## 1. Расширенное Управление Контекстом Файлов (Вдохновлено Shotgun)

### 1.1. Интерактивное Дерево Файлов и Каталогов

-   **Задача:** Реализовать отображение иерархической структуры файлов и каталогов выбранной директории в отдельной панели UI.
-   **Требования:**
    -   [ ] Отображение древовидной структуры (например, с использованием `TreeView` в ScalaFX).
    -   [ ] Возможность разворачивать/сворачивать узлы каталогов.
    -   [ ] Отображение иконок для файлов и каталогов.
    -   [ ] **Выбор элементов:** Реализовать возможность отмечать (например, чекбоксами) файлы и каталоги в дереве.
        -   [ ] При выборе каталога - автоматически выбирать все вложенные файлы (с возможностью ручного снятия отметки с отдельных элементов).
    -   [ ] Кнопка/действие для загрузки выбранной директории в дереве.
    -   [ ] Интеграция с новым механизмом подтверждения добавления файлового контекста.
-   **Затрагиваемые компоненты:**
    -   Новый UI компонент: `FileTreeView.scala` (или аналогичный).
    -   Модификация `FileManager.scala` для сканирования директорий и построения модели дерева.
    -   Интеграция в `MainController.scala` и основной макет UI (например, в левую панель рядом с `HistoryPanel` или как отдельная вкладка/секция).
    -   Интеграция с существующей инфраструктурой обработки файлов для получения и форматирования контекста.

### 1.2. Отображение и Использование Содержимого Выбранных Файлов

-   **Задача:** Предоставить пользователю возможность просматривать содержимое выбранных в дереве файлов и использовать это содержимое как контекст для AI-запросов.
-   **Требования:**
    -   [ ] При выборе файла в дереве (или по специальному действию) отображать его содержимое в отдельной области просмотра (возможно, с подсветкой синтаксиса для известных типов файлов).
    -   [ ] **Сбор контекста:** Механизм для сбора содержимого всех отмеченных в дереве файлов в единый текстовый блок.
    -   [ ] Возможность добавить собранный файловый контекст к основному запросу пользователя в `Footer` (например, в `inputTextArea` или в специальное поле контекста).
-   **Затрагиваемые компоненты:**
    -   `FileTreeView.scala` (для обработки выбора файла).
    -   `FileManager.scala` (для чтения содержимого файла).
    -   `MainController.scala` (для управления состоянием выбранного контекста).
    -   Модификация `Footer.scala` или новый UI-элемент для отображения/вставки контекста.
    -   Возможно, `ResponseArea.scala` или новый компонент для предпросмотра файла.

### 1.3. Игнорирование Файлов и Каталогов

-   **Задача:** Реализовать функционал игнорирования ненужных файлов и каталогов при построении дерева и сборе контекста (аналогично `node_modules`, `.git` в Shotgun).
-   **Требования:**
    -   [ ] Список исключений по умолчанию (например, `.git`, `target`, `node_modules`).
    -   [ ] Возможность для пользователя добавлять собственные правила/паттерны исключений (например, через настройки или файл `.aiplatformignore`).
    -   [ ] Визуальное отображение в дереве, что элемент исключен (например, серый цвет, зачеркивание).
-   **Затрагиваемые компоненты:**
    -   `FileManager.scala` (логика фильтрации).
    -   `FileTreeView.scala` (визуализация).
    -   Возможно, `SettingsView.scala` для настройки правил.

## 2. Гибкая Логика Пайплайнов Обработки Запросов

-   **Задача:** Ввести понятие "пайплайна" (конвейера) для более гибкой и настраиваемой обработки AI-запросов. Пайплайн определяет последовательность шагов от получения ввода пользователя до отображения результата.
-   **Требования:**
    -   [ ] **Определение структуры пайплайна:**
        -   Создать модели `Pipeline.scala`, `PipelineStep.scala`.
        -   Шаги могут включать:
            -   `ContextAssemblyStep`: Сбор контекста (из текущего ввода, выбранных файлов в дереве, истории диалога).
            -   `PromptTemplatingStep`: Применение шаблона промпта (из `PresetManager`) к собранному контексту и вводу пользователя.
            -   `PreProcessingStep`: Любая предварительная обработка текста промпта (например, очистка, добавление системных инструкций).
            -   `AICallStep`: Непосредственный вызов `AIService`.
            -   `PostProcessingStep`: Обработка ответа AI (например, извлечение кода, форматирование).
            -   `ResponseDisplayStep`: Отображение результата в `ResponseArea`.
    -   [ ] **Конфигурация пайплайнов:**
        -   Позволить пользователю выбирать/создавать различные пайплайны (например, "Простой запрос", "Запрос с файловым контекстом", "Генерация кода по файлам").
        -   Связать пайплайны с пресетами или категориями в `Header`.
    -   [ ] **Выполнение пайплайнов:**
        -   Модифицировать `RequestExecutionManager.scala` для выполнения выбранного пайплайна вместо текущей жестко закодированной логики.
-   **Затрагиваемые компоненты:**
    -   Новые классы: `Pipeline.scala`, `PipelineStep.scala` (и его наследники для разных типов шагов).
    -   Модификация `RequestExecutionManager.scala`.
    -   Модификация `PresetManager.scala` и `AppState.scala` для хранения конфигураций пайплайнов.
    -   Модификация `SettingsView.scala` или создание нового UI для управления пайплайнами.
    -   Модификация `MainController.scala` для выбора и запуска пайплайнов.

## 3. Улучшения UI/UX

-   [ ] **Индикация Загрузки/Обработки:** Более явная индикация при длительных операциях (сканирование большой директории, выполнение сложного пайплайна).
-   [ ] **Обратная связь:** Улучшить обратную связь пользователю о выбранных файлах, размере контекста и т.д.
-   [ ] **Настройки:** Добавить раздел в `SettingsView.scala` для конфигурации нового функционала (пути по умолчанию для дерева файлов, правила исключений, управление пайплайнами).
-   [ ] **Улучшение диалогов подтверждения:** Доработать диалоги подтверждения добавления файлов в контекст с возможностью настройки параметров обработки текста.

## 4. Рефакторинг и Технический Долг

-   [ ] Провести ревизию текущей логики `FileManager.scala` на предмет интеграции с новым деревом файлов.
-   [ ] Обеспечить корректное обновление состояния (`AppState.scala`) при работе с новым функционалом.
1.  **Реализовать сжатие папки в ZIP:**
    * **Описание:** При нажатии кнопки "Прикрепить папку" (📁) необходимо реализовать рекурсивный сбор всех файлов и подпапок, их сжатие в ZIP-архив и последующее прикрепление этого архива (вместо простого перечисления файлов).
    * **Компоненты:** `FileManager.scala`.
    * **Сложность:** Средняя/Высокая (требует работы с `java.util.zip` или аналогами).

2.  **Диагностировать и исправить проблему с отображением кнопок футера:**
    * **Описание:** Кнопки "➕", "📎", "📁" не видны пользователю. Необходимо выяснить причину (CSS, Layout, рендеринг) и исправить.
    * **Компоненты:** `main.css`, возможно `Footer.scala`, `MainController.scala`.
    * **Сложность:** Низкая/Средняя (в зависимости от причины).

3.  **Добавить настройку горячих клавиш:**
    * **Описание:** Реализовать функционал и UI для настройки пользовательских горячих клавиш (вкладка "Горячие клавиши" в настройках сейчас является плейсхолдером).
    * **Компоненты:** `SettingsView.scala`, `MainController.scala`, возможно новый менеджер.
    * **Сложность:** Высокая.

4.  **Улучшить обработку ошибок UI:**
    * **Описание:** Добавить более детальное логирование и, возможно, отображение сообщений об ошибках при инициализации или обновлении компонентов UI (например, если не удалось загрузить CSS).
    * **Компоненты:** `MainApp.scala`, `MainController.scala`, компоненты View.
    * **Сложность:** Средняя.

5.  **Рефакторинг `SettingsView`:**
    * **Описание:** Класс `SettingsView.scala` достаточно большой. Рассмотреть возможность вынесения логики работы с пресетами или маппингами в отдельные классы-хелперы для улучшения читаемости.
    * **Компоненты:** `SettingsView.scala`.
    * **Сложность:** Средняя.

4.  **[УЛУЧШЕНИЕ] Блокировка повторного запроса**
    * **Требование:** Запретить отправку нового запроса в текущем топике, пока не получен ответ на предыдущий.
    * **Действие:** Реализовать механизм блокировки/разблокировки поля ввода и кнопки "Отправить" в `Footer` на время выполнения `Future` в `RequestExecutionManager`. Управлять состоянием блокировки из `MainController`.

5.  **[ФУНКЦИОНАЛ] Автоматическое обновление заголовка топика**
    * **Требование:** После первого запроса в новом топике, автоматически сгенерировать краткий заголовок (2-5 слов) с помощью AI и обновить его в `HistoryPanel`.
    * **Действие:** Убедиться, что `TopicManager.generateAndSetTopicTitle` вызывается корректно (только один раз для новых топиков) и результат обновления отображается в `HistoryPanel` без задержек и артефактов.

6.  **[ФУНКЦИОНАЛ] Реализовать вкладку "Горячие клавиши"**
    * **Требование:** Отобразить текущие биндинги и, возможно, позволить их настройку.
    * **Действие:** Заменить плейсхолдер в `SettingsView` реальной реализацией.

## Прочие Задачи (из Bugs.md / TODO.md)

7.  **[РЕСУРСЫ] Добавить иконки:** Убедиться, что файлы иконок (`plus-circle.png`, `paperclip.png`, `folder-open.png`) находятся в `src/main/resources/icons` и доступны приложению. (Логи указывали на их отсутствие).
8. **[КОНФИГУРАЦИЯ] Кнопка "Global":** Проверить корректность работы режима "Global" (отправка запроса без применения шаблона пресета категории).
9. **[СТИЛИ] Стилизация кода:** Проверить визуальное отображение блоков кода в `ResponseArea` на соответствие требованиям (темный фон, светлый текст, кнопки копирования). (Код реализует это, нужна визуальная проверка).