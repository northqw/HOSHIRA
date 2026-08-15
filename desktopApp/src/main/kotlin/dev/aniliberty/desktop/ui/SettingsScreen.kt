package dev.aniliberty.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aniliberty.desktop.data.PlayerPreferences
import dev.aniliberty.desktop.data.PreferredPlayerSource
import dev.aniliberty.desktop.data.ResumeBehavior

@Composable
fun SettingsScreen(
    preferences: PlayerPreferences,
    portableMode: Boolean,
    onChange: (PlayerPreferences) -> Unit,
    onClearHistory: () -> Unit,
    onClearCaches: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(AniColors.Background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 72.dp,
            end = 72.dp,
            top = 126.dp,
            bottom = 72.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Text("Настройки", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text(
                if (portableMode) {
                    "Portable-режим · данные хранятся рядом с приложением"
                } else {
                    "Настройки Hoshira 0.3.0"
                },
                color = AniColors.TextMuted,
            )
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = AniColors.Border)
        }
        item {
            SettingsSection("Плеер") {
                ChoiceSetting(
                    title = "Источник по умолчанию",
                    value = preferences.preferredSource
                        .takeUnless { it == PreferredPlayerSource.Alloha }
                        ?.displayName
                        ?: PreferredPlayerSource.Kodik.displayName,
                    onClick = {
                        val values = PreferredPlayerSource.entries
                            .filterNot { it == PreferredPlayerSource.Alloha }
                        val current = preferences.preferredSource
                            .takeIf(values::contains)
                            ?: PreferredPlayerSource.Kodik
                        val next = values[(values.indexOf(current) + 1) % values.size]
                        onChange(preferences.copy(preferredSource = next))
                    },
                    subtitle = "Alloha — поддержка появится позже",
                )
                ToggleSetting(
                    title = "Автоматически открывать на весь экран",
                    checked = preferences.autoFullscreen,
                    onCheckedChange = { onChange(preferences.copy(autoFullscreen = it)) },
                )
                ToggleSetting(
                    title = "Автоматически включать следующую серию",
                    subtitle = "После 8-секундного обратного отсчёта",
                    checked = preferences.autoplayNext,
                    onCheckedChange = { onChange(preferences.copy(autoplayNext = it)) },
                )
                ChoiceSetting(
                    title = "Возобновление просмотра",
                    value = preferences.resumeBehavior.displayName,
                    onClick = {
                        val values = ResumeBehavior.entries
                        val next = values[(values.indexOf(preferences.resumeBehavior) + 1) % values.size]
                        onChange(preferences.copy(resumeBehavior = next))
                    },
                )
                ToggleSetting(
                    title = "Аппаратное ускорение",
                    subtitle = "Изменение применяется после перезапуска приложения",
                    checked = preferences.hardwareAcceleration,
                    onCheckedChange = { onChange(preferences.copy(hardwareAcceleration = it)) },
                )
            }
        }
        item {
            SettingsSection("Данные") {
                ActionSetting(
                    title = "Очистить историю просмотра",
                    subtitle = "Удалит прогресс и раздел «Продолжить просмотр»",
                    action = "Очистить",
                    onClick = onClearHistory,
                )
                ActionSetting(
                    title = "Очистить кэш",
                    subtitle = "Изображения и временные данные плеера",
                    action = "Очистить",
                    onClick = onClearCaches,
                )
            }
        }
        item {
            Text(
                "Hoshira Desktop 0.3.0",
                color = AniColors.TextMuted.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(AniColors.Surface)
            .padding(24.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
private fun ToggleSetting(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    SettingRow(title, subtitle) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ChoiceSetting(
    title: String,
    value: String,
    onClick: () -> Unit,
    subtitle: String = "Нажмите, чтобы выбрать следующий вариант",
) {
    SettingRow(title, subtitle) {
        Text(
            value,
            color = AniColors.OrangeBright,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ActionSetting(
    title: String,
    subtitle: String,
    action: String,
    onClick: () -> Unit,
) {
    SettingRow(title, subtitle) {
        Text(
            action,
            color = Color(0xFFFF8B6B),
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String?,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = AniColors.TextMuted)
            }
        }
        Spacer(Modifier.width(20.dp))
        trailing()
    }
}
