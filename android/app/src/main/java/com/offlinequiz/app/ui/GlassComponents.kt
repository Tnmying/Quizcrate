package com.offlinequiz.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.offlinequiz.app.LocalDarkTheme

object AppIcons {
    private fun icon(name: String, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
        }.build()
    val Sequential = icon("Sequential") { moveTo(8f,5f); lineTo(22f,5f); moveTo(8f,12f); lineTo(22f,12f); moveTo(8f,19f); lineTo(22f,19f); moveTo(2f,4f); lineTo(3f,3f); lineTo(3f,7f); moveTo(2f,11f); lineTo(4f,11f); lineTo(2f,14f); lineTo(4f,14f); moveTo(2f,18f); lineTo(4f,18f); lineTo(3f,20f); lineTo(4f,21f); lineTo(2f,22f) }
    val Random = icon("Random") { moveTo(2f,5f); lineTo(6f,5f); lineTo(17f,19f); lineTo(22f,19f); moveTo(18f,15f); lineTo(22f,19f); lineTo(18f,23f); moveTo(2f,19f); lineTo(6f,19f); lineTo(10f,14f); moveTo(14f,9f); lineTo(17f,5f); lineTo(22f,5f); moveTo(18f,1f); lineTo(22f,5f); lineTo(18f,9f) }
    val Manage = icon("Manage") { moveTo(14f,21f); lineTo(3f,21f); lineTo(3f,3f); lineTo(17f,3f); lineTo(17f,8f); moveTo(6f,7f); lineTo(13f,7f); moveTo(6f,11f); lineTo(11f,11f); moveTo(12f,19f); lineTo(13f,15f); lineTo(20f,8f); lineTo(23f,11f); lineTo(16f,18f); close() }
    val History = icon("History") { moveTo(3f,10f); curveTo(3f,-1f,22f,-1f,22f,12f); curveTo(22f,23f,6f,25f,3f,16f); moveTo(1f,6f); lineTo(3f,10f); lineTo(7f,8f); moveTo(12f,6f); lineTo(12f,12f); lineTo(17f,15f) }
    val Filter = icon("Filter") { moveTo(2f,4f); lineTo(22f,4f); lineTo(14f,13f); lineTo(14f,21f); lineTo(10f,19f); lineTo(10f,13f); close() }
    val Search = icon("Search") {
        moveTo(17f, 10f)
        curveTo(17f, 1f, 3f, 1f, 3f, 10f)
        curveTo(3f, 19f, 17f, 19f, 17f, 10f)
        moveTo(15f, 15f); lineTo(21f, 21f)
    }
    val Close = icon("Close") {
        moveTo(6f, 6f); lineTo(18f, 18f)
        moveTo(18f, 6f); lineTo(6f, 18f)
    }
    val Locate = icon("Locate") { moveTo(12f,2f); lineTo(12f,6f); moveTo(12f,18f); lineTo(12f,22f); moveTo(2f,12f); lineTo(6f,12f); moveTo(18f,12f); lineTo(22f,12f); moveTo(12f,5f); curveTo(3f,5f,3f,19f,12f,19f); curveTo(21f,19f,21f,5f,12f,5f) }
    val Document = icon("Document") { moveTo(14f,2f); lineTo(4f,2f); lineTo(4f,22f); lineTo(20f,22f); lineTo(20f,8f); close(); moveTo(14f,2f); lineTo(14f,8f); lineTo(20f,8f); moveTo(8f,12f); lineTo(16f,12f); moveTo(8f,17f); lineTo(16f,17f) }
    val Book = icon("Book") { moveTo(12f,5f); curveTo(8f,2f,4f,3f,2f,4f); lineTo(2f,20f); curveTo(6f,18f,9f,19f,12f,21f); curveTo(15f,19f,18f,18f,22f,20f); lineTo(22f,4f); curveTo(18f,2f,15f,3f,12f,5f); lineTo(12f,21f) }
    val Review = icon("Review") {
        moveTo(17f, 9f); lineTo(17f, 3f); lineTo(4f, 3f); lineTo(4f, 21f); lineTo(11f, 21f)
        moveTo(7f, 7f); lineTo(14f, 7f)
        moveTo(7f, 11f); lineTo(11f, 11f)
        moveTo(21f, 15f)
        curveTo(19f, 11f, 12f, 12f, 12f, 17f)
        curveTo(12f, 22f, 19f, 24f, 22f, 19f)
        moveTo(21f, 12f); lineTo(21f, 16f); lineTo(17f, 16f)
    }
    val Reset = icon("Reset") {
        moveTo(20f, 8f)
        curveTo(18f, 4f, 13f, 2f, 9f, 4f)
        curveTo(4f, 6f, 2f, 11f, 4f, 16f)
        curveTo(7f, 23f, 17f, 23f, 21f, 16f)
        moveTo(20f, 3f); lineTo(20f, 9f); lineTo(14f, 9f)
    }
    val Person = icon("Person") { moveTo(16f,7f); curveTo(16f,12f,8f,12f,8f,7f); curveTo(8f,2f,16f,2f,16f,7f); moveTo(3f,21f); curveTo(3f,10f,21f,10f,21f,21f); close() }
    val Appearance = icon("Appearance") {
        moveTo(20f, 15f)
        curveTo(12f, 17f, 7f, 11f, 9f, 3f)
        curveTo(2f, 5f, 1f, 15f, 7f, 19f)
        curveTo(12f, 23f, 18f, 21f, 20f, 15f)
    }
    val Stats = icon("Stats") { moveTo(3f,21f); lineTo(21f,21f); moveTo(5f,17f); lineTo(5f,12f); lineTo(8f,12f); lineTo(8f,17f); close(); moveTo(11f,17f); lineTo(11f,4f); lineTo(14f,4f); lineTo(14f,17f); close(); moveTo(18f,17f); lineTo(18f,8f); lineTo(21f,8f); lineTo(21f,17f); close() }
    val Backup = icon("Backup") { moveTo(3f,12f); lineTo(3f,3f); lineTo(21f,3f); lineTo(21f,12f); moveTo(3f,7f); lineTo(21f,7f); moveTo(7f,21f); lineTo(7f,12f); moveTo(4f,15f); lineTo(7f,12f); lineTo(10f,15f); moveTo(17f,12f); lineTo(17f,21f); moveTo(14f,18f); lineTo(17f,21f); lineTo(20f,18f) }
    val Info = icon("Info") { moveTo(12f,22f); curveTo(-1f,22f,-1f,2f,12f,2f); curveTo(25f,2f,25f,22f,12f,22f); moveTo(12f,11f); lineTo(12f,17f); moveTo(12f,7f); lineTo(12f,7.2f) }
    val Help = icon("Help") { moveTo(12f,22f); curveTo(-1f,22f,-1f,2f,12f,2f); curveTo(25f,2f,25f,22f,12f,22f); moveTo(9f,8f); curveTo(9f,4f,17f,5f,15f,10f); lineTo(12f,13f); moveTo(12f,17f); lineTo(12f,17.2f) }
    val Previous = icon("Previous") {
        moveTo(8f, 4f)
        lineTo(4f, 8f)
        lineTo(8f, 12f)
        moveTo(4f, 8f)
        lineTo(14f, 8f)
        curveTo(22f, 8f, 22f, 20f, 14f, 20f)
        lineTo(7f, 20f)
    }
    val Arrow = icon("Arrow") { moveTo(9f,5f); lineTo(16f,12f); lineTo(9f,19f) }
    val SkipQuestion = icon("SkipQuestion") {
        moveTo(5f, 4f); lineTo(16f, 12f); lineTo(5f, 20f); close()
        moveTo(19f, 4f); lineTo(19f, 20f)
    }
    val PeekAnswer = icon("PeekAnswer") {
        moveTo(2f, 12f)
        curveTo(7f, 3f, 17f, 3f, 22f, 12f)
        curveTo(17f, 21f, 7f, 21f, 2f, 12f)
        moveTo(15f, 12f)
        curveTo(15f, 16f, 9f, 16f, 9f, 12f)
        curveTo(9f, 8f, 15f, 8f, 15f, 12f)
    }
    val ReportQuestion = icon("ReportQuestion") {
        moveTo(10.5f, 4f)
        curveTo(11.2f, 2.7f, 12.8f, 2.7f, 13.5f, 4f)
        lineTo(22f, 18.5f)
        curveTo(22.7f, 19.7f, 21.9f, 21f, 20.5f, 21f)
        lineTo(3.5f, 21f)
        curveTo(2.1f, 21f, 1.3f, 19.7f, 2f, 18.5f)
        close()
        moveTo(12f, 9f); lineTo(12f, 13f)
        moveTo(12f, 17f); lineTo(12f, 17.1f)
    }
    val Play = icon("Play") { moveTo(7f,3f); lineTo(21f,12f); lineTo(7f,21f); close() }
    val Import = icon("Import") { moveTo(13f,21f); lineTo(4f,21f); lineTo(4f,3f); lineTo(14f,3f); lineTo(19f,8f); lineTo(19f,12f); moveTo(13f,3f); lineTo(13f,9f); lineTo(19f,9f); moveTo(18f,15f); lineTo(18f,23f); moveTo(14f,19f); lineTo(22f,19f) }
}

