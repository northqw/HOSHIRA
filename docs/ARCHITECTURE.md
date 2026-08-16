# Архитектура Hoshira Desktop

## Обзор

Hoshira Desktop — кроссплатформенное приложение на Kotlin/JVM и Compose
Multiplatform с основной Windows-версией и экспериментальной Linux-сборкой.
Проект разделяет сетевой слой, доменное состояние, UI и платформенную
интеграцию. Это сохраняет возможность позднее вынести переносимую логику в
общие Kotlin Multiplatform-модули.

```text
Compose UI
   │
   ▼
AppController ───────────────► AccountRepository
   │                                  │
   ▼                                  ▼
ReleaseRepository ────────────────► YaniApi
   │
   ▼
Platform player host ─────────────► HLS stream / external player pages
```

## Основные слои

### `model`

DTO и модели релизов, эпизодов, озвучек и UI-состояния. Слой не зависит от
Compose.

### `data`

- `YaniApi` — HTTP-запросы, сериализация и API-контракты;
- `ReleaseRepository` — главная лента, каталог, поиск и страницы релизов;
- `AccountRepository` — авторизация, пользовательские списки и DPAPI-хранилище
  сессии;
- `HlsStreamResolver` — преобразование страниц VideoHub/Kodik в прямой HLS-поток;
- `PlayerEngine` — платформенно-независимый контракт источника воспроизведения.

### `ui`

Compose-экраны, компоненты, тема, навигация и состояния загрузки. Долгоживущим
состоянием приложения управляет `AppController`.

### Windows integration

- `WindowsWindowStyle` управляет тёмной рамкой нативного окна;
- `NativeDesktopPlayerPanel` воспроизводит HLS через JavaFX Media внутри Swing;
- JavaFX-слой содержит собственные элементы управления, восстановление позиции
  и автоматический переход к следующей серии;
- C#-оболочка установщика упаковывает MSI, созданный jpackage/WiX.

### Linux integration

- `LinuxWindowStyle` задаёт фон и иконку окна без Win32-вызовов;
- `LinuxWebKitPlayerPanel` размещает системный WebKitGTK внутри AWT-контейнера
  через Eclipse SWT и переиспользует его между сериями открытой сессии плеера;
- конфигурация и кэш используют каталоги XDG;
- сессия шифруется AES-GCM, а файл ключа ограничен правами текущего
  пользователя;
- GitVerse CI/CD собирает пакеты `.deb` и `.rpm` на Ubuntu runner.

### Android integration

- `PlayerScreen` получает прямой поток через общий `HlsStreamResolver`;
- Media3/ExoPlayer воспроизводит HLS без страницы провайдера и WebView;
- увеличенный буфер, fallback декодера и нативная обработка audio focus уменьшают повторную буферизацию и краткие провалы звука.

## Безопасность

- публичный application token может переопределяться через переменную
  `YANI_APPLICATION_TOKEN`;
- приватный API-токен не поставляется с приложением;
- пароль существует только во время запроса авторизации;
- access token шифруется Windows DPAPI или платформенным Linux-хранилищем;
- Windows-плеер не создаёт браузерный профиль и не исполняет страницы провайдера.

## Направление развития

После стабилизации desktop-версии переносимые части могут быть выделены в:

```text
shared/
  api
  domain
  account
  player-contract
apps/
  desktop
  android-mobile
  android-tv
```

Windows HLS-плеер и установщик при этом останутся внутри desktop-модуля.
