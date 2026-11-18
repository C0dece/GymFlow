# Живой реестр состояния проекта GymFlow

## Цель
Реализовать быстрое переключение тренировочного дня (в активной тренировке и на карточке главного экрана), поддержку смены дня со сдвигом/без сдвига, сохранение состояния участников, предотвращение дубликатов, возможность исправления результатов завершённых тренировок с пересчётом прогрессии. Опубликовать проект на GitHub и выпустить релиз для скачивания.

## Сделано
- На карточке главного экрана (`app/src/main/java/app/gymflow/ui/GymFlow.kt`): рядом с названием тренировки (как в активном состоянии, так и в запланированном «По расписанию», как показано на скриншоте) добавлена компактная кнопка смены тренировочного дня `home_switch_day`.
- В шторке выбора запланированного дня (`app/src/main/java/app/gymflow/ui/PlannedDaySheet.kt`): отображаются все доступные дни текущей программы с названием, количеством упражнений и состоянием (запланирована, активна, завершена). Пользователь может выбрать день «Только на эту дату» (без сдвига расписания цикла) или «Со сдвигом».
- На экране активной тренировки (`app/src/main/java/app/gymflow/ui/Workout.kt`): добавлена кнопка `title_switch_day` рядом с заголовком в TopAppBar и пункт в меню действий тренировки `menu_switch_day`. В `WorkoutDaySheet` отображаются название, число упражнений, статус, возможность продолжить/начать или просмотреть результаты завершённого дня.
- Переключение дня:
  - Изолировано для выбранного участника (остальные участники групповой сессии не затрагиваются);
  - Пропущенный день не закрывается как выполненный, уходит в `PAUSED` и остаётся доступным;
  - Сохраняются незавершённые подходы и сохранённый курсор упражнения;
  - Исключены дубликаты сессий при повторном выборе дня;
  - При одиночной сессии корректно обновляется `programDayId`.
- Добавлены целевые unit-тесты в `app/src/test/java/app/gymflow/domain/WorkoutDaysTest.kt`.
- Проверена работа приложения и UI на живом эмуляторе (`GymFlow_Test`).
- Создан репозиторий на GitHub: `https://github.com/C0dece/GymFlow`.
- Опубликован релиз `v1.2.0` с готовым установочным APK: `https://github.com/C0dece/GymFlow/releases/tag/v1.2.0`.
- На основе заметок Obsidian пользователя (`d:\Заметки\Obsidian\Codece\Личное\Здоровье\Тренировки\2026-W43 (Неделя 1).md` и `2026-W44 (Неделя 2).md`) сформированы и разложены канонические файлы GymFlow Markdown v3:
  - `Программы/Возвращение в зал.md` — 3 тренировочных дня (Среда, Пятница, Понедельник) с индивидуальными целевыми весами и подходами для обоих атлетов;
  - `Участники/Алексей.md` и `Участники/Партнёр.md` — профили участников;
  - `Упражнения/*.md` — 19 карточек упражнений с типами отягощений и диапазонами повторений;
  - `Тренировки/*.md` — 3 завершённых тренировочных сессии W43 с фактическими весами, повторами и заметками.
- Все файлы сгенерированы с понятными человекочитаемыми именами в стандарте `gymflow_schema: 3`. Существующие заметки пользователя (`_Шаблон недели.md`, `2026-W43`, `2026-W44`) сохранены в полной неприкосновенности.
- Проведено тестирование чтения в Kotlin (`MarkdownTest.kt`): все 25 сгенерированных файлов успешно парсятся и валидируются без ошибок.

## Архитектурные решения
- `startWorkoutDay` и `changePlannedWorkout` безопасно работают с `allWorkouts` и сохраняют привязку к профилю участника.
- Полная сериализация и восстановление состояния (включая запаркованные воркауты `otherWorkouts` и активный день) через стандартный формат Markdown без сторонних зависимостей.
- Интеграция с Obsidian: GymFlow сканирует выбранную через SAF корневую папку и стандартные поддиректории (`Программы`, `Тренировки`, `Упражнения`, `Участники`, `Прогрессия`), игнорируя файлы без frontmatter `gymflow_schema:`. При сохранении GymFlow сохраняет существующие имена файлов на диске.

## Ключевые файлы
- [GymFlow.kt](file:///d:/Dev/Projects/Tools/GymFlow/app/src/main/java/app/gymflow/ui/GymFlow.kt)
- [Workout.kt](file:///d:/Dev/Projects/Tools/GymFlow/app/src/main/java/app/gymflow/ui/Workout.kt)
- [PlannedDaySheet.kt](file:///d:/Dev/Projects/Tools/GymFlow/app/src/main/java/app/gymflow/ui/PlannedDaySheet.kt)
- [WorkoutDaySheet.kt](file:///d:/Dev/Projects/Tools/GymFlow/app/src/main/java/app/gymflow/ui/WorkoutDaySheet.kt)
- [WorkoutDays.kt](file:///d:/Dev/Projects/Tools/GymFlow/app/src/main/java/app/gymflow/domain/WorkoutDays.kt)
- [PlannedWorkoutDays.kt](file:///d:/Dev/Projects/Tools/GymFlow/app/src/main/java/app/gymflow/domain/PlannedWorkoutDays.kt)
- [GymModel.kt](file:///d:/Dev/Projects/Tools/GymFlow/app/src/main/java/app/gymflow/GymModel.kt)
- [WorkoutDaysTest.kt](file:///d:/Dev/Projects/Tools/GymFlow/app/src/test/java/app/gymflow/domain/WorkoutDaysTest.kt)
- [MarkdownTest.kt](file:///d:/Dev/Projects/Tools/GymFlow/app/src/test/java/app/gymflow/domain/MarkdownTest.kt)
- [import-obsidian.mjs](file:///d:/Dev/Projects/Tools/GymFlow/scripts/import-obsidian.mjs)
