package com.verisonder.sondervault.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verisonder.sondervault.ui.theme.Body
import com.verisonder.sondervault.ui.theme.Palette
import com.verisonder.sondervault.ui.theme.display
import kotlinx.coroutines.delay

// The building pieces of the Verisonder look, shared with SonderShield.

val blockShape = RoundedCornerShape(18.dp)
val Gutter = 22.dp

/** "▲ LABEL": small uppercase label with a triangle, in the accent. */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier, color: Color = Palette.accentText) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp, 9.dp)) {
            val p = Path().apply { moveTo(size.width / 2, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(p, color)
        }
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), color = color, fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp)
    }
}

/** Screen title: optional kicker, big Unbounded word, optional line under it. */
@Composable
fun ScreenHeader(
    title: String,
    kicker: String? = null,
    sub: String? = null,
    size: Int = 36,
    action: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(start = Gutter, end = 12.dp, top = 14.dp, bottom = 6.dp)) {
        if (kicker != null) Kicker(kicker, Modifier.padding(bottom = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = display(size), color = Palette.text, modifier = Modifier.weight(1f))
            action()
        }
        if (sub != null) Text(sub, color = Palette.dim, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp, end = 10.dp))
    }
}

/**
 * The top of every screen below the grid: a round back (or close) button on the left,
 * optional actions on the right. The screen's title sits under it as a [ScreenHeader].
 */
@Composable
fun TopBar(
    onBack: (() -> Unit)?,
    close: Boolean = false,
    enabled: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 14.dp, end = 14.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconPill(if (close) LineIcons.close else LineIcons.back, if (close) "Close" else "Back", enabled = enabled, onClick = onBack)
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** A numbered section: thick line on top, "01" in the accent, a title. */
@Composable
fun Section(number: Int, title: String, first: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(top = 24.dp)) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(if (first) Palette.accent else Palette.text))
        Text("%02d".format(number), style = display(15), color = Palette.accentText, modifier = Modifier.padding(top = 14.dp))
        Text(title, color = Palette.text, fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 19.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
        content()
    }
}

