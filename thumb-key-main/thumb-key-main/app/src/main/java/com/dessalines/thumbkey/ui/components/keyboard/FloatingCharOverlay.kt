package com.dessalines.thumbkey.ui.components.keyboard

import android.content.Context
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.isActive
import kotlin.math.sqrt
import kotlin.random.Random

/** Represents one quadrant of a fractured character, with physics state. */
data class CharFragment(
    val initialOffsetX: Float,
    val initialOffsetY: Float,
    val velocityX: Float,
    val velocityY: Float,
    val rotationSpeed: Float,
    val clipRect: Rect, // Proportional 0..1 rect for quadrant
) {
    var currentX by mutableFloatStateOf(initialOffsetX)
    var currentY by mutableFloatStateOf(initialOffsetY)
    var rotation by mutableFloatStateOf(0f)
    var velX by mutableFloatStateOf(velocityX)
    var velY by mutableFloatStateOf(velocityY)
}

/** A deleted character that fractures into 4 pieces and falls with physics. */
class FracturingChar(
    val id: Long,
    val text: String,
    val startX: Float,
    val startY: Float,
    val style: TextStyle,
    val chaosMultiplier: Float = 1f, // Scale up velocities for multi-char word deletions
) {
    val fragments: List<CharFragment> = listOf(
        createFragment(0f, 0f, 0.5f, 0.5f), // Top-Left
        createFragment(0.5f, 0f, 1f, 0.5f), // Top-Right
        createFragment(0f, 0.5f, 0.5f, 1f), // Bottom-Left
        createFragment(0.5f, 0.5f, 1f, 1f), // Bottom-Right
    )

    var alpha by mutableFloatStateOf(1f)
    var isDead by mutableStateOf(false)

    private fun createFragment(leftPct: Float, topPct: Float, rightPct: Float, bottomPct: Float): CharFragment {
        val v = chaosMultiplier
        return CharFragment(
            initialOffsetX = startX,
            initialOffsetY = startY,
            velocityX = (Random.nextFloat() * 80f - 40f) * v,
            velocityY = (Random.nextFloat() * -50f - 80f) * v, // Initial upward burst
            rotationSpeed = (Random.nextFloat() * 360f - 180f) * v,
            clipRect = Rect(leftPct, topPct, rightPct, bottomPct),
        )
    }
}

class FloatingChar(
    val id: Long,
    val text: String,
    val startX: Float,
    val startY: Float,
    val initVelX: Float = 0f,
    val initVelY: Float = 0f,
    val delayMs: Long = 0,
) {
    var posX by mutableFloatStateOf(startX)
    var posY by mutableFloatStateOf(startY)
    var alpha by mutableFloatStateOf(1f)
    var rotation by mutableFloatStateOf(0f)
    var scale by mutableFloatStateOf(1f)
    var active by mutableStateOf(true)
}

@Composable
fun rememberAccelerometerSensor(enabled: Boolean): androidx.compose.runtime.State<Offset> {
    val context = LocalContext.current
    val sensorState = remember { mutableStateOf(Offset(0f, 9.81f)) }

    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose {}

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
                    sensorState.value = Offset(-event.values[0], event.values[1])
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sensorManager.unregisterListener(listener) }
    }

    return sensorState
}

@Composable
fun FloatingCharOverlay(
    floatingChars: SnapshotStateList<FloatingChar>,
    fracturingChars: SnapshotStateList<FracturingChar>,
    onFractureComplete: (Long) -> Unit,
    realisticGravityEnabled: Boolean = false,
    animationSpeed: Int,
    cursorScreenX: Float,
    cursorScreenY: Float,
    maxSpeed: Float,
    steerAccel: Float,
    velocityDamping: Float,
    dragVelScale: Float,
    maxTime: Float,
) {
    var overlayTopLeft by remember { mutableStateOf(Offset.Zero) }
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    val textMeasurer = rememberTextMeasurer()

    val configuration = LocalContext.current.resources.configuration
    val isSystemDarkMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val charColor = if (isSystemDarkMode) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val shadowColor = if (isSystemDarkMode) Color.Black else Color.White
    val fractureColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

    val overlayWidth = overlaySize.width.toFloat()
    val overlayHeight = overlaySize.height.toFloat()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                overlayTopLeft = coordinates.positionOnScreen()
                overlaySize = coordinates.size
            },
    ) {
        FractureOverlay(
            fracturingChars = fracturingChars,
            overlayTopLeft = overlayTopLeft,
            overlayWidth = overlayWidth,
            overlayHeight = overlayHeight,
            textMeasurer = textMeasurer,
            color = fractureColor,
            shadowColor = shadowColor,
            onCharAnimationFinished = onFractureComplete,
            realisticGravityEnabled = realisticGravityEnabled,
        )

        floatingChars.toList().forEach { fc ->
            key(fc.id) {
                FloatingCharItem(
                    fc = fc,
                    overlayTopLeft = overlayTopLeft,
                    overlayWidth = overlayWidth,
                    overlayHeight = overlayHeight,
                    cursorScreenX = cursorScreenX,
                    cursorScreenY = cursorScreenY,
                    maxSpeed = maxSpeed,
                    steerAccel = steerAccel,
                    velocityDamping = velocityDamping,
                    dragVelScale = dragVelScale,
                    maxTime = maxTime,
                    charColor = charColor,
                    shadowColor = shadowColor,
                    onComplete = { floatingChars.removeAll { it.id == fc.id } },
                )
            }
        }
    }
}

