# Архитектура Hoshira Desktop

## Обзор

Hoshira Desktop — Windows-приложение на Kotlin/JVM и Compose Multiplatform.
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
Native WebView2 player host ──────► external player pages
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
- `PlayerEngine` — платформенно-независимый контракт источника воспроизведения.

### `ui`

Compose-экраны, компоненты, тема, навигация и состояния загрузки. Долгоживущим
состоянием приложения управляет `AppController`.

### Windows integration

- `WindowsWindowStyle` управляет тёмной рамкой нативного окна;
- `NativeWebView2PlayerPanel` размещает WebView2 через Win32/JNA;
- `EmbeddedPlayerHost` генерирует изолированную HTML-оболочку и собственные
  элементы управления плеером;
- C#-оболочка установщика упаковывает MSI, созданный jpackage/WiX.

## Безопасность

- публичный application token может переопределяться через переменную
  `YANI_APPLICATION_TOKEN`;
- приватный API-токен не поставляется с приложением;
- пароль существует только во время запроса авторизации;
- access token шифруется Windows DPAPI;
- браузерные данные WebView2 хранятся в локальном профиле Hoshira.

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

Windows WebView2-host и установщик при этом останутся внутри desktop-модуля.