@Composable
internal fun Modifier.glassBackdrop(): Modifier {
    val dark = LocalDarkTheme.current
    val baseColors = if (dark) listOf(Color(0xFF101927), Color(0xFF131F31), Color(0xFF101B2C))
        else listOf(Color(0xFFE4EFFC), Color(0xFFECF4FF), Color(0xFFDCEBFA))
    val blueGlow = if (dark) Color(0xFF213A5A) else Color(0xFFB1D3F6)
    val lightGlow = if (dark) Color(0xFF242B43) else Color(0xFFFAF7EE)
    val aquaGlow = if (dark) Color(0xFF193747) else Color(0xFFBBDDEB)
    return drawWithCache {
        val base = Brush.verticalGradient(baseColors)
        val blue = Brush.radialGradient(
            listOf(blueGlow, blueGlow.copy(alpha = 0f)),
            center = Offset(size.width * .05f, size.height * .24f),
            radius = size.maxDimension * .52f
        )
        val light = Brush.radialGradient(
            listOf(lightGlow, lightGlow.copy(alpha = 0f)),
            center = Offset(size.width * .98f, size.height * .42f),
            radius = size.maxDimension * .36f
        )
        val aqua = Brush.radialGradient(
            listOf(aquaGlow, aquaGlow.copy(alpha = 0f)),
            center = Offset(size.width * .8f, size.height * .94f),
            radius = size.maxDimension * .42f
        )
        onDrawBehind {
            drawRect(base)
            drawRect(blue)
            drawRect(light)
            drawRect(aqua)
        }
    }
}