/** Rounded block: the building piece of every card. */
@Composable
fun Block(
    modifier: Modifier = Modifier,
    color: Color = Palette.panel,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    Column(
        modifier.fillMaxWidth().let { if (onClick != null) it.pressScale(src) else it }.clip(blockShape).background(color)
            .let { if (onClick != null) it.clickable(interactionSource = src, indication = ripple(), onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        content = content,
    )
}

/** Card with a title, a line under it and something on the right. Picked = filled with the accent. */
@Composable
fun OptionCard(
    title: String,
    sub: String? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val bg by animateColorAsState(if (selected) Palette.accent else Palette.panel, tween(240), label = "card")
    val fg by animateColorAsState(if (selected) Palette.onAccent else Palette.text, tween(240), label = "cardText")
    Block(Modifier.padding(bottom = 8.dp), color = bg, onClick = if (enabled) onClick else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = if (enabled) fg else Palette.faint, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                if (!sub.isNullOrEmpty()) {
                    Text(
                        sub, color = if (selected) fg.copy(alpha = 0.8f) else Palette.dim,
                        fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            trailing()
        }
    }
}

/** On/off switch: accent track with a dark knob when on. */
@Composable
fun Toggle(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val track by animateColorAsState(if (checked) Palette.accent else Palette.card2, label = "track")
    val knob = if (checked) Palette.onAccent else Palette.faint
    val x by animateDpAsState(if (checked) 21.dp else 3.dp, spring(dampingRatio = 0.6f, stiffness = 700f), label = "knob")
    Box(
        Modifier.padding(start = 10.dp).size(46.dp, 28.dp).graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(CircleShape).background(track)
            .clickable(enabled = enabled) { onChange(!checked) },
    ) {
        Box(Modifier.offset(x = x, y = 3.dp).size(22.dp).clip(CircleShape).background(knob))
    }
}

/** Title + line + toggle, the whole card clickable. */
@Composable
fun ToggleCard(title: String, sub: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    OptionCard(title, sub, onClick = { onChange(!checked) }, enabled = enabled) { Toggle(checked, enabled, onChange) }
}

/** Card that opens something: title, line, and a chevron. */
@Composable
fun LinkCard(title: String, sub: String?, onClick: () -> Unit) {
    OptionCard(title, sub, onClick = onClick) {
        Icon(LineIcons.chevron, null, tint = Palette.faint, modifier = Modifier.padding(start = 10.dp).size(18.dp))
    }
}

/** Filter pill. Picked = solid (white on dark themes). */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Palette.pill else Color.Transparent, tween(200), label = "chip")
    val edge by animateColorAsState(if (selected) Palette.pill else Palette.line, tween(200), label = "chipEdge")
    val fg by animateColorAsState(if (selected) Palette.onPill else Palette.text, tween(200), label = "chipText")
    Box(
        Modifier.height(36.dp).pressScale().clip(CircleShape)
            .background(bg)
            .border(1.5.dp, edge, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
    }
}

/** Shrinks a little while pressed and springs back. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource? = null): Modifier {
    val src = source ?: remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.5f, stiffness = 900f), label = "press")
    return this.scale(s)
}

/** Pill button: filled with the accent, or outlined. Shows a spinner while [working]. */
@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true,
    working: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = CircleShape
    val src = remember { MutableInteractionSource() }
    val fill = if (danger) Palette.danger else Palette.accent
    val on = enabled && !working
    Box(
        modifier.height(50.dp).pressScale(src).clip(shape)
            .background(if (filled && enabled) fill else Color.Transparent)
            .border(2.dp, if (filled && enabled) fill else if (enabled) Palette.text else Palette.line, shape)
            .clickable(interactionSource = src, indication = ripple(), enabled = on, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        val fg = when { !enabled -> Palette.faint; filled && danger -> Color.White; filled -> Palette.onAccent; else -> Palette.text }
        if (working) {
            CircularProgressIndicator(Modifier.size(20.dp), color = fg, strokeWidth = 2.dp)
        } else {
            Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = fg, maxLines = 1)
        }
    }
}

/** A quiet text-only action ("Not now", "Use fingerprint"). */
@Composable
fun TextAction(text: String, enabled: Boolean = true, color: Color = Palette.accentText, onClick: () -> Unit) {
    Text(
        text, color = if (enabled) color else Palette.faint, fontWeight = FontWeight.Bold, fontSize = 14.sp,
        modifier = Modifier.clip(CircleShape).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/**
 * Text field as a rounded block: the placeholder sits inside, a problem shows underneath
 * in the danger colour, the edge lights up in the accent while typing.
 */
@Composable
fun InputField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    enabled: Boolean = true,
    problem: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    onAction: (() -> Unit)? = null,
    fill: Color = Palette.panel,
    trailing: @Composable (() -> Unit)? = null,
) {
    val edge = if (problem != null) Palette.danger else Palette.line
    Column(modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            enabled = enabled,
            textStyle = TextStyle(color = Palette.text, fontFamily = Body, fontSize = 16.sp),
            cursorBrush = SolidColor(Palette.accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
                imeAction = imeAction, autoCorrectEnabled = false,
            ),
            // Next keeps its default (move to the next field) unless an action is given.
            keyboardActions = if (onAction == null) KeyboardActions.Default else KeyboardActions(
                onDone = { onAction() }, onGo = { onAction() }, onNext = { onAction() },
                onSend = { onAction() }, onSearch = { onAction() },
            ),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Row(
                    Modifier.height(56.dp).clip(RoundedCornerShape(16.dp)).background(fill)
                        .border(1.5.dp, edge, RoundedCornerShape(16.dp)).padding(start = 18.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) Text(placeholder, color = Palette.faint, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                    if (trailing != null) trailing() else Spacer(Modifier.width(12.dp))
                }
            },
        )
        if (problem != null) {
            Text(problem, color = Palette.danger, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
        }
    }
}

/** Small round button with an icon. */
@Composable
fun IconPill(icon: ImageVector, description: String, enabled: Boolean = true, tint: Color = Palette.text, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier.size(44.dp).pressScale(src).clip(CircleShape).border(1.5.dp, Palette.line, CircleShape)
            .clickable(interactionSource = src, indication = ripple(), enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = if (enabled) tint else Palette.faint, modifier = Modifier.size(19.dp)) }
}

/** Wordmark: "Sonder" in the text colour, "Vault" in the accent. */
@Composable
fun Wordmark(size: Int = 19) {
    Row {
        Text("Sonder", style = display(size), color = Palette.text)
        Text("Vault", style = display(size), color = Palette.accentText)
    }
}

/**
 * The swoosh between the page and the section below: a thin accent blade over the curved
 * top edge of [below]. The blade sweeps in from the right.
 */
@Composable
fun Swoosh(blade: Color, below: Color, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(700)) }
    Canvas(modifier.fillMaxWidth().height(42.dp)) {
        val w = size.width
        val h = size.height
        val sx = w / 360f
        val sy = h / 46f
        fun o(x: Float, y: Float) = Offset(x * sx, y * sy)
        val bladePath = Path().apply {
            moveTo(o(0f, 46f).x, o(0f, 46f).y)
            quadraticTo(o(130f, 4f).x, o(130f, 4f).y, o(360f, 12f).x, o(360f, 12f).y)
            lineTo(o(360f, 20f).x, o(360f, 20f).y)
            quadraticTo(o(155f, 16f).x, o(155f, 16f).y, o(0f, 46f).x, o(0f, 46f).y)
            close()
        }
        val fill = Path().apply {
            moveTo(o(0f, 46f).x, o(0f, 46f).y)
            quadraticTo(o(150f, 20f).x, o(150f, 20f).y, o(360f, 28f).x, o(360f, 28f).y)
            lineTo(w, h + 1f)
            lineTo(0f, h + 1f)
            close()
        }
        clipRect(left = w * (1f - progress.value)) { drawPath(bladePath, blade) }
        drawPath(fill, below)
    }
}

