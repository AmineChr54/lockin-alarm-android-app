package app.lockinalarm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---------- line icons (24 x 24 viewBox, stroked; `fills` are filled) ----------
private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} $cy A$r $r 0 1 0 ${cx + r} $cy A$r $r 0 1 0 ${cx - r} $cy Z"

enum class Ic(val strokes: List<String>, val fills: List<String> = emptyList()) {
    HOME(listOf("M4 11 L12 4 L20 11 V20 H14.5 V14.5 H9.5 V20 H4 Z")),
    ALARM(listOf(circle(12f, 13f, 7f), "M12 9.5 V13 L14.5 14.5 M5 5 L7.5 3 M19 5 L16.5 3")),
    TAG(listOf("M3.5 12 V4 H11.5 L20.5 13 L13 20.5 Z"), listOf(circle(7.8f, 8.2f, 1.5f))),
    SETTINGS(listOf("M4 7 H13.5 M18.5 7 H20 M4 17 H5.5 M10.5 17 H20", circle(16f, 7f, 2.3f), circle(8f, 17f, 2.3f))),
    BACK(listOf("M15 5 L8 12 L15 19")),
    NEXT(listOf("M9 5 L16 12 L9 19")),
    PLAY(emptyList(), listOf("M8 5 L19 12 L8 19 Z")),
    STOP(emptyList(), listOf("M7 7 H17 V17 H7 Z")),
    CHECK(listOf("M5 12.5 L10 17 L19 7")),
    PLUS(listOf("M12 5 V19 M5 12 H19")),
    EDIT(listOf("M4 20 L8 19 L19 8 L16 5 L5 16 Z")),
    TRASH(listOf("M5 7 H19 M9 7 V4 H15 V7 M7 7 L8 20 H16 L17 7")),
    NFC(listOf("M7 8 A6 6 0 0 1 7 16 M10.5 6 A9 9 0 0 1 10.5 18 M14 4 A12 12 0 0 1 14 20")),
    CHART(listOf("M5 20 V12 M12 20 V5 M19 20 V9")),
    LOCK(listOf("M6 11 H18 V20 H6 Z M8.5 11 V8 A3.5 3.5 0 0 1 15.5 8 V11")),
    SHARE(listOf(circle(17f, 5.5f, 2.5f), circle(6f, 12f, 2.5f), circle(17f, 18.5f, 2.5f), "M8.3 10.8 L14.7 6.8 M8.3 13.2 L14.7 17.2"));

    val parsed by lazy { strokes.map { PathParser().parsePathString(it).toPath() } to fills.map { PathParser().parsePathString(it).toPath() } }
}