internal val GlassCardRadius = 24.dp

@Composable
internal fun Modifier.glassSurface(
    radius: Dp = GlassCardRadius,
    tint: Color = Color.Transparent,
    fill: Brush? = null
): Modifier {
    val shape = RoundedCornerShape(radius)
    val dark = LocalDarkTheme.current
    val glass = if (dark) Color(0xFF30425D) else Color.White
    val shadow = if (dark) Color.Black else Color(0xFF345C83)
    val rim = if (dark) Color(0xFF7895BB).copy(alpha = .48f) else Color.White
    return this
        .drawWithCache {
            val corner = radius.toPx().coerceAtMost(size.minDimension / 2)
            // Draw a diffuse shadow behind the shape without elevating a translucent Android layer.
            onDrawBehind {
                for (spread in 6 downTo 1) {
                    val extra = (spread * .8f).dp.toPx()
                    drawRoundRect(
                        color = shadow.copy(alpha = .012f),
                        topLeft = Offset(-extra, 2.dp.toPx() - extra),
                        size = Size(size.width + 2 * extra, size.height + 2 * extra),
                        cornerRadius = CornerRadius(corner + extra)
                    )
                }
            }
        }
        .clip(shape)
        .background(
            fill ?: Brush.verticalGradient(
                listOf(
                    tint.copy(alpha = tint.alpha * .45f).compositeOver(glass.copy(alpha = .60f)),
                    tint.compositeOver(glass.copy(alpha = .24f))
                )
            ),
            shape
        )
        .border(
            1.dp,
            Brush.linearGradient(listOf(rim, rim.copy(alpha = rim.alpha * .25f), rim.copy(alpha = rim.alpha * .65f))),
            shape
        )
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    GlassPanel {
        Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun GlassPanel(
    modifier: Modifier = Modifier,
    tint: Color = Color.Transparent,
    content: @Composable BoxScope.() -> Unit
) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(modifier.fillMaxWidth().glassSurface(tint = tint), propagateMinConstraints = true, content = content)
    }
}

