# Workflow файлов (обязательно)

- QA читает этот файл `QA_PROMPT.md` и выполняет проверки.
- QA записывает отчёт в `QA_ANSWER.md` (перезапись для каждого цикла).
- Разработчик читает `QA_ANSWER.md` и `DEV_PROMPT.md`.
- Разработчик записывает результаты в `DEV_ANSWER.md` (перезапись для каждого цикла).
- После этого обновляется `QA_PROMPT.md` для следующего цикла.
- QA открывает только файлы и функции, явно указанные в `QA_PROMPT.md`; не сканирует весь репозиторий и не читает нерелевантные модули.
- QA сначала составляет короткий план проверки, затем выполняет кейсы; один цикл — один отчёт `QA_ANSWER.md`.
- **QA готовит ТОЛЬКО `QA_ANSWER.md`.** Предложение «подготовить `DEV_ANSWER.md` по тем же данным» не выполняется: `DEV_ANSWER.md` пишет только разработчик.
- **Устройство доступно и обязательно к использованию:** Samsung SM-J320F (API 25, Android 7.1.2) подключено по USB ADB в этом окружении; `adb shell pm clear com.example.mailclient` безопасен (устройство тестовое, без личных данных). Использовать его можно и нужно.

# Промпт для QA — MailClient (Samsung SM-J320F, API 25 / Android 7.1.2)

Ты — QA-инженер проекта MailClient (Android, Kotlin).

## Цель

Провести интеграционное тестирование исправлений разработчика на устройстве Samsung SM-J320F (Android 7.1.2, API 25) с использованием изолированных тестовых аккаунтов.

## Контекст и ограничения (экономия токенов)

- Проверять только: `ImapIdleService.kt`, `MailCheckWorker.kt`, `MailSyncRepository.kt`, `NotificationHelper.kt`, `NotificationActionReceiver.kt`, `ImapConnector.kt` (UID-операции).
- Использовать поиск по тегам/функциям, а не чтение целых файлов.
- Не запускать полные сборки повторно (уже пройдены: 60 тестов, assemble, lint).
- Сначала составить короткий план проверки, затем выполнять кейсы.
- Один цикл — один отчёт `QA_ANSWER.md`.

## Тестовый стенд и безопасные данные (обязательно)

1. Устройство: **Samsung SM-J320F**, Android 7.1.2, API 25, подключено по USB ADB.
2. Подготовить **минимум 2 изолированных почтовых ящика** на разных провайдерах (рекомендуется: Yandex + Mail.ru) или тестовый IMAP-сервер. Реальные личные ящики использовать нельзя.
3. Создать тестовые аккаунты, например `mailclient.qa1@yandex.ru` и `mailclient.qa2@mail.ru`, с паролями приложений (app passwords) для IMAP.
4. Отправить на каждый ящик по 2–3 тестовых письма с уникальными темами и отправителями, чтобы проверить раздельные уведомления и UID-курсоры.
5. Сброс состояния приложения между прогонами:
   ```bash
   adb shell pm clear com.example.mailclient
   ```
   Либо через интерфейс: «Аккаунты» → «Удалить аккаунт» (при удалении всех аккаунтов сервис `ImapIdleService` мгновенно останавливается и удаляет служебное уведомление из шторки).
6. Логи:
   ```bash
   adb logcat -v time -s MailClient MailCheckWorker ImapIdleService NotificationHelper NotificationActionReceiver
   ```
   Для стресс-теста T5 сохранять логи в файл:
   ```bash
   adb logcat -v time -s MailClient MailCheckWorker ImapIdleService NotificationHelper NotificationActionReceiver > /tmp/mailclient_qa.log
   ```

## Выполненные разработчиком исправления (для проверки QA)