@Composable
fun Icon(ic: Ic, color: Color = C.text, size: Dp = 22.dp, stroke: Float = 1.8f, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 24f
        scale(k, k, pivot = Offset.Zero) {
            ic.parsed.first.forEach { drawPath(it, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
            ic.parsed.second.forEach { drawPath(it, color) }
        }
    }
}

// ---------- surfaces ----------
@Composable
fun TCard(modifier: Modifier = Modifier, color: Color = C.card, border: Boolean = true, radius: Dp = 20.dp, pad: PaddingValues = PaddingValues(16.dp), onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier.fillMaxWidth().clip(shape).background(color)
            .then(if (border) Modifier.border(1.dp, C.line, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(pad),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, dot: Color? = null, color: Color = C.text, borderColor: Color = C.lineHi, onClick: (() -> Unit)? = null) {
    Row(
        modifier.clip(CircleShape).border(1.dp, borderColor, CircleShape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (dot != null) Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(text, style = T.sans(13), color = color)
    }
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(starred(title), style = T.serif(25), modifier = Modifier.weight(1f))
        if (action != null) Text(action, style = T.sans(14), color = C.text.copy(alpha = 0.85f),
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onAction).padding(6.dp))
    }
}

@Composable
fun BigButton(text: String, modifier: Modifier = Modifier, color: Color = C.coral, textColor: Color = Color.White, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().height(58.dp).clip(CircleShape).background(if (enabled) color else C.cardHi)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(starred(text), style = T.serif(23), color = if (enabled) textColor else C.muted) }
}

@Composable
fun CircleButton(ic: Ic, label: String, modifier: Modifier = Modifier, bg: Color = C.cardHi, tint: Color = C.text, size: Dp = 42.dp, onClick: () -> Unit) {
    Box(
        modifier.size(size).clip(CircleShape).background(bg).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(ic, tint, size * 0.43f, stroke = 2f) }
}

/** A sub-screen: round back button, serif title in the middle, optional action on the right. */
@Composable
fun TopBar(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CircleButton(Ic.BACK, "Back", onClick = onBack)
        Text(starred(title), style = T.serif(24), textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        Box(Modifier.width(42.dp), contentAlignment = Alignment.Center) { action?.invoke() }
    }
}

// ---------- the floating pill nav ----------
data class NavItem(val id: String, val ic: Ic, val label: String)

@Composable
fun PillNav(items: List<NavItem>, selected: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    Row(
        modifier.fillMaxWidth().height(66.dp).shadow(18.dp, CircleShape).clip(CircleShape).background(Color(0xFF2A2B2F))
            .border(1.dp, Color(0xFF38393E), CircleShape).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically,
    ) {
        for (it in items) {
            val on = it.id == selected
            Box(
                Modifier.size(50.dp).clip(CircleShape).background(if (on) C.coral else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(it.id) }.semantics { contentDescription = it.label },
                contentAlignment = Alignment.Center,
            ) { Icon(it.ic, if (on) C.ink else Color(0xFFC2C3C8), 22.dp, stroke = if (on) 2f else 1.8f) }
        }
    }
}

// ---------- settings rows ----------
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1E1F22)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { i, o ->
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (i == selected) C.green else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(i) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) { Text(o, style = T.sans(13), color = if (i == selected) C.text else C.muted) }
        }
    }
}

@Composable
fun ValueRow(title: String, value: String, sub: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = T.sans(15))
            if (sub != null) Text(sub, style = T.sans(12), color = C.muted)
        }
        Text(value, style = T.sans(14), color = Color(0xFFD6D5D0))
        Spacer(Modifier.width(4.dp))
        Icon(Ic.NEXT, C.muted, 14.dp)
    }
}

@Composable
fun ToggleRow(title: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = T.sans(15))
            if (sub != null) Text(sub, style = T.sans(12), color = C.muted)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(
            checkedThumbColor = C.text, checkedTrackColor = C.green, uncheckedThumbColor = C.muted, uncheckedTrackColor = C.cardHi, uncheckedBorderColor = C.lineHi,
        ))
    }
}

@Composable
fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))

// ---------- dialogs ----------
@Composable
fun InputDialog(title: String, initial: String, number: Boolean = false, hint: String? = null, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.card, shape = RoundedCornerShape(26.dp),
        title = { Text(starred(title), style = T.serif(26)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hint != null) Text(hint, style = T.sans(13), color = C.muted)
                OutlinedTextField(
                    v, { v = it }, singleLine = true, textStyle = T.sans(17),
                    keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.coral, unfocusedBorderColor = C.lineHi, cursorColor = C.coral),
                )
            }
        },
        confirmButton = { TextButton({ onDone(v) }) { Text("Save", style = T.sans(15, 600), color = C.coral) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", style = T.sans(15), color = C.muted) } },
    )
}

@Composable
fun ChoiceDialog(title: String, options: List<String>, selected: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.card, shape = RoundedCornerShape(26.dp),
        title = { Text(starred(title), style = T.serif(26)) },
        text = {
            Column {
                options.forEachIndexed { i, o ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(i) }.padding(vertical = 12.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(o, style = T.sans(16), modifier = Modifier.weight(1f))
                        if (i == selected) Icon(Ic.CHECK, C.coral, 20.dp, stroke = 2.4f)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("Close", style = T.sans(15), color = C.muted) } },
    )
}
