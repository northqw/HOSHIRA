package dev.aniliberty.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aniliberty.desktop.data.AnimeListKind
import coil3.compose.AsyncImage
import dev.aniliberty.desktop.data.CatalogFilters
import dev.aniliberty.desktop.data.CatalogSort
import dev.aniliberty.desktop.model.ReleaseDto

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun PrimaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: String? = null,
) {
    var hovered by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (hovered) 1.035f else 1f)

    Row(
        modifier = modifier
            .height(54.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(
                Brush.horizontalGradient(
                    if (hovered) {
                        listOf(AniColors.OrangeBright, AniColors.Amber)
                    } else {
                        listOf(AniColors.Orange, AniColors.OrangeBright)
                    },
                ),
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 27.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leading != null) {
            Text(
                leading,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.width(11.dp))
        }
        Text(
            label,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun SecondaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: String? = null,
) {
    var hovered by remember { mutableStateOf(false) }
    val container by animateColorAsState(
        if (hovered) AniColors.SurfaceHighest else AniColors.SurfaceHigh.copy(alpha = 0.94f),
    )
    val scale by animateFloatAsState(if (hovered) 1.025f else 1f)

    Row(
        modifier = modifier
            .height(52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(container)
            .border(1.dp, AniColors.Border.copy(alpha = 0.72f), CircleShape)
            .pointerHoverIcon(PointerIcon.Hand)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leading != null) {
            Text(leading, color = AniColors.Text, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
        }
        Text(
            label,
            color = AniColors.Text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun MetaChip(
    text: String,
    accent: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(if (accent) AniColors.Orange.copy(alpha = 0.22f) else AniColors.SurfaceHigh)
            .border(
                width = 1.dp,
                color = if (accent) AniColors.Orange.copy(alpha = 0.72f) else AniColors.Border,
                shape = CircleShape,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            color = if (accent) Color(0xFFFFB27A) else AniColors.Text,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun CarouselArrow(
    forward: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var hovered by remember { mutableStateOf(false) }
    val container by animateColorAsState(
        when {
            !enabled -> AniColors.Surface.copy(alpha = 0.45f)
            hovered -> AniColors.Orange
            else -> AniColors.SurfaceHigh
        },
    )
    val scale by animateFloatAsState(if (hovered && enabled) 1.06f else 1f)

    Box(
        modifier = modifier
            .size(46.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.42f
            }
            .clip(CircleShape)
            .background(container)
            .border(1.dp, if (hovered && enabled) AniColors.OrangeBright else AniColors.Border, CircleShape)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (forward) "›" else "‹",
            color = AniColors.Text,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.graphicsLayer { translationY = -1f },
        )
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun DubbingDropdown(
    options: List<Pair<String, Int>>,
    selected: String?,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Выберите студию",
    countSuffix: String = "сер.",
) {
    var expanded by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf(false) }
    val container by animateColorAsState(
        when {
            expanded -> AniColors.SurfaceHighest
            hovered -> AniColors.SurfaceHighest
            else -> AniColors.SurfaceHigh
        },
    )
    val border by animateColorAsState(
        if (expanded || hovered) AniColors.OrangeBright else AniColors.Border,
    )
    val selectedOption = options.firstOrNull { it.first == selected }

    Box(
        modifier = modifier.width(420.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(CircleShape)
                .background(container)
                .border(1.dp, border, CircleShape)
                .pointerHoverIcon(PointerIcon.Hand)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { expanded = !expanded },
                )
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selectedOption?.first ?: placeholder,
                color = AniColors.Text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            selectedOption?.let {
                Text(
                    text = "${it.second} $countSuffix",
                    color = AniColors.TextMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.width(14.dp))
            }
            Text(
                text = if (expanded) "⌃" else "⌄",
                color = AniColors.OrangeBright,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .width(420.dp)
                .background(AniColors.SurfaceHighest),
        ) {
            options.forEach { option ->
                val isSelected = option.first == selected
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = option.first,
                                color = AniColors.Text,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${option.second} $countSuffix",
                                color = if (isSelected) AniColors.OrangeBright else AniColors.TextMuted,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    },
                    onClick = {
                        onSelected(option.first)
                        expanded = false
                    },
                    modifier = Modifier.background(
                        if (isSelected) AniColors.Orange.copy(alpha = 0.12f) else Color.Transparent,
                    ),
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun AnimeListDropdown(
    selected: AnimeListKind?,
    onSelected: (AnimeListKind?) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf(false) }
    val active = enabled && (expanded || hovered)
    val container by animateColorAsState(
        if (active) AniColors.SurfaceHighest else AniColors.SurfaceHigh,
    )
    val border by animateColorAsState(
        if (active) AniColors.OrangeBright else AniColors.Border,
    )

    Box(modifier.width(280.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(CircleShape)
                .background(container)
                .border(1.dp, border, CircleShape)
                .pointerHoverIcon(PointerIcon.Hand)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .clickable(
                    enabled = enabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { expanded = !expanded },
                )
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selected?.title ?: "Добавить в список",
                color = if (enabled) AniColors.Text else AniColors.TextMuted,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) "⌃" else "⌄",
                color = AniColors.OrangeBright,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .width(280.dp)
                .background(AniColors.SurfaceHighest),
        ) {
            AnimeListKind.entries
                .filterNot { it == AnimeListKind.Favorite }
                .forEach { kind ->
                    val isSelected = kind == selected
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = kind.title,
                                color = if (isSelected) AniColors.OrangeBright else AniColors.Text,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                            )
                        },
                        onClick = {
                            onSelected(kind)
                            expanded = false
                        },
                        modifier = Modifier.background(
                            if (isSelected) AniColors.Orange.copy(alpha = 0.12f) else Color.Transparent,
                        ),
                    )
                }
            if (selected != null) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "Убрать из списка",
                            color = Color(0xFFFF8A8A),
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    onClick = {
                        onSelected(null)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun CatalogFilterBar(
    filters: CatalogFilters,
    onChange: (CatalogFilters) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CatalogDropdown(
            caption = "Тип",
            selected = filters.type,
            options = catalogTypeOptions,
            onSelected = { onChange(filters.copy(type = it)) },
        )
        CatalogDropdown(
            caption = "Статус",
            selected = filters.status,
            options = catalogStatusOptions,
            onSelected = { onChange(filters.copy(status = it)) },
        )
        CatalogDropdown(
            caption = "Сезон",
            selected = filters.season,
            options = catalogSeasonOptions,
            onSelected = { onChange(filters.copy(season = it)) },
        )
        CatalogDropdown(
            caption = "Жанр",
            selected = filters.genre,
            options = catalogGenreOptions,
            width = 190,
            onSelected = { onChange(filters.copy(genre = it)) },
        )
        CatalogDropdown(
            caption = "Год",
            selected = yearFilterValue(filters),
            options = catalogYearOptions,
            onSelected = { value ->
                val bounds = value
                    ?.split(':')
                    ?.mapNotNull(String::toIntOrNull)
                    .orEmpty()
                onChange(
                    filters.copy(
                        fromYear = bounds.getOrNull(0),
                        toYear = bounds.getOrNull(1),
                    ),
                )
            },
        )
        CatalogDropdown(
            caption = "Рейтинг",
            selected = filters.minRating?.toString(),
            options = catalogRatingOptions,
            onSelected = { value ->
                onChange(filters.copy(minRating = value?.toDoubleOrNull()))
            },
        )
        CatalogDropdown(
            caption = "Сортировка",
            selected = filters.sort.name,
            options = catalogSortOptions,
            width = 210,
            onSelected = { value ->
                val sort = CatalogSort.entries.firstOrNull { it.name == value }
                    ?: CatalogSort.Newest
                onChange(filters.copy(sort = sort))
            },
        )
        if (filters != CatalogFilters()) {
            Row(
                modifier = Modifier
                    .height(46.dp)
                    .clip(CircleShape)
                    .border(1.dp, AniColors.Border, CircleShape)
                    .background(AniColors.SurfaceHigh)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onChange(CatalogFilters()) },
                    )
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("×", color = AniColors.OrangeBright, fontWeight = FontWeight.ExtraBold)
                Text(
                    "Сбросить",
                    color = AniColors.Text,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun CatalogDropdown(
    caption: String,
    selected: String?,
    options: List<CatalogChoice>,
    onSelected: (String?) -> Unit,
    width: Int = 160,
) {
    var expanded by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == selected }?.label
        ?: options.first().label
    val border by animateColorAsState(
        if (expanded || hovered) AniColors.OrangeBright else AniColors.Border,
    )

    Box(Modifier.width(width.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(CircleShape)
                .border(1.dp, border, CircleShape)
                .background(if (expanded) AniColors.SurfaceHighest else AniColors.SurfaceHigh)
                .pointerHoverIcon(PointerIcon.Hand)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { expanded = !expanded },
                )
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    caption.uppercase(),
                    color = AniColors.TextMuted,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    selectedLabel,
                    color = AniColors.Text,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (expanded) "⌃" else "⌄",
                color = AniColors.OrangeBright,
                fontWeight = FontWeight.ExtraBold,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .width(width.dp)
                .background(AniColors.SurfaceHighest),
        ) {
            options.forEach { option ->
                val isSelected = option.value == selected
                DropdownMenuItem(
                    text = {
                        Text(
                            option.label,
                            color = if (isSelected) AniColors.OrangeBright else AniColors.Text,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                        )
                    },
                    onClick = {
                        onSelected(option.value)
                        expanded = false
                    },
                    modifier = Modifier.background(
                        if (isSelected) AniColors.Orange.copy(alpha = 0.12f) else Color.Transparent,
                    ),
                )
            }
        }
    }
}

private data class CatalogChoice(
    val label: String,
    val value: String?,
)

private fun yearFilterValue(filters: CatalogFilters): String? =
    filters.fromYear?.let { from -> "$from:${filters.toYear ?: from}" }

private val catalogTypeOptions = listOf(
    CatalogChoice("Все", null),
    CatalogChoice("Сериал", "tv"),
    CatalogChoice("Фильм", "movie"),
    CatalogChoice("OVA", "ova"),
    CatalogChoice("ONA", "ona"),
    CatalogChoice("Спешл", "special"),
    CatalogChoice("Короткий метр", "shortfilm"),
)

private val catalogStatusOptions = listOf(
    CatalogChoice("Все", null),
    CatalogChoice("Онгоинг", "ongoing"),
    CatalogChoice("Завершён", "released"),
    CatalogChoice("Анонс", "announcement"),
)

private val catalogSeasonOptions = listOf(
    CatalogChoice("Все", null),
    CatalogChoice("Зима", "winter"),
    CatalogChoice("Весна", "spring"),
    CatalogChoice("Лето", "summer"),
    CatalogChoice("Осень", "autumn"),
)

private val catalogGenreOptions = listOf(
    CatalogChoice("Все", null),
    CatalogChoice("Экшен", "ekshen"),
    CatalogChoice("Приключения", "priklyucheniya"),
    CatalogChoice("Фэнтези", "fentezi"),
    CatalogChoice("Исекай", "isekai"),
    CatalogChoice("Комедия", "komediya"),
    CatalogChoice("Романтика", "romantika"),
    CatalogChoice("Драма", "drama"),
    CatalogChoice("Детектив", "detektiv"),
    CatalogChoice("Триллер", "triller"),
    CatalogChoice("Ужасы", "ugasy"),
    CatalogChoice("Повседневность", "povsednevnost"),
    CatalogChoice("Спорт", "sport"),
)

private val catalogYearOptions = listOf(
    CatalogChoice("Все", null),
    CatalogChoice("2026", "2026:2026"),
    CatalogChoice("2025", "2025:2025"),
    CatalogChoice("2024", "2024:2024"),
    CatalogChoice("2020–2023", "2020:2023"),
    CatalogChoice("2010–2019", "2010:2019"),
    CatalogChoice("До 2010", "1900:2009"),
)

private val catalogRatingOptions = listOf(
    CatalogChoice("Любой", null),
    CatalogChoice("от 7", "7.0"),
    CatalogChoice("от 8", "8.0"),
    CatalogChoice("от 9", "9.0"),
)

private val catalogSortOptions = listOf(
    CatalogChoice("Сначала новые", CatalogSort.Newest.name),
    CatalogChoice("По рейтингу", CatalogSort.Rating.name),
    CatalogChoice("По году", CatalogSort.Year.name),
    CatalogChoice("По популярности", CatalogSort.Popular.name),
    CatalogChoice("По названию", CatalogSort.Title.name),
)

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun PosterCard(
    release: ReleaseDto,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var hovered by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (hovered) 1.045f else 1f)
    val borderColor by animateColorAsState(
        if (hovered) AniColors.Orange.copy(alpha = 0.85f) else AniColors.Border,
    )

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .pointerHoverIcon(PointerIcon.Hand)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(AniColors.Surface)
                .border(1.dp, borderColor, RoundedCornerShape(12.dp)),
        ) {
            RemoteImage(
                url = release.posterUrl,
                contentDescription = release.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )

            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.45f to Color.Transparent,
                            1f to AniColors.Background.copy(alpha = 0.96f),
                        ),
                    ),
            )

            if (release.isOngoing) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(AniColors.Orange)
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    Text(
                        "ОНГОИНГ",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }

            if (hovered) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(AniColors.Orange),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("▶", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(14.dp),
            ) {
                Text(
                    text = release.displayName,
                    color = AniColors.Text,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = listOfNotNull(
                        release.year?.toString(),
                        release.latestEpisode?.let { "${it.displayOrdinal} эп." },
                    ).joinToString("  •  "),
                    color = AniColors.TextMuted,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                listOf(AniColors.SurfaceHigh, AniColors.Surface, AniColors.BackgroundSoft),
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                filterQuality = FilterQuality.High,
            )
        } else {
            Text(
                "H",
                color = AniColors.TextMuted.copy(alpha = 0.45f),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun LoadingState(
    label: String = "Загружаем релизы…",
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            color = AniColors.Orange,
            strokeWidth = 3.dp,
            modifier = Modifier.size(38.dp),
        )
        Spacer(Modifier.height(18.dp))
        Text(label, color = AniColors.TextMuted)
    }
}

@Composable
fun StartupSplash(modifier: Modifier = Modifier) {
    val motion = rememberInfiniteTransition()
    val pulse by motion.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_350, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )
    val rotation by motion.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4_800, easing = LinearEasing),
        ),
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF050506)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(154.dp).graphicsLayer {
                    scaleX = pulse
                    scaleY = pulse
                },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { rotationZ = rotation },
                ) {
                    drawArc(
                        color = Color.White.copy(alpha = 0.82f),
                        startAngle = 18f,
                        sweepAngle = 225f,
                        useCenter = false,
                        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = Color.White.copy(alpha = 0.24f),
                        startAngle = 268f,
                        sweepAngle = 55f,
                        useCenter = false,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
                Box(
                    Modifier
                        .size(92.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF050506))
                        .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    BrandGlyph(
                        Modifier.size(58.dp),
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            BrandMark()
            Spacer(Modifier.height(12.dp))
            Text(
                "Ваше аниме — без лишнего",
                color = AniColors.TextMuted,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Не удалось загрузить данные",
            style = MaterialTheme.typography.headlineMedium,
            color = AniColors.Text,
        )
        Spacer(Modifier.height(10.dp))
        Text(message, color = AniColors.TextMuted)
        Spacer(Modifier.height(22.dp))
        PrimaryAction("Повторить", onRetry, leading = "↻")
    }
}

@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BrandGlyph(Modifier.size(36.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            "HOSHIRA",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

@Composable
fun BrandGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val width = size.width
        val height = size.height
        val strokeWidth = size.minDimension * 0.065f
        val outline = Path().apply {
            moveTo(width * 0.50f, height * 0.05f)
            lineTo(width * 0.84f, height * 0.24f)
            lineTo(width * 0.84f, height * 0.76f)
            lineTo(width * 0.50f, height * 0.95f)
            lineTo(width * 0.16f, height * 0.76f)
            lineTo(width * 0.16f, height * 0.24f)
            close()
        }
        drawPath(
            path = outline,
            color = Color.White.copy(alpha = 0.92f),
            style = Stroke(width = strokeWidth),
        )
        drawLine(
            color = Color.White,
            start = androidx.compose.ui.geometry.Offset(width * 0.36f, height * 0.29f),
            end = androidx.compose.ui.geometry.Offset(width * 0.30f, height * 0.72f),
            strokeWidth = strokeWidth * 1.22f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color.White,
            start = androidx.compose.ui.geometry.Offset(width * 0.70f, height * 0.28f),
            end = androidx.compose.ui.geometry.Offset(width * 0.64f, height * 0.71f),
            strokeWidth = strokeWidth * 1.22f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color.White,
            start = androidx.compose.ui.geometry.Offset(width * 0.33f, height * 0.52f),
            end = androidx.compose.ui.geometry.Offset(width * 0.67f, height * 0.48f),
            strokeWidth = strokeWidth * 1.22f,
            cap = StrokeCap.Round,
        )
    }
}
