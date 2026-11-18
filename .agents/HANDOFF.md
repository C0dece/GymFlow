# Живой реестр состояния проекта GymFlow

## Цель
Реализовать быстрое переключение тренировочного дня (в активной тренировке и на карточке главного экрана), поддержку смены дня со сдвигом/без сдвига, сохранение состояния участников, предотвращение дубликатов, возможность исправления результатов завершённых тренировок с пересчётом прогрессии.

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
- Успешно собрана Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Архитектурные решения
- `startWorkoutDay` и `changePlannedWorkout` безопасно работают с `allWorkouts` и сохраняют привязку к профилю участника.
- Полная сериализация и восстановление состояния (включая запаркованные воркауты `otherWorkouts` и активный день) через стандартный формат Markdown без сторонних зависимостей.

## Ключевые файлы
- [GymFlow.kt](file:///d:/Dev/Projects/Tools/GymFlow/app/src/main/java/app/gymflow/ui/GymFlow.kt)
- [Workout.kt](file:///d:/Dev/Projects/Tools/GymFlow/app/src/main/java/app/gymflow/ui/Workout.kt)
- [PlannedDaySheet.kt](file:///d:/Dev/Projects/Tools/GymFlow/app/src/main/java/app/gymflow/ui/PlannedDaySheet.kt)
- [WorkoutDaySheet.kt](file:///d:/Dev/Projects/Tools/GymFlow/app/src/main/java/app/gymflow/ui/WorkoutDaySheet.kt)
- [WorkoutDays.kt](file:///d:/Dev/Projects/Tools/GymFlow/app/src/main/java/app/gymflow/domain/WorkoutDays.kt)
- [PlannedWorkoutDays.kt](file:///d:/Dev/Projects/Tools/GymFlow/app/src/main/java/app/gymflow/domain/PlannedWorkoutDays.kt)
- [GymModel.kt](file:///d:/Dev/Projects/Tools/GymFlow/app/src/main/java/app/gymflow/GymModel.kt)
- [WorkoutDaysTest.kt](file:///d:/Dev/Projects/Tools/GymFlow/app/src/test/java/app/gymflow/domain/WorkoutDaysTest.kt)
