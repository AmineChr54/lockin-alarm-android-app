package app.lockinalarm.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import app.lockinalarm.R

/** Charcoal, one card grey, a few accents, serif + Inter. */
object C {
    val bg = Color(0xFF1C1D20)
    val bgDeep = Color(0xFF141518)
    val card = Color(0xFF26272B)
    val cardHi = Color(0xFF2C2D31)
    val line = Color(0xFF34353A)
    val lineHi = Color(0xFF44454B)
    val text = Color(0xFFF3F1EC)
    val muted = Color(0xFFA3A4AA)
    val faint = Color(0xFF6E6F75)
    val ink = Color(0xFF17181B)
    val coral = Color(0xFFF26B4F)
    val green = Color(0xFF3E5E4E)
    val blue = Color(0xFF2D5A94)
    val pink = Color(0xFFE89BCB)
    val ok = Color(0xFF6FBF8E)
    val bad = Color(0xFFFF8A80)
}

val Serif = FontFamily(
    Font(R.font.instrument_serif, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun inter(w: Int) = Font(R.font.inter, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

/** "Time to *lock in*?" -> the starred part in italic, for the serif headlines. */
fun starred(s: String): AnnotatedString = buildAnnotatedString {
    var italic = false
    for (part in s.split('*')) {
        if (italic) withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(part) } else append(part)
        italic = !italic
    }
}

object T {
    fun serif(size: Int) = TextStyle(fontFamily = Serif, fontSize = size.sp, lineHeight = (size * 1.06).sp, color = C.text)
    fun sans(size: Int, weight: Int = 400) = TextStyle(fontFamily = Inter, fontSize = size.sp, fontWeight = FontWeight(weight), lineHeight = (size * 1.4).sp, color = C.text)
    val label = TextStyle(fontFamily = Inter, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp)
}

@Composable
fun LockInTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.coral, onPrimary = C.ink,
            secondary = C.green, onSecondary = C.text,
            background = C.bg, onBackground = C.text,
            surface = C.bg, onSurface = C.text,
            surfaceVariant = C.card, onSurfaceVariant = C.muted,
            surfaceContainerHigh = C.cardHi, surfaceContainer = C.card,
            outline = C.lineHi, error = C.bad,
        ),
        typography = Typography(
            bodyLarge = T.sans(16), bodyMedium = T.sans(14), bodySmall = T.sans(12),
            labelLarge = T.sans(14, 500), titleLarge = T.serif(24), headlineSmall = T.serif(28),
        ),
        content = content,
    )
}