/** Slides up and fades in once, [order] steps after the screen appears. */
@Composable
fun Modifier.enter(order: Int = 0): Modifier {
    val e = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(60L * order)
        e.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 240f))
    }
    return this.graphicsLayer { translationY = (1f - e.value) * 28.dp.toPx(); alpha = e.value.coerceIn(0f, 1f) }
}

/** Line icons drawn from SVG paths (24×24), the same set SonderShield uses. */
object LineIcons {
    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (p in paths) {
            b.addPath(
                addPathNodes(p), stroke = SolidColor(Color.White), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    val back = icon("back", "M15 6l-6 6 6 6")
    val close = icon("close", "M6 6l12 12", "M18 6L6 18")
    val plus = icon("plus", "M12 5v14", "M5 12h14")
    val check = icon("check", "M5 12.5l4.5 4.5L19 7")
    val chevron = icon("chevron", "M9 6l6 6-6 6")
    val trash = icon("trash", "M4 7h16", "M9 7V4h6v3", "M6 7l1 13h10l1-13")
    val share = icon("share", "M12 15V4", "M8 8l4-4 4 4", "M5 13v6h14v-6")
    val lock = icon("lock", "M6 11h12v9H6z", "M8.5 11V8a3.5 3.5 0 0 1 7 0v3")
    val filter = icon("filter", "M4 6h16", "M7 12h10", "M10 18h4")
    val settings = icon(
        "settings",
        "M19.26 9.75L21.86 10.31L21.86 13.69L19.26 14.25L18.72 15.55L20.17 17.77L17.77 20.17L15.55 18.72L14.25 19.26L13.69 21.86L10.31 21.86L9.75 19.26L8.45 18.72L6.23 20.17L3.83 17.77L5.28 15.55L4.74 14.25L2.14 13.69L2.14 10.31L4.74 9.75L5.28 8.45L3.83 6.23L6.23 3.83L8.45 5.28L9.75 4.74L10.31 2.14L13.69 2.14L14.25 4.74L15.55 5.28L17.77 3.83L20.17 6.23L18.72 8.45Z",
        "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
    )
    val putBack = icon("putBack", "M12 4v11", "M8 11l4 4 4-4", "M5 20h14")
    val photo = icon("photo", "M4 5h16v14H4z", "M4 16l5-5 4 4 2-2 5 5", "M15.5 9h.01")
    val video = icon("video", "M4 6h11v12H4z", "M15 10l5-3v10l-5-3")
    val file = icon("file", "M6 3h8l4 4v14H6z", "M14 3v4h4")
    val inbox = icon("inbox", "M4 13l3-8h10l3 8v6H4z", "M4 13h5l1 2h4l1-2h5")
    val fingerprint = icon(
        "fingerprint",
        "M7 10a5 5 0 0 1 10 0v2", "M12 10v4c0 2-1 4-2.5 5.5", "M9.5 10.5V13c0 1.5-.5 3-1.5 4",
        "M14.5 12c0 3-.6 5.3-1.8 7.5", "M17 14.5c-.2 1.6-.6 3-1.2 4.4",
    )
    val scan = icon("scan", "M4 8V4h4", "M16 4h4v4", "M20 16v4h-4", "M8 20H4v-4", "M4 12h16")
}
