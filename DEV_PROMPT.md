# Промпт для разработчика — MailClient (на основе QA_ANSWER.md)

## Workflow файлов (обязательно)

- Разработчик читает `QA_ANSWER.md` и этот файл `DEV_PROMPT.md`.
- Разработчик выполняет задачи и записывает результаты в `DEV_ANSWER.md` (перезапись для каждого цикла).
- После этого обновляется `QA_PROMPT.md` для следующего цикла.
- **`DEV_ANSWER.md` пишет ТОЛЬКО разработчик.** QA может предложить «подготовить `DEV_ANSWER.md` по тем же данным» — это предложение следует игнорировать: отчёт разработчика формируется только по результатам фактического выполнения задач разработчиком. QA не должен создавать/перезаписывать `DEV_ANSWER.md`.
- **Тестовое устройство доступно:** Samsung SM-J320F, Android 7.1.2, API 25, подключено по USB ADB. Устройство подготовлено специально для этого проекта и **не содержит личных данных и файлов**. Использовать его можно и нужно: `adb shell pm clear com.example.mailclient` безопасен и разрешён.

## Ревью отчёта QA (QA_ANSWER.md)

1. **Блокировка T1–T8 некорректна.** QA пометил все кейсы как `FAIL (blocked)` из‑за «отсутствия устройства/ADB/аккаунтов». На самом деле устройство Samsung SM-J320F доступно и подготовлено; ADB работает; тестовый `pm clear` безопасен. Реальные кейсы **не выполнялись** — их нужно выполнить на устройстве (см. задачи ниже).
2. **Автотесты:** `./gradlew app:testDebugUnitTest` — 60 passed (QA подтвердил). `assembleDebug`/`lintDebug` — BUILD SUCCESSFUL. Это базовый порог сохраняется.
3. **Несоответствие `DEV_ANSWER.md` и кода (важно):**
   - `DEV_ANSWER.md` утверждал, что `withLock` вокруг сетевых вызовов в `ImapIdleService` убран и `AccountSyncLocks` захватывается только в коротких SQLite‑транзакциях. **Фактически** `ImapIdleService` всё ещё оборачивает сетевые вызовы (`fetchUnreadInboxMessages`, `fetchFolderMessagesFullScan`, `fetchNewInboxMail`, `listenIdleForAccount`) в `AccountSyncLocks.getLock(account.email).withLock` (два места: первичная синхронизация и коллбэк `onNewMailArrived`). Задача E из предыдущего цикла **не выполнена**.
   - `MailSyncRepository.processSyncAtomic()` и `dispatchPendingNotifications()` **не** захватывают `AccountSyncLocks` (вызовов нет). `MailCheckWorker` тоже не использует `AccountSyncLocks`.
   - Следствие: риск deadlock/`database is locked` при T5 не устранён на уровне кода, несмотря на зелёные юнит‑тесты.
4. **Выполнено корректно:** `NotificationHelper.dismissNotification()` уже удаляет запись из `pending_notifications` по `eventKey` (код есть) — соответствует `DEV_ANSWER.md`.
5. **`fetchNewInboxMail(sinceUid = 0)`:** код фильтрует `msg.uid > sinceUid`, поэтому при `sinceUid = 0` проходят все UID > 0; курсор `= currentHighestUid`. Требуется подтверждение на реальном IMAP (T7).
6. **UID‑операции:** `NotificationActionReceiver` при `ACTION_DELETE` вызывает `moveToTrashByUid` (не `deleteMessageByUid`). Соответствует ожиданию QA (письмо в Корзину). Требуется проверка на реальном сервере (T6).
7. **Служебное уведомление:** кнопка «Остановить мониторинг» (`ACTION_STOP`) присутствует; при удалении всех аккаунтов сервис делает `stopSelf` и снимает foreground‑уведомление. Поведение при DND (`zen_mode=3`) не проверено.
8. **Версия Android:** в документации указана 7.1.2 (API 25) — соответствует устройству.

## Задачи разработчику

### A. Тестовый стенд и безопасные данные (обязательно)
- Использовать устройство **Samsung SM-J320F** (API 25). Подготовить **минимум 2 изолированных ящика** на разных провайдерах (Yandex + Mail.ru) или тестовый IMAP‑сервер. Реальные личные ящики использовать нельзя.
- Создать тестовые аккаунты (например, `mailclient.qa1@yandex.ru`, `mailclient.qa2@mail.ru`) с паролями приложений (app passwords) для IMAP.
- Отправить на каждый ящик по 2–3 тестовых письма с уникальными темами/отправителями.
- Сброс состояния между прогонами: `adb shell pm clear com.example.mailclient` (безопасно, устройство тестовое) или через UI «Аккаунты → Удалить аккаунт».
- Логи: `adb logcat -v time -s MailClient MailCheckWorker ImapIdleService NotificationHelper NotificationActionReceiver`. Для T5 — в файл: `adb logcat -v time -s MailClient MailCheckWorker ImapIdleService NotificationHelper NotificationActionReceiver > /tmp/mailclient_qa.log`.

### B. Ликвидация Mutex deadlock (критично, блокирует T5)
- **Убрать** `AccountSyncLocks.getLock(...).withLock` вокруг сетевых вызовов в `ImapIdleService` (оба места: первичная синхронизация и `onNewMailArrived`).
- **Перенести** захват `AccountSyncLocks` в `MailSyncRepository.processSyncAtomic()` и `dispatchPendingNotifications()` — только внутри коротких SQLite‑транзакций (или обеспечить эквивалентную защиту без deadlock).
- Убедиться, что `MailCheckWorker` не держит лок во время сетевых запросов.
- Проверить отсутствие `database is locked` / ANR / deadlock при T5.