@Composable
internal fun glassFieldColors(): TextFieldColors {
    val fill = if (LocalDarkTheme.current) MaterialTheme.colorScheme.surfaceContainerHighest else Color.White
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = fill.copy(alpha = .56f),
        unfocusedContainerColor = fill.copy(alpha = .34f),
        disabledContainerColor = fill.copy(alpha = .20f),
        errorContainerColor = fill.copy(alpha = .42f),
        unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = .32f)
    )
}

@Composable
internal fun GlassActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).glassSurface(18.dp),
        enabled = enabled,
        content = content
    )
}

@Composable
internal fun GlassFilterButton(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    label: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val tint = if (selected) colors.primary else colors.onSurfaceVariant
    Row(
        Modifier.padding(vertical = 5.dp).glassSurface(24.dp)
            .background(if (selected) colors.primary.copy(alpha = .12f) else Color.Transparent)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 44.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        icon?.let { Icon(it, null, Modifier.size(15.dp), tint = tint) }
        CompositionLocalProvider(LocalContentColor provides tint) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge, label)
        }
    }
}

@Composable
fun MainTabs(route: String, onSelect: (String) -> Unit) {
    val selectedFill = if (LocalDarkTheme.current) listOf(Color(0xFF304E73), Color(0xFF253D5C))
        else listOf(Color.White.copy(alpha = .72f), Color(0x66B4D8FC))
    val tabs = listOf(
        Triple(AppRouteState.Route.HOME, "学习", AppIcons.Book),
        Triple(AppRouteState.Route.WRONGBOOK_INDEX, "错题", AppIcons.Review),
        Triple(AppRouteState.Route.PROFILE, "我的", AppIcons.Person)
    )
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
            .glassSurface(32.dp).selectableGroup().padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        tabs.forEach { (id, label, icon) ->
            val selected = route == id
            val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Column(
                Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(27.dp))
                    .then(if (selected) Modifier.background(
                        Brush.verticalGradient(selectedFill)
                    ) else Modifier)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(id) })
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
            ) {
                Icon(icon, null, Modifier.size(22.dp), tint = tint)
                Text(label, color = tint, style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
            }
        }
    }
}

@Composable
fun PageHeading(title: String) {
    Text("题匣 / Quizcrate", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
fun EntryCard(title: String, detail: String, icon: ImageVector, onClick: () -> Unit) {
    GlassCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.glassSurface(14.dp)) {
                Icon(icon, null, Modifier.padding(9.dp).size(23.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(AppIcons.Arrow, null, Modifier.size(20.dp))
        }
    }
}

@Composable
fun ActionLabel(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, Modifier.size(18.dp))
        Text(label)
    }
}