private const val GRAVITY = 2400f

@Composable
private fun FractureOverlay(
    fracturingChars: SnapshotStateList<FracturingChar>,
    overlayTopLeft: Offset,
    overlayWidth: Float,
    overlayHeight: Float,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    color: Color,
    shadowColor: Color,
    onCharAnimationFinished: (Long) -> Unit,
    realisticGravityEnabled: Boolean = false,
) {
    val hardwareGravity by rememberAccelerometerSensor(enabled = realisticGravityEnabled)
    val hasChars by remember { derivedStateOf { fracturingChars.isNotEmpty() } }

    LaunchedEffect(hasChars, realisticGravityEnabled) {
        if (!hasChars) return@LaunchedEffect
        var lastFrameTime = 0L
        while (isActive && fracturingChars.isNotEmpty()) {
            withFrameNanos { frameTime ->
                if (lastFrameTime == 0L) {
                    lastFrameTime = frameTime
                    return@withFrameNanos
                }
                val dt = ((frameTime - lastFrameTime) / 1_000_000_000f).coerceAtMost(0.05f)
                lastFrameTime = frameTime

                val gravityX = if (realisticGravityEnabled) hardwareGravity.x * 150f else 0f
                val gravityY = if (realisticGravityEnabled) hardwareGravity.y * 150f else GRAVITY

                val bounceLeft = overlayTopLeft.x
                val bounceRight = overlayTopLeft.x + overlayWidth
                val bounceTop = overlayTopLeft.y
                val bounceBottom = overlayTopLeft.y + overlayHeight
                val bounceDamping = 0.6f

                val deadIds = mutableListOf<Long>()
                for (char in fracturingChars.toList()) {
                    if (char.isDead) continue

                    for (frag in char.fragments) {
                        frag.velX += gravityX * dt
                        frag.velY += gravityY * dt
                        frag.currentX += frag.velX * dt
                        frag.currentY += frag.velY * dt
                        frag.rotation += frag.rotationSpeed * dt

                        if (frag.currentX < bounceLeft) {
                            frag.currentX = bounceLeft
                            frag.velX = -frag.velX * bounceDamping
                        }
                        if (frag.currentX > bounceRight) {
                            frag.currentX = bounceRight
                            frag.velX = -frag.velX * bounceDamping
                        }
                        if (frag.currentY < bounceTop) {
                            frag.currentY = bounceTop
                            frag.velY = -frag.velY * bounceDamping
                        }
                        if (frag.currentY > bounceBottom) {
                            frag.currentY = bounceBottom
                            frag.velY = -frag.velY * bounceDamping
                        }
                    }

                    char.alpha -= 0.8f * dt
                    if (char.alpha <= 0f) {
                        char.isDead = true
                        deadIds.add(char.id)
                    }
                }
                for (id in deadIds) onCharAnimationFinished(id)
            }
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val shadowOffset = 2f
        for (char in fracturingChars) {
            if (char.isDead) continue

            val shadowLayoutResult = textMeasurer.measure(
                text = char.text,
                style = char.style.copy(color = shadowColor.copy(alpha = char.alpha * 0.8f)),
            )
            val textLayoutResult = textMeasurer.measure(
                text = char.text,
                style = char.style.copy(color = color.copy(alpha = char.alpha)),
            )
            val width = textLayoutResult.size.width.toFloat()
            val height = textLayoutResult.size.height.toFloat()

            for (frag in char.fragments) {
                val clipRectPx = Rect(
                    left = frag.clipRect.left * width,
                    top = frag.clipRect.top * height,
                    right = frag.clipRect.right * width,
                    bottom = frag.clipRect.bottom * height,
                )
                val clipPath = Path().apply { addRect(clipRectPx) }
                val pivot = Offset(
                    clipRectPx.center.x,
                    clipRectPx.center.y,
                )

                val localX = frag.currentX - overlayTopLeft.x
                val localY = frag.currentY - overlayTopLeft.y

                translate(left = localX + shadowOffset, top = localY + shadowOffset) {
                    rotate(degrees = frag.rotation, pivot = pivot) {
                        clipPath(clipPath) {
                            drawText(shadowLayoutResult)
                        }
                    }
                }
                translate(left = localX, top = localY) {
                    rotate(degrees = frag.rotation, pivot = pivot) {
                        clipPath(clipPath) {
                            drawText(textLayoutResult)
                        }
                    }
                }
            }
        }
    }
}

private const val ARRIVE_RADIUS = 22f

@Composable
private fun FloatingCharItem(
    fc: FloatingChar,
    overlayTopLeft: Offset,
    overlayWidth: Float,
    overlayHeight: Float,
    cursorScreenX: Float,
    cursorScreenY: Float,
    maxSpeed: Float,
    steerAccel: Float,
    velocityDamping: Float,
    dragVelScale: Float,
    maxTime: Float,
    charColor: Color,
    shadowColor: Color,
    onComplete: () -> Unit,
) {
    val latestCursorX by rememberUpdatedState(cursorScreenX)
    val latestCursorY by rememberUpdatedState(cursorScreenY)

    LaunchedEffect(fc.id) {
        if (fc.delayMs > 0) {
            kotlinx.coroutines.delay(fc.delayMs)
        }

        val initTargetX = if (!latestCursorX.isNaN()) latestCursorX else fc.startX
        val initTargetY = if (!latestCursorY.isNaN()) latestCursorY else fc.startY - 400f

        val dx0 = initTargetX - fc.startX
        val dy0 = initTargetY - fc.startY
        val dist0 = sqrt(dx0 * dx0 + dy0 * dy0).coerceAtLeast(1f)

        val bounceLeft = overlayTopLeft.x
        val bounceRight = overlayTopLeft.x + overlayWidth
        val bounceTop = overlayTopLeft.y
        val bounceBottom = overlayTopLeft.y + overlayHeight
        val bounceDamping = 0.6f

        var velX = fc.initVelX
        var velY = fc.initVelY
        if (velX == 0f && velY == 0f) {
            val launchSpeed = (dist0 * 5f).coerceIn(600f, maxSpeed)
            velX = dx0 / dist0 * launchSpeed
            velY = dy0 / dist0 * launchSpeed
        } else {
            velX *= dragVelScale
            velY *= dragVelScale
            val speed = sqrt(velX * velX + velY * velY)
            if (speed > maxSpeed) {
                velX = velX / speed * maxSpeed
                velY = velY / speed * maxSpeed
            }
        }

        var prevNanos = 0L
        var totalTime = 0f

        while (isActive && fc.active) {
            val nanos = withFrameNanos { it }
            if (prevNanos == 0L) {
                prevNanos = nanos
                continue
            }
            val dt = ((nanos - prevNanos) / 1_000_000_000f).coerceAtMost(0.05f)
            prevNanos = nanos
            totalTime += dt

            val targetX = if (!latestCursorX.isNaN()) latestCursorX else fc.startX
            val targetY = if (!latestCursorY.isNaN()) latestCursorY else fc.startY - 400f

            val toX = targetX - fc.posX
            val toY = targetY - fc.posY
            val dist = sqrt(toX * toX + toY * toY)

            if (dist < ARRIVE_RADIUS || totalTime >= maxTime) {
                fc.alpha = 0f
                fc.active = false
                break
            }

            val steerX = toX / dist
            val steerY = toY / dist
            velX += steerX * steerAccel * dt
            velY += steerY * steerAccel * dt

            val dampFactor = 1f - velocityDamping * dt
            velX *= dampFactor
            velY *= dampFactor

            val distanceCap = (dist * 4f).coerceAtMost(maxSpeed)
            val speed = sqrt(velX * velX + velY * velY)
            if (speed > distanceCap) {
                val scale = distanceCap / speed
                velX *= scale
                velY *= scale
            }

            fc.posX += velX * dt
            fc.posY += velY * dt

            if (fc.posX < bounceLeft) { fc.posX = bounceLeft; velX = -velX * bounceDamping }
            if (fc.posX > bounceRight) { fc.posX = bounceRight; velX = -velX * bounceDamping }
            if (fc.posY < bounceTop) { fc.posY = bounceTop; velY = -velY * bounceDamping }
            if (fc.posY > bounceBottom) { fc.posY = bounceBottom; velY = -velY * bounceDamping }

            fc.alpha = (dist / (dist0 * 0.3f)).coerceIn(0f, 1f)
        }
        onComplete()
    }

    Text(
        text = fc.text,
        style = TextStyle(
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = charColor,
            shadow = Shadow(
                color = shadowColor,
                offset = Offset(1f, 1f),
                blurRadius = 6f,
            ),
        ),
        modifier = Modifier
            .offset { IntOffset((fc.posX - overlayTopLeft.x).toInt(), (fc.posY - overlayTopLeft.y).toInt()) }
            .graphicsLayer(
                alpha = fc.alpha,
                rotationZ = fc.rotation,
                scaleX = fc.scale,
                scaleY = fc.scale
            ),
    )
}