### C. Проверка UID‑операций на реальном сервере (T6)
- Проверить через UI/логи: `markAsReadByUid`, `moveToSpamByUid`, `restoreFromTrashByUid`, `moveMessageByUid`, `moveToTrashByUid` (ACTION_DELETE → Корзина).
- Сохранение типов исключений (`IOException`, `AuthenticationFailedException`) без разрыва цепочки.

### D. Служебное уведомление и DND (T1)
- Удаление всех аккаунтов → сервис останавливается → уведомление исчезает ≤ 5 с.
- Кнопка «Остановить мониторинг» останавливает сервис и убирает уведомление.
- При включённом DND (`zen_mode=3`) служебное уведомление не блокирует пользовательские уведомления о письмах.

### E. Первая синхронизация с UID=0 (T7)
- `adb shell pm clear com.example.mailclient` → добавить аккаунт: первые письма учитываются, курсор `newHighestUid` сохраняется (= LASTUID).

### F. Многопочтовый сценарий (T2, T3, T4, T8)
- 2 ящика одновременно → 2 независимых уведомления с подзаголовками адресов.
- «Удалить» в шторке → карточка мгновенно закрывается, письмо в Корзину на сервере, `pending_notifications` очищается, повторная синхронизация не показывает карточку снова.
- Открыть письмо → «Удалить» / «В спам» → «Восстановить во Входящие»: серверные операции проходят успешно.
- DND выключен.

### G. Конкурентность Worker + IDLE (T5, стресс)
- При активном IDLE запустить `adb shell cmd jobscheduler run -f com.example.mailclient 1`.
- В шторке и SQLite ровно 1 карточка, нет дедлоков/ANR/`database is locked`.
- Вести длительный сбор логов в файл.

### H. Логирование и диагностика
- Логи по тегам `MailClient`, `MailCheckWorker`, `ImapIdleService`, `NotificationHelper`, `NotificationActionReceiver` при ключевых операциях.
- Версия Android в документации: 7.1.2.

### I. Покрытие тестами
- Сохранить зелёные `testDebugUnitTest` (60+). При необходимости добавить инструментированный тест Worker + IDLE на устройстве (проверка отсутствия дублей в SQLite).

## J. Служебное уведомление foreground service (не показывать пользователю)

- На реальном устройстве в шторке постоянно висит служебное уведомление «Мониторинг почтовых ящиков. Отслеживание новых писем по IMAP IDLE». Это служебное уведомление и **его НЕ нужно показывать пользователю**.
- Убрать служебное уведомление из шторки: либо сделать его полностью невидимым/не отображаемым, либо отказаться от foreground service в пользу фонового механизма (WorkManager/обычный сервис), сохранив мониторинг IDLE.
- Если foreground service остаётся технически необходимым — минимизировать его видимость (IMPORTANCE_MIN, VISIBILITY_SECRET, без badge/звука/вибрации) и не показывать в шторке.

## K. Критические недочёты по мультизадачности (много писем/ящиков, удаление из шторки)

- **Удаление из шторки при «Письмо не найдено»:** `NotificationActionReceiver` при `ACTION_DELETE` вызывает `moveToTrashByUid`; если сервер возвращает «Письмо не найдено» (уже удалено/перемещено), `dismissNotification` не вызывается и карточка остаётся в шторке. Исправить: при ошибке «не найдено» всё равно вызывать `NotificationHelper.dismissNotification(...)`, чтобы убрать карточку.
- **Немедленная остановка сервиса при удалении всех аккаунтов:** `ImapIdleService` проверяет пустой список аккаунтов только в `onStartCommand` и раз в 30 с в цикле; при удалении всех аккаунтов через UI служебное уведомление может висеть до 30 с. Исправить: в `updateMonitoringJobs()` (и/или цикле) при `accounts.isEmpty()` вызывать `stopSelf()` + снятие foreground-уведомления немедленно (≤5 с, как в T1.4).
- **DND (`zen_mode=3`):** `NotificationHelper.isNotificationAllowed()` не проверяет DND; служебное и пользовательские уведомления могут блокироваться. Проверить/исправить поведение при включённом DND (T1.5, T8).
- **Последовательная обработка аккаунтов в `MailCheckWorker`:** один зависший аккаунт блокирует остальные. Рассмотреть таймауты на сетевые вызовы и/или параллелизацию по аккаунтам.
- **Повторное появление карточки после удаления:** `dismissNotification` вычисляет `eventKey` через текущий `savedValidity`; если UIDVALIDITY изменился между показом и удалением, запись в `pending_notifications` не удалится. Сделать удаление robust (по `account+uid` или актуальной validity).

## Критерии завершения

- `./gradlew app:testDebugUnitTest app:assembleDebug app:lintDebug` — BUILD SUCCESSFUL.
- Задачи B выполнены в коде (withLock убран, локи перенесены).
- T1–T8 выполнены на Samsung SM-J320F; результаты зафиксированы в `DEV_ANSWER.md`.
- Служебное уведомление foreground service не показывается пользователю в шторке (задача J).
- Недочёты по мультизадачности из раздела K устранены (удаление из шторки при «не найдено», немедленная остановка сервиса, DND).
- `DEV_ANSWER.md` заполняет только разработчик (см. правило workflow).
