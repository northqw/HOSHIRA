package dev.aniliberty.desktop.ui

import java.nio.charset.StandardCharsets
import java.util.Base64

internal data class AndroidPlayerHostSource(
    val episodeId: String,
    val label: String,
    val selected: Boolean,
    val enabled: Boolean = true,
)

internal data class AndroidPlayerHostConfig(
    val playerUrl: String,
    val title: String,
    val subtitle: String,
    val position: String,
    val sources: List<AndroidPlayerHostSource>,
    val resumeSeconds: Double,
    val startupVolume: Float,
    val preferredQuality: String?,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val controlsHideDelayMs: Int,
    val showLoading: Boolean,
)

internal const val HOSHIRA_PLAYER_FONT_PATH =
    "/__hoshira_android/font/montserrat-semibold.ttf"

/**
 * This document is loaded with WebView.loadDataWithBaseURL using the provider
 * URL as its HTTPS base. That gives the host a real provider origin without
 * racing an in-flight provider navigation via document.open/document.write.
 */
internal fun androidPlayerHostDocument(config: AndroidPlayerHostConfig): String {
    val encodedUrl = config.playerUrl.toBase64()
    val encodedQuality = config.preferredQuality.orEmpty().toBase64()
    val sourceOptions = config.sources.joinToString("\n") { source ->
        val unavailable = if (source.enabled) "" else " disabled unavailable"
        """
            <button
              class="source-option${if (source.selected) " selected" else ""}$unavailable"
              data-source-id="${source.episodeId.escapeHtml()}"
              type="button"
              ${if (source.enabled) "" else "disabled aria-disabled=\"true\""}
            >
              <span>
                ${source.label.escapeHtml()}
                ${if (source.enabled) "" else "<small>Поддержка появится позже</small>"}
              </span>
              ${if (source.selected) "<span class=\"check\">✓</span>" else ""}
            </button>
        """.trimIndent()
    }
    val selectedSource = config.sources.firstOrNull { it.selected && it.enabled }
        ?: config.sources.firstOrNull(AndroidPlayerHostSource::enabled)
    val sourcePicker = if (config.sources.size > 1) {
        """
            <div class="source-picker">
              <button class="control source-toggle" id="source-toggle" type="button">
                <span class="source-caption">Источник</span>
                <span id="source-label">${selectedSource?.label.orEmpty().escapeHtml()}</span>
                <span class="chevron">⌄</span>
              </button>
              <div class="source-menu" id="source-menu">
                <div class="source-menu-title">Выберите плеер</div>
                $sourceOptions
              </div>
            </div>
        """.trimIndent()
    } else {
        """
            <div class="source-single">
              <span class="source-caption">Источник</span>
              <span>${selectedSource?.label.orEmpty().escapeHtml()}</span>
            </div>
        """.trimIndent()
    }
    val previousButton = if (config.hasPrevious) {
        """<button class="control episode-button" id="previous" type="button">‹ Предыдущая</button>"""
    } else {
        "<span></span>"
    }
    val nextButton = if (config.hasNext) {
        """<button class="control episode-button accent" id="next" type="button">Следующая ›</button>"""
    } else {
        "<span></span>"
    }

    return """
        <!doctype html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
          <meta name="referrer" content="origin">
          <style>
            @font-face {
              font-family: HoshiraMontserrat;
              src: url('$HOSHIRA_PLAYER_FONT_PATH') format('truetype');
              font-style: normal;
              font-weight: 100 900;
              font-display: block;
            }
            :root {
              color-scheme: dark;
              font-family: HoshiraMontserrat, Arial, sans-serif;
              --orange: #ff570f;
              --panel: rgba(10, 10, 10, .92);
              --muted: #a9a9ad;
            }
            * { box-sizing: border-box; }
            html, body {
              width: 100%;
              height: 100%;
              min-width: 0;
              margin: 0;
              overflow: hidden;
              background: #000;
              color: #fff;
            }
            #hoshira-provider {
              position: fixed;
              inset: 0;
              display: block;
              width: 100%;
              height: 100%;
              border: 0;
              background: #000;
            }
            .loading {
              position: fixed;
              inset: 0;
              z-index: 5;
              display: grid;
              place-items: center;
              pointer-events: none;
              background: #000;
              transition: opacity .22s ease;
            }
            .loading.hidden { opacity: 0; visibility: hidden; }
            .spinner {
              width: 42px;
              height: 42px;
              margin: 0 auto 16px;
              border: 4px solid rgba(255,255,255,.16);
              border-top-color: var(--orange);
              border-radius: 50%;
              animation: spin .8s linear infinite;
            }
            .loading-label { color: var(--muted); font-size: 16px; font-weight: 700; }
            @keyframes spin { to { transform: rotate(360deg); } }
            .chrome {
              position: fixed;
              inset: 0;
              z-index: 7;
              pointer-events: none;
              opacity: 1;
              transition: opacity .25s ease;
            }
            .chrome.hidden { opacity: 0; }
            .shade-top, .shade-bottom {
              position: absolute;
              left: 0;
              right: 0;
              pointer-events: none;
            }
            .shade-top {
              top: 0;
              height: 34%;
              background: linear-gradient(to bottom, rgba(0,0,0,.9), rgba(0,0,0,0));
            }
            .shade-bottom {
              bottom: 0;
              height: 42%;
              background: linear-gradient(to top, rgba(0,0,0,.94), rgba(0,0,0,0));
            }
            .topbar {
              position: absolute;
              z-index: 3;
              top: 0;
              left: 0;
              right: 0;
              display: flex;
              align-items: center;
              justify-content: space-between;
              gap: 16px;
              padding: 18px 22px;
            }
            .top-left {
              min-width: 0;
              display: flex;
              align-items: center;
              gap: 16px;
            }
            .meta { min-width: 0; }
            .title {
              max-width: 54vw;
              overflow: hidden;
              text-overflow: ellipsis;
              white-space: nowrap;
              font-size: 18px;
              font-weight: 900;
            }
            .subtitle {
              margin-top: 3px;
              color: var(--muted);
              font-size: 13px;
              font-weight: 700;
            }
            button {
              font: inherit;
              color: inherit;
            }
            .control {
              pointer-events: auto;
              border: 1px solid rgba(255,255,255,.15);
              border-radius: 999px;
              background: rgba(20,20,20,.88);
              cursor: pointer;
            }
            .back {
              min-width: 46px;
              height: 46px;
              padding: 0 16px;
              font-size: 17px;
              font-weight: 900;
            }
            .source-picker { position: relative; pointer-events: auto; }
            .source-toggle, .source-single {
              display: flex;
              align-items: center;
              gap: 8px;
              min-height: 44px;
              padding: 0 16px;
              font-size: 13px;
              font-weight: 800;
            }
            .source-single {
              border: 1px solid rgba(255,255,255,.12);
              border-radius: 999px;
              background: rgba(20,20,20,.82);
            }
            .source-caption { color: var(--muted); font-size: 11px; text-transform: uppercase; }
            .chevron { color: var(--orange); font-size: 18px; }
            .source-menu {
              position: absolute;
              top: calc(100% + 8px);
              right: 0;
              display: none;
              width: min(310px, calc(100vw - 24px));
              padding: 8px;
              border: 1px solid rgba(255,255,255,.12);
              border-radius: 14px;
              background: var(--panel);
              box-shadow: 0 18px 50px rgba(0,0,0,.5);
            }
            .source-menu.open { display: block; }
            .source-menu-title {
              padding: 7px 10px 9px;
              color: var(--muted);
              font-size: 12px;
              font-weight: 800;
            }
            .source-option {
              display: flex;
              width: 100%;
              align-items: center;
              justify-content: space-between;
              padding: 11px 12px;
              border: 0;
              border-radius: 10px;
              background: transparent;
              text-align: left;
              font-weight: 800;
            }
            .source-option > span:first-child { min-width: 0; }
            .source-option.unavailable {
              cursor: default;
              opacity: .46;
            }
            .source-option small {
              display: block;
              margin-top: 4px;
              color: rgba(255,255,255,.55);
              font-size: .68em;
              font-weight: 600;
              white-space: normal;
              line-height: 1.35;
            }
            .source-option.selected { color: var(--orange); background: rgba(255,87,15,.1); }
            .center-controls {
              position: absolute;
              z-index: 2;
              inset: 0;
              display: none;
              align-items: center;
              justify-content: center;
              gap: 20px;
            }
            .center-controls.available { display: flex; }
            .round {
              width: 48px;
              height: 48px;
              padding: 0;
              font-size: 14px;
              font-weight: 900;
            }
            .play {
              width: 70px;
              height: 70px;
              border: 0;
              background: var(--orange);
              font-size: 28px;
              box-shadow: 0 10px 35px rgba(255,87,15,.35);
            }
            .bottom {
              position: absolute;
              z-index: 3;
              left: 0;
              right: 0;
              bottom: 0;
              padding: 18px 24px 20px;
            }
            .timeline {
              display: grid;
              grid-template-columns: 54px 1fr 54px;
              align-items: center;
              gap: 10px;
              color: #ddd;
              font-size: 12px;
              font-weight: 700;
            }
            input[type=range] {
              width: 100%;
              accent-color: var(--orange);
              pointer-events: auto;
            }
            .bottom-row {
              display: grid;
              grid-template-columns: 1fr auto 1fr;
              align-items: center;
              gap: 16px;
              margin-top: 12px;
            }
            .playback-actions, .episode-actions {
              display: flex;
              align-items: center;
              gap: 10px;
            }
            .episode-actions { justify-content: flex-end; }
            .small {
              min-width: 42px;
              height: 38px;
              padding: 0 13px;
              border-radius: 999px;
              font-weight: 900;
            }
            .volume { width: 95px !important; }
            .volume-icon {
              width: 26px;
              height: 26px;
              flex: 0 0 auto;
              fill: currentColor;
              stroke: currentColor;
              color: #fff;
            }
            .position { color: var(--muted); font-size: 12px; font-weight: 800; }
            .episode-button { min-height: 38px; padding: 0 14px; border-radius: 999px; font-weight: 900; }
            .accent { border-color: var(--orange); background: var(--orange); }
            .gesture-layer {
              position: fixed;
              inset: 0;
              z-index: 6;
              pointer-events: none;
              touch-action: manipulation;
              -webkit-tap-highlight-color: transparent;
            }
            .gesture-layer.active { pointer-events: auto; }
            .tap-feedback {
              position: fixed;
              z-index: 12;
              min-width: 92px;
              padding: 14px 18px;
              border: 1px solid rgba(255,255,255,.18);
              border-radius: 999px;
              background: rgba(8,8,8,.84);
              color: #fff;
              font-size: 17px;
              font-weight: 900;
              text-align: center;
              opacity: 0;
              pointer-events: none;
              transform: translate(-50%, -50%) scale(.82);
              transition: opacity .16s ease, transform .16s ease;
            }
            .tap-feedback.visible {
              opacity: 1;
              transform: translate(-50%, -50%) scale(1);
            }
            .loading { z-index: 9; }
            .chrome { z-index: 10; }
            .chrome.hidden {
              visibility: hidden;
              transition: opacity .2s ease, visibility 0s linear .2s;
            }
            button, input[type=range] {
              touch-action: manipulation;
              -webkit-tap-highlight-color: transparent;
            }
            .control {
              min-height: 58px;
              border-width: 2px;
              transition: transform .1s ease, background-color .1s ease;
            }
            .control:active, .source-option:active {
              transform: scale(.95);
              background-color: rgba(55,55,55,.96);
            }
            .back {
              min-width: 64px;
              height: 64px;
              padding: 0 22px;
              font-size: 20px;
            }
            .source-toggle, .source-single {
              min-height: 60px;
              padding: 0 20px;
              gap: 10px;
              font-size: 16px;
            }
            .source-caption { font-size: 13px; }
            .chevron { font-size: 22px; }
            .source-menu {
              width: 310px;
              padding: 10px;
              border-radius: 18px;
            }
            .source-menu-title { padding: 10px 13px 12px; font-size: 14px; }
            .source-option {
              min-height: 58px;
              padding: 14px 16px;
              border-radius: 14px;
              font-size: 16px;
            }
            .round {
              width: 68px;
              height: 68px;
              font-size: 18px;
            }
            .play {
              width: 96px;
              height: 96px;
              font-size: 38px;
            }
            .center-controls { gap: 28px; }
            .timeline {
              grid-template-columns: 68px 1fr 68px;
              gap: 14px;
              font-size: 15px;
            }
            input[type=range] {
              min-height: 42px;
              margin: 0;
              border: 0;
              background: transparent;
              -webkit-appearance: none;
            }
            input[type=range]::-webkit-slider-runnable-track {
              height: 8px;
              border-radius: 999px;
              background: rgba(255,255,255,.3);
            }
            input[type=range]::-webkit-slider-thumb {
              width: 28px;
              height: 28px;
              margin-top: -10px;
              border: 0;
              border-radius: 50%;
              background: var(--orange);
              -webkit-appearance: none;
            }
            .small, .episode-button {
              min-width: 58px;
              height: 58px;
              padding: 0 19px;
              border-radius: 999px;
              font-size: 16px;
            }
            .volume { width: 140px !important; }
            .volume-icon { width: 32px; height: 32px; }
            .position { font-size: 15px; }
            @media (min-width: 1500px) {
              .topbar {
                gap: 34px;
                padding: 40px 52px;
              }
              .top-left { gap: 32px; }
              .title { max-width: 48vw; font-size: 46px; }
              .subtitle { margin-top: 8px; font-size: 30px; }
              .back {
                min-width: 132px;
                height: 132px;
                padding: 0 42px;
                font-size: 36px;
              }
              .source-toggle, .source-single {
                min-height: 124px;
                gap: 20px;
                padding: 0 40px;
                font-size: 34px;
              }
              .source-caption { font-size: 25px; }
              .chevron { font-size: 42px; }
              .source-menu {
                top: calc(100% + 18px);
                width: 620px;
                padding: 18px;
                border-width: 2px;
                border-radius: 30px;
              }
              .source-menu-title { padding: 15px 22px 20px; font-size: 27px; }
              .source-option {
                min-height: 112px;
                padding: 22px 26px;
                border-radius: 22px;
                font-size: 32px;
              }
              .center-controls { gap: 52px; }
              .round {
                width: 132px;
                height: 132px;
                font-size: 34px;
              }
              .play {
                width: 184px;
                height: 184px;
                font-size: 70px;
              }
              .bottom { padding: 34px 54px 42px; }
              .timeline {
                grid-template-columns: 120px 1fr 120px;
                gap: 24px;
                font-size: 30px;
              }
              input[type=range] { min-height: 76px; }
              input[type=range]::-webkit-slider-runnable-track { height: 14px; }
              input[type=range]::-webkit-slider-thumb {
                width: 54px;
                height: 54px;
                margin-top: -20px;
              }
              .bottom-row { gap: 30px; margin-top: 16px; }
              .playback-actions, .episode-actions { gap: 22px; }
              .small, .episode-button {
                min-width: 116px;
                height: 112px;
                padding: 0 32px;
                border-radius: 999px;
                font-size: 31px;
              }
              .volume { width: 290px !important; }
              .volume-icon { width: 56px; height: 56px; }
              .position { font-size: 29px; }
              .tap-feedback {
                min-width: 190px;
                padding: 26px 34px;
                border-width: 2px;
                font-size: 34px;
              }
              .spinner {
                width: 90px;
                height: 90px;
                margin-bottom: 30px;
                border-width: 8px;
              }
              .loading-label { font-size: 34px; }
            }
          </style>
        </head>
        <body>
          <iframe
            id="hoshira-provider"
            allow="autoplay; fullscreen; encrypted-media; picture-in-picture"
            allowfullscreen
            referrerpolicy="origin"
          ></iframe>
          <div class="gesture-layer" id="gesture-layer">
            <div class="tap-feedback" id="tap-feedback"></div>
          </div>
          <div class="loading${if (config.showLoading) "" else " hidden"}" id="loading">
            <div>
              <div class="spinner"></div>
              <div class="loading-label">Загружаем плеер…</div>
            </div>
          </div>
          <div class="chrome" id="chrome">
            <div class="shade-top"></div>
            <div class="shade-bottom"></div>
            <div class="topbar">
              <div class="top-left">
                <button class="control back" id="back" type="button">← Назад</button>
                <div class="meta">
                  <div class="title">${config.title.escapeHtml()}</div>
                  <div class="subtitle">${config.subtitle.escapeHtml()}</div>
                </div>
              </div>
              $sourcePicker
            </div>
            <div class="center-controls" id="center-controls">
              <button class="control round" id="rewind" type="button">−10</button>
              <button class="control round play" id="center-play" type="button">▶</button>
              <button class="control round" id="forward" type="button">+10</button>
            </div>
            <div class="bottom">
              <div class="timeline">
                <span id="current-time">0:00</span>
                <input id="seek" type="range" min="0" max="1000" value="0">
                <span id="duration">0:00</span>
              </div>
              <div class="bottom-row">
                <div class="playback-actions">
                  <button class="control small" id="play-toggle" type="button">▶</button>
                  <svg class="volume-icon" viewBox="0 0 24 24" aria-hidden="true">
                    <path d="M4 9v6h4l5 4V5L8 9H4Z" stroke="none"></path>
                    <path d="M16 8.5c1.4 1.3 1.4 5.7 0 7M18.8 6c3 3 3 9 0 12"
                      fill="none" stroke-width="1.8" stroke-linecap="round"></path>
                  </svg>
                  <input class="volume" id="volume" type="range" min="0" max="1" step="0.01" value="${config.startupVolume.coerceIn(0f, 1f)}">
                </div>
                <span class="position">${config.position.escapeHtml()}</span>
                <div class="episode-actions">$previousButton$nextButton</div>
              </div>
            </div>
          </div>
          <script>
            (() => {
              const decode = value => new TextDecoder().decode(
                Uint8Array.from(atob(value), c => c.charCodeAt(0))
              );
              const playerUrl = decode("$encodedUrl");
              const preferredQuality = decode("$encodedQuality");
              const resumeSeconds = ${config.resumeSeconds.coerceAtLeast(0.0)};
              const controlsHideDelay = ${config.controlsHideDelayMs.coerceIn(1_500, 12_000)};
              const iframe = document.getElementById('hoshira-provider');
              const loading = document.getElementById('loading');
              const chrome = document.getElementById('chrome');
              const centerControls = document.getElementById('center-controls');
              const playToggle = document.getElementById('play-toggle');
              const centerPlay = document.getElementById('center-play');
              const seek = document.getElementById('seek');
              const volume = document.getElementById('volume');
              const currentTimeLabel = document.getElementById('current-time');
              const durationLabel = document.getElementById('duration');
              const gestureLayer = document.getElementById('gesture-layer');
              const tapFeedback = document.getElementById('tap-feedback');
              let activeVideo = null;
              let hasPlaybackStarted = false;
              let adjustingSystemVolume = false;
              let resumed = false;
              let hideTimer = 0;
              let feedbackTimer = 0;
              let pendingTapTimer = 0;
              let lastTapAt = 0;
              let lastTapZone = '';
              let pointerStart = null;

              const diagnostic = message => {
                try { HoshiraAndroid.diagnostic('host: ' + String(message)); } catch (_) {}
              };
              const action = (name, value = '') => {
                try { HoshiraAndroid.hostAction(String(name), String(value)); } catch (_) {}
              };
              const readSystemVolume = () => {
                try {
                  const value = Number(HoshiraAndroid.systemVolume());
                  return Number.isFinite(value) ? Math.min(1, Math.max(0, value)) : Number(volume.value);
                } catch (_) {
                  return Number(volume.value);
                }
              };
              volume.value = String(readSystemVolume());
              const formatTime = seconds => {
                const safe = Number.isFinite(seconds) ? Math.max(0, seconds) : 0;
                const minutes = Math.floor(safe / 60);
                return minutes + ':' + String(Math.floor(safe % 60)).padStart(2, '0');
              };
              const showChrome = () => {
                chrome.classList.remove('hidden');
                clearTimeout(hideTimer);
                if (activeVideo && !activeVideo.paused) {
                  hideTimer = setTimeout(() => chrome.classList.add('hidden'), controlsHideDelay);
                }
              };
              const hideChrome = () => {
                clearTimeout(hideTimer);
                chrome.classList.add('hidden');
              };
              const showTapFeedback = (text, x, y) => {
                tapFeedback.textContent = text;
                tapFeedback.style.left = x + 'px';
                tapFeedback.style.top = y + 'px';
                tapFeedback.classList.remove('visible');
                void tapFeedback.offsetWidth;
                tapFeedback.classList.add('visible');
                clearTimeout(feedbackTimer);
                feedbackTimer = setTimeout(() => tapFeedback.classList.remove('visible'), 620);
              };
              const seekBy = seconds => {
                if (!activeVideo) return;
                const duration = Number(activeVideo.duration);
                const maximum = Number.isFinite(duration) ? duration : Infinity;
                activeVideo.currentTime = Math.min(
                  maximum,
                  Math.max(0, Number(activeVideo.currentTime || 0) + seconds)
                );
                updateState();
                showChrome();
              };
              const updateState = () => {
                if (!activeVideo) return;
                const duration = Number(activeVideo.duration) || 0;
                const position = Number(activeVideo.currentTime) || 0;
                const paused = activeVideo.paused;
                playToggle.textContent = paused ? '▶' : '❚❚';
                centerPlay.textContent = paused ? '▶' : '❚❚';
                currentTimeLabel.textContent = formatTime(position);
                durationLabel.textContent = formatTime(duration);
                seek.value = duration > 0 ? String(Math.round(position / duration * 1000)) : '0';
                HoshiraAndroid.playback(position, duration, Number(volume.value) || 0, preferredQuality);
              };
              const connectVideo = video => {
                if (!video || video === activeVideo) return;
                activeVideo = video;
                hasPlaybackStarted = false;
                video.controls = false;
                video.removeAttribute('controls');
                video.setAttribute('playsinline', '');
                video.playsInline = true;
                video.muted = false;
                video.volume = 1;
                centerControls.classList.add('available');
                HoshiraAndroid.playerDetected(
                  Number(video.videoWidth) || 0,
                  Number(video.videoHeight) || 0,
                  document.querySelectorAll('iframe').length
                );
                diagnostic('video connected');
                const applyResume = () => {
                  if (!resumed && resumeSeconds > 0 && Number.isFinite(video.duration)) {
                    video.currentTime = Math.min(resumeSeconds, Math.max(0, video.duration - 2));
                    resumed = true;
                  }
                };
                video.addEventListener('loadedmetadata', applyResume);
                video.addEventListener('play', () => {
                  hasPlaybackStarted = true;
                  gestureLayer.classList.add('active');
                  showChrome();
                });
                video.addEventListener('pause', showChrome);
                video.addEventListener('timeupdate', updateState);
                video.addEventListener('volumechange', updateState);
                video.addEventListener('ended', () => HoshiraAndroid.ended());
                applyResume();
                updateState();
              };
              const collectRoots = (root, result) => {
                if (!root || result.includes(root)) return;
                result.push(root);
                root.querySelectorAll('iframe').forEach(frame => {
                  try { collectRoots(frame.contentDocument, result); } catch (_) {}
                });
              };
              const scan = () => {
                const roots = [];
                try { collectRoots(iframe.contentDocument, roots); } catch (_) {}
                const video = roots.map(root => root.querySelector('video')).find(Boolean);
                connectVideo(video);
                if (!activeVideo) {
                  diagnostic('scan no-video; roots=' + roots.length);
                }
              };
              const summarizeProviderDocument = () => {
                try {
                  const providerDocument = iframe.contentDocument;
                  if (!providerDocument) {
                    diagnostic('provider document unavailable');
                    return;
                  }
                  const bodyText = String(providerDocument.body?.innerText || '')
                    .replace(/\s+/g, ' ')
                    .trim()
                    .slice(0, 180);
                  diagnostic(
                    'provider document; ready=' + providerDocument.readyState +
                    '; title=' + String(providerDocument.title || '').slice(0, 80) +
                    '; body=' + (bodyText || '<empty>')
                  );
                  if (/контент\s+не\s+найден|content\s+not\s+found/i.test(bodyText)) {
                    action('provider-error', bodyText);
                  }
                } catch (error) {
                  diagnostic('provider document inaccessible: ' + String(error));
                }
              };
              const togglePlay = () => {
                if (!activeVideo) return;
                if (activeVideo.paused) activeVideo.play().catch(error => diagnostic('play failed: ' + error));
                else activeVideo.pause();
                showChrome();
              };
              const tapZone = x => {
                if (x < window.innerWidth * .36) return 'left';
                if (x > window.innerWidth * .64) return 'right';
                return 'center';
              };
              const handleSingleTap = () => {
                document.getElementById('source-menu')?.classList.remove('open');
                if (activeVideo && !hasPlaybackStarted && activeVideo.paused) {
                  togglePlay();
                  return;
                }
                if (chrome.classList.contains('hidden')) showChrome();
                else hideChrome();
              };
              const handleDoubleTap = (zone, x, y) => {
                if (zone === 'left') {
                  seekBy(-10);
                  showTapFeedback('−10 сек', x, y);
                } else if (zone === 'right') {
                  seekBy(10);
                  showTapFeedback('+10 сек', x, y);
                } else {
                  const wasPaused = activeVideo?.paused !== false;
                  togglePlay();
                  showTapFeedback(wasPaused ? '▶' : 'Ⅱ', x, y);
                }
              };

              document.getElementById('back').addEventListener('click', () => action('back'));
              document.getElementById('previous')?.addEventListener('click', () => action('previous'));
              document.getElementById('next')?.addEventListener('click', () => action('next'));
              document.getElementById('rewind').addEventListener('click', () => seekBy(-10));
              document.getElementById('forward').addEventListener('click', () => seekBy(10));
              playToggle.addEventListener('click', togglePlay);
              centerPlay.addEventListener('click', togglePlay);
              seek.addEventListener('input', () => {
                if (activeVideo && Number.isFinite(activeVideo.duration)) {
                  activeVideo.currentTime = Number(seek.value) / 1000 * activeVideo.duration;
                }
              });
              volume.addEventListener('input', () => {
                try { HoshiraAndroid.setSystemVolume(Number(volume.value)); } catch (_) {}
              });
              volume.addEventListener('pointerdown', () => { adjustingSystemVolume = true; });
              volume.addEventListener('pointerup', () => { adjustingSystemVolume = false; });
              volume.addEventListener('pointercancel', () => { adjustingSystemVolume = false; });
              document.getElementById('source-toggle')?.addEventListener('click', event => {
                event.stopPropagation();
                showChrome();
                document.getElementById('source-menu')?.classList.toggle('open');
              });
              document.querySelectorAll('.source-option').forEach(option => {
                option.addEventListener('click', () => {
                  if (!option.disabled) action('source', option.dataset.sourceId || '');
                });
              });
              document.querySelectorAll('.control,input[type=range],.source-option').forEach(element => {
                element.addEventListener('pointerdown', showChrome);
              });
              gestureLayer.addEventListener('pointerdown', event => {
                if (!event.isPrimary || event.button !== 0) return;
                pointerStart = {
                  id: event.pointerId,
                  x: event.clientX,
                  y: event.clientY,
                  at: performance.now()
                };
                document.getElementById('source-menu')?.classList.remove('open');
                try { gestureLayer.setPointerCapture(event.pointerId); } catch (_) {}
              });
              gestureLayer.addEventListener('pointerup', event => {
                const start = pointerStart;
                pointerStart = null;
                if (!start || start.id !== event.pointerId) return;
                const distance = Math.hypot(event.clientX - start.x, event.clientY - start.y);
                if (distance > Math.max(32, window.innerWidth * .018)) return;
                if (performance.now() - start.at > 480) return;
                const now = performance.now();
                const zone = tapZone(event.clientX);
                if (now - lastTapAt <= 320 && zone === lastTapZone) {
                  clearTimeout(pendingTapTimer);
                  pendingTapTimer = 0;
                  lastTapAt = 0;
                  lastTapZone = '';
                  handleDoubleTap(zone, event.clientX, event.clientY);
                } else {
                  clearTimeout(pendingTapTimer);
                  lastTapAt = now;
                  lastTapZone = zone;
                  pendingTapTimer = setTimeout(() => {
                    lastTapAt = 0;
                    lastTapZone = '';
                    handleSingleTap();
                  }, 340);
                }
              });
              gestureLayer.addEventListener('pointercancel', () => {
                pointerStart = null;
              });
              iframe.addEventListener('load', () => {
                loading.classList.add('hidden');
                action('provider-loaded');
                const loadedUrl = new URL(playerUrl);
                diagnostic(
                  'provider iframe loaded; src=' + loadedUrl.origin +
                  '; urlLength=' + playerUrl.length +
                  '; query=' + Boolean(loadedUrl.search)
                );
                setTimeout(summarizeProviderDocument, 50);
                setTimeout(summarizeProviderDocument, 800);
                setTimeout(scan, 50);
                setTimeout(scan, 500);
                setTimeout(scan, 1500);
              });
              iframe.addEventListener('error', () => {
                diagnostic('provider iframe error event');
              });
              setInterval(() => {
                if (!activeVideo || !activeVideo.isConnected) {
                  activeVideo = null;
                  hasPlaybackStarted = false;
                  gestureLayer.classList.remove('active');
                  centerControls.classList.remove('available');
                  scan();
                } else {
                  updateState();
                }
                if (!adjustingSystemVolume) {
                  volume.value = String(readSystemVolume());
                }
              }, 1000);
              setTimeout(() => {
                loading.classList.add('hidden');
                diagnostic('loading fallback reached');
              }, 8000);
              iframe.src = playerUrl;
              const installedUrl = new URL(playerUrl);
              diagnostic(
                'installed; provider=' + installedUrl.origin +
                '; pathLength=' + installedUrl.pathname.length +
                '; query=' + Boolean(installedUrl.search) +
                '; queryLength=' + installedUrl.search.length +
                '; viewport=' + window.innerWidth + 'x' + window.innerHeight +
                '; dpr=' + window.devicePixelRatio
              );
            })();
          </script>
        </body>
        </html>
    """.trimIndent()
}

private fun String.toBase64(): String =
    Base64.getEncoder().encodeToString(toByteArray(StandardCharsets.UTF_8))

private fun String.escapeHtml(): String = buildString(length) {
    this@escapeHtml.forEach { character ->
        append(
            when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&#39;"
                else -> character
            },
        )
    }
}
