package app.lockinalarm.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp

/** One shape of a mascot, in its 200 x 240 viewBox (see design/gen_mascots.py). */
internal class P(
    val d: String,
    val fill: Long? = null,
    val stroke: Long? = null,
    val w: Float = 0f,
    val alpha: Float = 1f,
    val dash: FloatArray? = null,
    val evenOdd: Boolean = false,
    val rot: Float = 0f,
    val px: Float = 0f,
    val py: Float = 0f,
    val eye: Boolean = false,
) {
    val path: Path by lazy { PathParser().parsePathString(d).toPath().also { if (evenOdd) it.fillType = PathFillType.EvenOdd } }
    val center: Offset by lazy { path.getBounds().center }
}

enum class Mood { IDLE, ACTIVE }

private fun pose(mood: Mood): List<P> = if (mood == Mood.ACTIVE) MascotArt.keyAlert else MascotArt.keyIdle

/**
 * The key character. It bobs, blinks, and pops in again whenever [pop] changes.
 * [width] is the drawn width; the height is width * 1.2 (the 200 x 240 viewBox).
 */
@Composable
fun Mascot(mood: Mood, width: Dp, modifier: Modifier = Modifier, pop: Any? = null, still: Boolean = false, label: String? = null) {
    val shapes = pose(mood)
    val inf = rememberInfiniteTransition(label = "mascot")
    val bob by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "bob")
    val blink by inf.animateFloat(1f, 1f, infiniteRepeatable(keyframes {
        durationMillis = 4200
        1f at 3600; 0.12f at 3680; 1f at 3800
    }), label = "blink")
    val popScale = remember { Animatable(1f) }
    LaunchedEffect(pop, mood) {
        if (pop != null) { popScale.snapTo(0.82f); popScale.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow)) }
    }
    Canvas(modifier.size(width, width * 1.2f).semantics { contentDescription = label ?: "key mascot" }) {
        val k = size.width / 200f
        val dy = if (still) 0f else (kotlin.math.sin(bob * 2 * Math.PI).toFloat() * 2.5f)
        // viewBox units from the top-left, then the pop-in grows from the feet
        scale(k, k, pivot = Offset.Zero) { scale(popScale.value, popScale.value, pivot = Offset(100f, 236f)) {
            translate(top = dy) {
                for (p in shapes) {
                    val draw = {
                        if (p.fill != null) drawPath(p.path, Color(p.fill), alpha = p.alpha)
                        if (p.stroke != null) drawPath(
                            p.path, Color(p.stroke), alpha = p.alpha,
                            style = Stroke(width = p.w, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = p.dash?.let { PathEffect.dashPathEffect(it) }),
                        )
                    }
                    val eyed = {
                        if (p.eye && !still && blink < 0.99f) scale(1f, blink, pivot = p.center) { draw() } else draw()
                    }
                    if (p.rot != 0f) rotate(p.rot, pivot = Offset(p.px, p.py)) { eyed() } else eyed()
                }
            }
        } }
    }
}
