package kr.local.settlement

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettlementTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFF80D5C5), onPrimary = Color(0xFF00382F), primaryContainer = Color(0xFF164D45),
        background = Color(0xFF101922), surface = Color(0xFF1A2531), surfaceVariant = Color(0xFF263746),
        onSurface = Color(0xFFE7EFF7), onSurfaceVariant = Color(0xFFB7C5D1), outline = Color(0xFF526575)
    ) else lightColorScheme(
        primary = Color(0xFF087F72), onPrimary = Color.White, primaryContainer = Color(0xFFDDF3ED),
        onPrimaryContainer = Color(0xFF07584F), secondary = Color(0xFF425D79),
        background = Color(0xFFF4F7FA), surface = Color.White, surfaceVariant = Color(0xFFEAF0F5),
        onSurface = Color(0xFF1C3045), onSurfaceVariant = Color(0xFF65778B), outline = Color(0xFFC7D2DF)
    )
    MaterialTheme(colorScheme = colors, shapes = Shapes(
        small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp)
    ), content = content)
}

@Composable fun PageHeading(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun SectionCard(
    title: String, subtitle: String? = null, icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (icon != null) Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(icon, null, Modifier.padding(9.dp).size(21.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

@Composable fun StatePill(text: String, warning: Boolean = false, neutral: Boolean = false) {
    val bg = when { warning -> MaterialTheme.colorScheme.errorContainer; neutral -> MaterialTheme.colorScheme.surfaceVariant; else -> MaterialTheme.colorScheme.primaryContainer }
    val fg = when { warning -> MaterialTheme.colorScheme.onErrorContainer; neutral -> MaterialTheme.colorScheme.onSurfaceVariant; else -> MaterialTheme.colorScheme.onPrimaryContainer }
    Surface(shape = RoundedCornerShape(8.dp), color = bg) {
        Text(text, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = fg,
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable fun Hint(text: String, warning: Boolean = false) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        color = if (warning) MaterialTheme.colorScheme.errorContainer.copy(alpha = .65f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(if (warning) Icons.Outlined.Warning else Icons.Outlined.Info, null, Modifier.size(18.dp),
                tint = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable fun EmptyState(title: String, description: String, icon: ImageVector = Icons.Outlined.List) {
    Column(Modifier.fillMaxWidth().padding(vertical = 35.dp, horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, null, Modifier.padding(22.dp).size(38.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable fun PrimaryAction(text: String, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = enabled, shape = RoundedCornerShape(14.dp)) {
        if (icon != null) { Icon(icon, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable fun FileOverview(docs: List<Doc>, policy: Policy?) {
    val selected = docs.filter { it.included }
    Surface(shape = RoundedCornerShape(23.dp), color = Color.Transparent) {
        Column(Modifier.background(Brush.linearGradient(listOf(Color(0xFF193C50), Color(0xFF0A7369)))).padding(21.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("전송할 자료", color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.Email, null, tint = Color.White.copy(alpha = .8f), modifier = Modifier.size(23.dp))
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${selected.size}", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Text("개 선택", color = Color.White.copy(alpha = .9f), modifier = Modifier.padding(bottom = 6.dp))
                Spacer(Modifier.weight(1f))
                Text(sizeText(selected.sumOf { it.size }), color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
            }
            HorizontalDivider(color = Color.White.copy(alpha = .2f))
            val attention = if (policy == null) 0 else selected.count { policy.problem(it) != null }
            Text("전체 ${docs.size}개  ·  제외 ${docs.size - selected.size}개  ·  확인 필요 ${attention}개",
                color = Color.White.copy(alpha = .85f), style = MaterialTheme.typography.bodySmall)
        }
    }
}
