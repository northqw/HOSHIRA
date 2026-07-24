<div align="center">
  <img src="desktopApp/src/main/resources/icons/hoshira.png" width="96" alt="Hoshira">
  <h1>Hoshira</h1>
  <p><strong>A modern desktop client for your anime library.</strong></p>
  <p>Browse, search, manage personal lists, and watch episodes in one native Windows experience.</p>

  <p>
    <a href="README.md">Русский</a>
    ·
    <a href="README.en.md">English</a>
  </p>

  <p>
    <img src="https://img.shields.io/badge/version-0.2.4-ff4e00?style=flat-square" alt="Version 0.2.4">
    <img src="https://img.shields.io/badge/platform-Windows%2010%2F11-111318?style=flat-square&logo=windows" alt="Windows 10/11">
    <img src="https://img.shields.io/badge/Kotlin-2.3.20-7f52ff?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin 2.3.20">
    <img src="https://img.shields.io/badge/Compose%20Desktop-1.11.1-4285f4?style=flat-square" alt="Compose Desktop 1.11.1">
    <img src="https://img.shields.io/badge/license-proprietary-111318?style=flat-square" alt="Proprietary license">
  </p>
</div>

![Hoshira home screen](docs/assets/hoshira-home.png)

## Product overview

Hoshira is an independent Windows application focused on fast access to an
anime catalog, personal library, and episode playback. It is designed as a
complete desktop product with a cinematic home screen, smooth transitions,
native Windows integration, and a branded installer.

The current release is a **Windows x64 desktop beta**. The project is structured
so reusable logic can later move into Kotlin Multiplatform modules for Android
and Android TV.

## Features

| Area | Capabilities |
| --- | --- |
| Home | Rotating featured releases, collections, and horizontal carousels |
| Catalog | Infinite loading, filters, and sorting |
| Search | Debounced catalog search with clear loading and error states |
| Anime details | Artwork, metadata, genres, dubbing studio selector, and episodes |
| Player | WebView2, source and quality selection, custom playback controls |
| Account | YummyAnime sign-in, favorites, and personal lists |
| Desktop UX | Image cache, dark window chrome, loading states, and a branded installer |

## Requirements

### Installed application

- Windows 10 or Windows 11, x64;
- Microsoft Edge WebView2 Runtime;
- an internet connection.

The JVM is bundled with the desktop distribution.

## Repository layout

```text
desktopApp/
├─ installer/             # branded installer shell
├─ src/main/kotlin/       # application, API, state, and UI
├─ src/main/resources/    # icons and native WebView2 loader
├─ src/test/              # unit tests
└─ tools/                 # Windows packaging tools
docs/
├─ ARCHITECTURE.md
└─ assets/
third_party/              # third-party license texts
```

See the [architecture document](docs/ARCHITECTURE.md) for implementation
details.

## Technology

- Kotlin/JVM 21;
- Compose Multiplatform Desktop;
- Kotlin Coroutines and Serialization;
- Coil 3 with disk-backed image caching;
- JNA and Microsoft WebView2 for native Windows integration;
- Gradle Wrapper;
- WiX/jpackage with a custom C# installer shell.

## Security and privacy

- credentials are never sent to a Hoshira-owned backend;
- account requests go directly to the API provider;
- passwords are never stored;
- local sessions are protected by Windows DPAPI;
- secrets and signing material are excluded from Git.

If you discover a security issue, do not publish sensitive details in a public
issue. Contact the repository owner through their GitVerse profile.

## Legal notice

Hoshira is an unofficial, independent client. It is not affiliated with the
owners of Yani/YummyAnime, Microsoft WebView2, or external video providers. The
application does not host or distribute media. Catalog and playback
availability depend on third-party services, the user's region, and their terms
of service.

All third-party trademarks and materials remain the property of their
respective owners.

## Licensing

Copyright © 2026 northqw. All rights reserved.

The source code is published for reference and review only. Use, copying,
modification, and distribution are prohibited without prior written permission
from the copyright holder. See [LICENSE](LICENSE) for the complete terms.

Third-party components and materials remain subject to their respective
licenses. Applicable notices are available under `third_party/`.