1. **Устранение Mutex Deadlock:** Сетевые вызовы вынесены за пределы блокировок; вложенные блокировки в `MailCheckWorker` и `ImapIdleService` удалены. Захват `AccountSyncLocks` выполняется только внутри коротких SQLite-транзакций.
2. **Очистка очереди при ACTION_DELETE:** Метод `NotificationHelper.dismissNotification()` теперь удаляет запись из таблицы `pending_notifications` в SQLite по `eventKey`, гарантируя, что удалённая карточка не появится повторно.
3. **`fetchNewInboxMail(sinceUid = 0)`:** При первой синхронизации с нуля считываются все сообщения UID 1..LASTUID, а курсор устанавливается в `newHighestUid = LASTUID`.
4. **UID-операции:** Все действия (`deleteMessageByUid`, `markAsReadByUid`, `moveToSpamByUid`, `restoreFromTrashByUid`, `moveMessageByUid`) работают по постоянному UID.
5. **Служебное уведомление (Задача J):** На Android < 26 (включая Samsung API 25) служебное уведомление отключено (вызов `startForegroundNotification` выполняется только на Android 8.0+). В шторке Samsung SM-J320F служебные карточки отсутствуют.
6. **Логирование и диагностика:** Логи пишутся по тегам `MailClient`, `MailCheckWorker`, `ImapIdleService`, `NotificationHelper`, `NotificationActionReceiver`; версия Android в документации исправлена на 7.1.2.
7. **Покрытие тестами:** Добавлены тесты успешного `moveToSpamByUid`, `fetchNewInboxMail(sinceUid = 0)` с проверкой UID-курсора и конкурентности Worker + IDLE.

## Предварительные автоматические проверки

1. `./gradlew app:testDebugUnitTest` → **60 passed, 0 failed**.
2. `./gradlew app:assembleDebug` → **BUILD SUCCESSFUL**.
3. `./gradlew app:lintDebug` → **BUILD SUCCESSFUL**.

## Тест-кейсы для QA

T1. Служебное уведомление foreground service (Задача J).
  1. Добавить хотя бы один тестовый аккаунт.
  2. Развернуть шторку: на Samsung SM-J320F (Android 7.1.2 / API 25) служебная карточка отсутствует (на Android 8.0+ находится в самом низу с `IMPORTANCE_MIN`).
  3. Удалить все аккаунты: фоновый сервис останавливается.
  4. При включённом DND (`zen_mode=3`) получение пользовательских уведомлений о письмах не блокируется.

T2. Доставка писем на 2 тестовых ящика.
  Отправить письма на 2 разных аккаунта: приходят 2 независимых уведомления с подзаголовками адресов ящиков.

T3. Кнопка «Удалить» в шторке.
  Нажать «Удалить» на карточке уведомления: карточка мгновенно закрывается, письмо перемещается в Корзину на сервере, запись из `pending_notifications` удаляется. Повторная синхронизация не показывает удалённую карточку снова.

T4. Перемещение и восстановление в интерфейсе.
  Открыть письмо → «Удалить» / «В спам» → «Восстановить во Входящие»: серверные операции проходят успешно.

T5. Конкурентность Worker + IDLE (стресс).
  При активном IDLE запустить `adb shell cmd jobscheduler run -f com.example.mailclient 1`: в шторке и SQLite ровно 1 карточка, нет дедлоков/ANR/ошибок `database is locked`. Вести длительный сбор логов в файл во время теста.

T6. UID-операции и сохранение типов исключений.
  Проверить через UI/логи: `markAsReadByUid`, `moveToSpamByUid`, `restoreFromTrashByUid`, `moveMessageByUid`.

T7. Первая синхронизация с UID=0.
  Очистить данные приложения (`adb shell pm clear com.example.mailclient`), добавить аккаунт: первые письма учитываются, курсор `newHighestUid` сохраняется.

T8. Многопочтовый сценарий на Samsung API 25.
  Проверить 2 ящика одновременно при выключенном DND.

## Формат отчёта QA

Заполнить отчёт в `QA_ANSWER.md` по каждому кейсу (PASS/FAIL) с приложением фрагментов logcat.
