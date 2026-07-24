# Политика безопасности Hoshira

Безопасность учётных данных, локальной сессии и встроенного проигрывателя
рассматривается как приоритет проекта.

## Поддерживаемые версии

Исправления безопасности выпускаются только для последней публично доступной
версии Hoshira. Старые сборки могут не получать обновления.

## Как сообщить об уязвимости

Не создавайте публичную задачу с техническими деталями уязвимости.

1. Свяжитесь с владельцем проекта приватно, используя способ связи, указанный в
   [профиле GitVerse](https://gitverse.ru/northqw).
2. Добавьте к теме сообщения пометку `Hoshira Security`.
3. Укажите затронутую версию, условия воспроизведения, возможное влияние и
   минимальный безопасный пример.
4. Удалите из материалов токены, пароли, персональные данные и приватные ссылки.

Если приватный канал временно недоступен, создайте публичную задачу только с
просьбой связаться с вами. Не раскрывайте в ней саму уязвимость.

Целевой срок подтверждения получения отчёта — семь календарных дней, первичной
оценки — четырнадцать дней. Это ориентир, а не гарантированный SLA. Срок
исправления зависит от серьёзности проблемы и внешних зависимостей.

## Область действия

Политика распространяется на:

- desktop-приложение и установщик Hoshira;
- хранение и обработку локальной сессии;
- взаимодействие с WebView2 и встроенными проигрывателями;
- обработку сетевых данных и локального кэша;
- официальные сборочные и упаковочные сценарии проекта.

Проблемы исключительно на стороне Yani/YummyAnime, WebView2, видеопровайдеров и
других сторонних сервисов следует также направлять их владельцам. Hoshira может
исправить только небезопасное поведение собственного кода.

## Ответственное исследование

Разрешается добросовестная проверка безопасности на собственном устройстве и
собственной учётной записи, если она не нарушает работу сторонних сервисов, не
получает доступ к чужим данным и не причиняет вред другим пользователям.

Это ограниченное разрешение не даёт права распространять исходный код,
модифицированные сборки, эксплойты или материалы бренда Hoshira и не изменяет
условия [LICENSE](LICENSE).

Пожалуйста, согласуйте публичное раскрытие информации с владельцем проекта и
дайте разумное время на подготовку исправления.

---

# Hoshira Security Policy

Security fixes are provided only for the latest publicly available Hoshira
version.

Do not disclose vulnerability details in a public issue. Contact the project
owner privately using a method listed on the
[GitVerse owner profile](https://gitverse.ru/northqw), use the subject
`Hoshira Security`, and include the affected version, reproduction conditions,
impact, and a minimal safe proof of concept. Remove credentials, personal data,
and private URLs from all submitted material.

If no private channel is available, open a public issue requesting contact
without revealing the vulnerability. The target acknowledgement time is seven
calendar days and the target initial assessment time is fourteen days; these
targets are not a guaranteed SLA.

Good-faith testing is permitted on your own device and account when it does not
disrupt third-party services, access other users' data, or cause harm. This
limited permission does not authorize redistribution of source code, modified
builds, exploits, or Hoshira brand assets and does not modify [LICENSE](LICENSE).

Coordinate public disclosure with the project owner and allow reasonable time
for remediation.
