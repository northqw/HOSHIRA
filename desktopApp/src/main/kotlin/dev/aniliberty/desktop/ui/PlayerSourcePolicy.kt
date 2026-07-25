package dev.aniliberty.desktop.ui

internal fun playerSourcePriority(name: String): Int = when {
    name.contains("Alloha", ignoreCase = true) -> 0
    name.contains("Kodik", ignoreCase = true) -> 1
    name.contains("Sibnet", ignoreCase = true) -> 2
    else -> 3
}
