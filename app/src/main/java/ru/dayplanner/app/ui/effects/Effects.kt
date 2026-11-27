package ru.dayplanner.app.ui.effects

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.dayplanner.app.R
import ru.dayplanner.app.ui.theme.AppStyle
import ru.dayplanner.app.ui.theme.LocalAppStyle
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// Украшения стиля «С эффектами». Всё нарисовано средствами Compose, без сторонних библиотек.
// Эффекты короткие, не перехватывают нажатия и не проигрываются, если в системе отключены анимации.

interface EffectsController {
    /** Дело отмечено выполненным; [allDone] — выполнены все дела дня. */
    fun onTaskCompleted(allDone: Boolean)
}

object NoEffects : EffectsController {
    override fun onTaskCompleted(allDone: Boolean) {}
}

val LocalEffects = staticCompositionLocalOf<EffectsController> { NoEffects }

private fun animationsEnabled(): Boolean = try {
    ValueAnimator.areAnimatorsEnabled()
} catch (e: Exception) {
    true
}

private class Celebration(val serial: Long, val text: String, val big: Boolean)

private class Particle(
    val angle: Float,
    val speed: Float,
    val size: Float,
    val color: Int,
    val spin: Float,
)

private class HostController : EffectsController {
    var enabled: Boolean = false
    var phrases: Array<String> = emptyArray()
    var finale: String = ""
    var event by mutableStateOf<Celebration?>(null)
    private var lastIndex = -1

    override fun onTaskCompleted(allDone: Boolean) {
        if (!enabled || !animationsEnabled()) return
        val text = if (allDone || phrases.isEmpty()) {
            finale
        } else {
            var index = Random.nextInt(phrases.size)
            if (index == lastIndex) index = (index + 1) % phrases.size
            lastIndex = index
            phrases[index]
        }
        event = Celebration(System.nanoTime(), text, allDone)
    }
}

private fun makeParticles(event: Celebration): List<Particle> {
    val random = Random(event.serial)
    val count = if (event.big) 90 else 44
    return List(count) {
        Particle(
            angle = random.nextFloat() * 6.2832f,
            speed = 0.35f + random.nextFloat() * 0.65f,
            size = 6f + random.nextFloat() * 5f,
            color = random.nextInt(1000),
            spin = -540f + random.nextFloat() * 1080f,
        )
    }
}

/** Оболочка экрана: в стиле «С эффектами» рисует поверх содержимого свечение, конфетти и похвалу. */
@Composable
fun EffectsHost(style: AppStyle, content: @Composable () -> Unit) {
    val controller = remember { HostController() }
    val enabled = style == AppStyle.EFFECTS
    val phrases = stringArrayResource(R.array.praise)
    val finale = stringResource(R.string.all_done)
    SideEffect {
        controller.enabled = enabled
        controller.phrases = phrases
        controller.finale = finale
        if (!enabled) controller.event = null
    }
    CompositionLocalProvider(LocalEffects provides controller) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            if (enabled) {
                TopGlow()
                CelebrationOverlay(controller)
            }
        }
    }
}

@Composable
private fun BoxScope.TopGlow() {
    val glow = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    Canvas(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(140.dp),
    ) {
        drawRect(brush = Brush.verticalGradient(colors = listOf(glow, Color.Transparent)))
    }
}

@Composable
private fun BoxScope.CelebrationOverlay(controller: HostController) {
    val event = controller.event ?: return
    val scheme = MaterialTheme.colorScheme
    val colors = remember(scheme) {
        listOf(scheme.primary, scheme.secondary, scheme.tertiary, Color(0xFFFFC94A), Color(0xFFFF6F91))
    }
    key(event.serial) {
        val progress = remember { Animatable(0f) }
        val particles = remember { makeParticles(event) }
        LaunchedEffect(Unit) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = if (event.big) 2800 else 2000, easing = LinearEasing),
            )
            if (controller.event === event) controller.event = null
        }

        // Разлёт конфетти из центра экрана.
        Canvas(modifier = Modifier.matchParentSize()) {
            val t = (progress.value / 0.62f).coerceAtMost(1f)
            if (t < 1f) {
                val origin = Offset(size.width / 2f, size.height * 0.45f)
                val reach = size.minDimension * (if (event.big) 0.85f else 0.6f)
                val eased = 1f - (1f - t) * (1f - t)
                val fall = size.height * 0.22f * t * t
                val alpha = (1f - t * t).coerceIn(0f, 1f)
                for (p in particles) {
                    val distance = reach * p.speed * eased
                    val x = origin.x + cos(p.angle) * distance
                    val y = origin.y + sin(p.angle) * distance + fall
                    val w = p.size.dp.toPx()
                    val h = w * 0.55f
                    rotate(degrees = p.spin * t, pivot = Offset(x, y)) {
                        drawRect(
                            color = colors[p.color % colors.size].copy(alpha = alpha),
                            topLeft = Offset(x - w / 2f, y - h / 2f),
                            size = Size(w, h),
                        )
                    }
                }
            }
        }

        // Крупная надпись-похвала. Нажатия сквозь неё проходят к содержимому.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 28.dp)
                .graphicsLayer {
                    val t = progress.value
                    val appear = (t / 0.12f).coerceIn(0f, 1f)
                    val vanish = ((1f - t) / 0.18f).coerceIn(0f, 1f)
                    alpha = minOf(appear, vanish)
                    val scale = 0.7f + 0.3f * appear
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(colors = listOf(scheme.primary, scheme.tertiary)))
                .padding(horizontal = 26.dp, vertical = 18.dp),
        ) {
            Text(
                text = event.text,
                color = scheme.onPrimary,
                style = if (event.big) {
                    MaterialTheme.typography.headlineMedium
                } else {
                    MaterialTheme.typography.headlineSmall
                },
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Вспышка с частицами при нажатии (только в стиле «С эффектами»).
 * Нажатие лишь наблюдается и не перехватывается: его обрабатывает сама кнопка.
 */
fun Modifier.pressBurst(): Modifier = composed {
    if (LocalAppStyle.current != AppStyle.EFFECTS) {
        Modifier
    } else {
        val color = MaterialTheme.colorScheme.primary
        var origin by remember { mutableStateOf(Offset.Zero) }
        val progress = remember { Animatable(1f) }
        val scope = rememberCoroutineScope()
        Modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (animationsEnabled()) {
                        origin = down.position
                        scope.launch {
                            progress.snapTo(0f)
                            progress.animateTo(1f, tween(durationMillis = 380))
                        }
                    }
                }
            }
            .drawWithContent {
                drawContent()
                val t = progress.value
                if (t < 1f) {
                    val ink = color.copy(alpha = (1f - t) * 0.7f)
                    val radius = (6f + 22f * t).dp.toPx()
                    drawCircle(color = ink, radius = radius, center = origin, style = Stroke(width = 1.5.dp.toPx()))
                    val dot = 2.dp.toPx()
                    val orbit = radius + 5.dp.toPx()
                    for (i in 0 until 8) {
                        val angle = i * 0.7854f
                        drawCircle(
                            color = ink,
                            radius = dot,
                            center = Offset(origin.x + cos(angle) * orbit, origin.y + sin(angle) * orbit),
                        )
                    }
                }
            }
    }
}
