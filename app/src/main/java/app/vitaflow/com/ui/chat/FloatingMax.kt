package app.vitaflow.com.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vitaflow.com.AppContainer
import app.vitaflow.com.data.db.ChatMessageEntity
import app.vitaflow.com.ui.home.Dest
import app.vitaflow.com.ui.theme.GlassSurface
import app.vitaflow.com.ui.theme.GlassTextField
import app.vitaflow.com.ui.theme.LocalGlass

/**
 * Max follows you around the app: a small round button on every screen that opens a compact chat panel.
 * It uses the same chat as the full screen, so nothing is lost when you move between them.
 */
@Composable
fun BoxScope.FloatingMax(
    vm: ChatViewModel,
    container: AppContainer,
    open: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onFull: () -> Unit,
    onGo: (Dest) -> Unit
) {
    val g = LocalGlass.current
    val messages by vm.messages.collectAsStateWithLifecycle()
    var changeIds by remember { mutableStateOf<List<Long>?>(null) }

    if (!open) {
        Box(
            Modifier.align(Alignment.BottomEnd).padding(16.dp).size(54.dp).clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(g.accent, g.accent2)))
                .clickable(onClick = onOpen),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.AutoAwesome, contentDescription = "Max", tint = g.onAccent) }
    } else {
        val listState = rememberLazyListState()
        val shown = messages.takeLast(10)
        LaunchedEffect(shown.size, vm.streaming) {
            val last = shown.size + (if (vm.streaming != null) 1 else 0) - 1
            if (last >= 0) listState.scrollToItem(last)
        }
        GlassSurface(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.55f).padding(10.dp),
            shape = RoundedCornerShape(24.dp), strong = true
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Max", color = g.textPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onFull) { Icon(Icons.Rounded.OpenInFull, contentDescription = "পুরো পর্দা", tint = g.textSecondary) }
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "বন্ধ", tint = g.textSecondary) }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(shown, key = { it.id }) { m -> Message(m, onOpen = { changeIds = it }, onSpeak = null) }
                    vm.streaming?.let { partial ->
                        item { Message(ChatMessageEntity(id = -1, accountId = 0, role = "MAX", text = partial), {}, null) }
                    }
                    if (vm.busy && vm.streaming == null) {
                        item { Text("ভাবছি...", color = g.textSecondary, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                vm.notice?.let { Text(it, color = g.danger, style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { vm.clearNotice() }) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassTextField(vm.input, { vm.input = it }, "Max-কে লিখুন", modifier = Modifier.weight(1f))
                    if (vm.input.isNotBlank() && !vm.busy) RoundButton(Icons.Rounded.ArrowUpward, "পাঠান", { vm.send(vm.input) })
                }
            }
        }
    }
    changeIds?.let { ids -> ChangeDialog(ids, container, onDismiss = { changeIds = null }, onGo = { changeIds = null; onGo(it) }) }
}
