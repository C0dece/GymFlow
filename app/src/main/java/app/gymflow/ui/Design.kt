@file:OptIn(ExperimentalMaterial3Api::class)

package app.gymflow.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gymflow.R
import app.gymflow.data.Theme
import app.gymflow.domain.Load
import app.gymflow.domain.UnitWeight
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val night = darkColorScheme(
    primary = Color(0xFFEF4743), onPrimary = Color(0xFF101012),
    primaryContainer = Color(0xFF302023), onPrimaryContainer = Color(0xFFF2F2F3),
    secondary = Color(0xFFC9C9D0), onSecondary = Color(0xFF252529),
    secondaryContainer = Color(0xFF34343A), onSecondaryContainer = Color(0xFFF2F2F3),
    background = Color(0xFF101012), onBackground = Color(0xFFF2F2F3),
    surface = Color(0xFF101012), onSurface = Color(0xFFF2F2F3),
    surfaceContainer = Color(0xFF1B1B1F), surfaceContainerHigh = Color(0xFF252529),
    surfaceContainerHighest = Color(0xFF34343A), surfaceContainerLow = Color(0xFF171719),
    onSurfaceVariant = Color(0xFFA1A1AA), outline = Color(0xFF898991), outlineVariant = Color(0xFF34343A),
    error = Color(0xFFFFCD86), onError = Color(0xFF282015),
)
private val light = lightColorScheme(
    primary = Color(0xFFBB2429), onPrimary = Color.White,
    primaryContainer = Color(0xFFF7DEE0), onPrimaryContainer = Color(0xFF251C20),
    secondary = Color(0xFF53535B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E3E9), onSecondaryContainer = Color(0xFF24242A),
    background = Color(0xFFF5F4F6), onBackground = Color(0xFF18181C),
    surface = Color(0xFFF5F4F6), onSurface = Color(0xFF18181C),
    surfaceContainer = Color(0xFFEBEAEF), surfaceContainerHigh = Color(0xFFE4E3E8),
    surfaceContainerHighest = Color(0xFFD7D6DD), surfaceContainerLow = Color(0xFFEFEEF2),
    onSurfaceVariant = Color(0xFF52525B), outline = Color(0xFF777780), outlineVariant = Color(0xFFCDCCD4),
    error = Color(0xFF784800), onError = Color.White,
)
private val type = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontSize = 27.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.45).sp),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun GymTheme(theme: Theme, content: @Composable () -> Unit) {
    val dark = theme == Theme.DARK || (theme == Theme.SYSTEM && isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (dark) night else light, typography = type, content = content)
}

@Composable
fun Symbol(@DrawableRes icon: Int, modifier: Modifier = Modifier, description: String? = null) {
    Icon(painterResource(icon), contentDescription = description, modifier = modifier)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.titleLarge)
}

@Composable
fun BarTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun CardBlock(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val inner: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
    if (onClick == null) Card(modifier.fillMaxWidth(), colors = colors, content = inner)
    else Card(onClick = onClick, modifier = modifier.fillMaxWidth(), colors = colors, content = inner)
}

@Composable
fun ScreenColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdaptivePair(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    content: @Composable FlowRowScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = if (maxWidth / LocalDensity.current.fontScale < 290.dp) 1 else 2
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
            maxItemsInEachRow = columns,
            content = content,
        )
    }
}

@Composable
fun EditorSheet(title: String, close: () -> Unit, footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        val body: @Composable (Modifier) -> Unit = { modifier ->
            Column(
                modifier.verticalScroll(rememberScrollState())
                    .padding(start = 18.dp, end = 18.dp, bottom = if (footer == null) 22.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineMedium)
                content()
            }
        }
        if (footer == null) body(Modifier.fillMaxWidth().imePadding())
        else Column(Modifier.fillMaxWidth().imePadding()) {
            body(Modifier.weight(1f, fill = false).fillMaxWidth())
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
                Box(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) { footer() }
            }
        }
    }
}

@Composable
fun Field(
    value: String,
    label: String,
    change: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    error: Boolean = false,
) {
    OutlinedTextField(value, change, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), isError = error, maxLines = if (singleLine) 1 else 4)
}

@Composable
fun Marker(color: Color = MaterialTheme.colorScheme.primary) {
    Box(Modifier.size(8.dp).background(color, CircleShape))
}

@Composable
fun InlineStatus(message: String, warning: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Marker(if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Muted(message, Modifier.weight(1f, fill = false))
    }
}

@Composable
fun unitText(unit: UnitWeight) = if (unit == UnitWeight.KG) stringResource(R.string.kg) else stringResource(R.string.lb)

@Composable
fun loadText(type: Load) = when (type) {
    Load.BARBELL -> stringResource(R.string.barbell)
    Load.DUMBBELL -> stringResource(R.string.dumbbell)
    Load.MACHINE -> stringResource(R.string.machine)
    Load.BODYWEIGHT -> stringResource(R.string.bodyweight)
    Load.OTHER -> stringResource(R.string.other_load)
}

fun number(value: Double): String = value.toBigDecimal().stripTrailingZeros().toPlainString().replace('.', ',')
fun decimal(value: String): Double? = value.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
fun positiveInt(value: String): Int? = value.trim().toIntOrNull()?.takeIf { it > 0 }
fun dateOrNull(value: String): LocalDate? = runCatching { LocalDate.parse(value.trim()) }.getOrNull()
fun shortDate(value: String): String = dateOrNull(value)?.format(
    DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ru"))
) ?: value
fun fullDate(value: LocalDate): String = value.format(
    DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag("ru"))
).replaceFirstChar(Char::uppercase)
fun repRange(first: Int, last: Int) = if (first == last) "$last" else "$first-$last"
