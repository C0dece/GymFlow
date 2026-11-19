# GymFlow

- Windows: `setup-dev.ps1` проверяет SDK и AVD, `run-app.ps1` собирает и устанавливает Debug APK, `test-all.ps1` запускает JVM, lint, проверку зависимостей и инструментальные тесты. Для изолированного запуска используйте `-Avd GymFlow_Compact -Headless`.
- Unit-тесты и эталоны Markdown находятся в `app/src/test`. Compose UI и интеграционные тесты используют отдельные папки `Documents/GymFlow_QA_*` на эмуляторе.
- Канонический формат Markdown описан в `docs/markdown-v6.md`. Запись в SAF проходит через журнал Room и проверку версии. Доступ к пользовательским папкам осуществляется только после выбора через SAF.
- `qa-output`, `local.properties` и результаты сборки являются локальными и исключены из Git.
