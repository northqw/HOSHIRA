package androidx.compose.ui.input.pointer

import androidx.compose.ui.Modifier

/**
 * Android counterpart for the desktop pointer-event modifier used by the
 * tablet UI. Touch input normally does not emit hover events, while a mouse or
 * stylus connected to a tablet still can.
 */
fun Modifier.onPointerEvent(
    eventType: PointerEventType,
    onEvent: (PointerEvent) -> Unit,
): Modifier = pointerInput(eventType, onEvent) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == eventType) {
                onEvent(event)
            }
        }
    }
}
