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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
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
    // Running time (seconds) since spawn, used for the piecewise alpha curve.
    // Kept separate from `alpha` so the curve can hold peak intensity for a
    // while before the fade begins, instead of a pure linear decay.
    var ageSec by mutableFloatStateOf(0f)
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
    val isSpray: Boolean = false,
    val spinSpeed: Float = 0f,
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

        // Some devices (Android emulators w/o sensor HAL, unusual hardware)
        // don't expose an accelerometer at all. Skip registration rather than
        // passing a null sensor to registerListener, which is undefined.
        if (sensor == null) return@DisposableEffect onDispose {}

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
                    sensorState.value = Offset(-event.values[0], event.values[1])
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
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
    cursorXState: State<Float>,
    cursorYState: State<Float>,
    maxSpeed: Float,
    steerAccel: Float,
    velocityDamping: Float,
    dragVelScale: Float,
    maxTime: Float,
) {
    var overlayTopLeft by remember { mutableStateOf(Offset.Zero) }
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    val textMeasurer = rememberTextMeasurer()
    val overlayView = LocalView.current

    val configuration = LocalContext.current.resources.configuration
    val isSystemDarkMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val charColor = if (isSystemDarkMode) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val shadowColor = if (isSystemDarkMode) Color.Black else Color.White
    // Key-decay particles are always white, against a darker-outlined shadow so
    // they read on both light and dark backgrounds.  The alpha fade is driven
    // by `FracturingChar.alpha`; starting at 1.0 here keeps peak intensity.
    val fractureColor = Color.White
    val fractureShadowColor = Color.Black.copy(alpha = 0.55f)

    val overlayWidth = overlaySize.width.toFloat()
    val overlayHeight = overlaySize.height.toFloat()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val windowPos = coordinates.localToWindow(Offset.Zero)
                val loc = IntArray(2)
                overlayView.getLocationOnScreen(loc)
                overlayTopLeft = Offset(loc[0] + windowPos.x, loc[1] + windowPos.y)
                overlaySize = IntSize(overlayView.width, overlayView.height)
            },
    ) {
        FractureOverlay(
            fracturingChars = fracturingChars,
            overlayTopLeft = overlayTopLeft,
            overlayWidth = overlayWidth,
            overlayHeight = overlayHeight,
            textMeasurer = textMeasurer,
            color = fractureColor,
            shadowColor = fractureShadowColor,
            onCharAnimationFinished = onFractureComplete,
            realisticGravityEnabled = realisticGravityEnabled,
        )

        // Iterate via a snapshot-safe copy: when `onComplete` callbacks remove
        // items mid-recomposition (several chars arriving at the cursor within
        // the same frame), an index-based loop over the live SnapshotStateList
        // can skip children and force their LaunchedEffects to re-initialise on
        // the next frame — that's a whole-batch "jitter" / restart.
        for (fc in floatingChars.toList()) {
            key(fc.id) {
                val onComplete = remember<() -> Unit>(fc.id) {
                    { floatingChars.removeAll { it.id == fc.id } }
                }
                FloatingCharItem(
                    fc = fc,
                    overlayTopLeft = overlayTopLeft,
                    overlayWidth = overlayWidth,
                    overlayHeight = overlayHeight,
                    cursorXState = cursorXState,
                    cursorYState = cursorYState,
                    maxSpeed = maxSpeed,
                    steerAccel = steerAccel,
                    velocityDamping = velocityDamping,
                    dragVelScale = dragVelScale,
                    maxTime = maxTime,
                    charColor = charColor,
                    shadowColor = shadowColor,
                    onComplete = onComplete,
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
    val currentOverlayTopLeft by rememberUpdatedState(overlayTopLeft)
    val currentOverlayWidth by rememberUpdatedState(overlayWidth)
    val currentOverlayHeight by rememberUpdatedState(overlayHeight)

    val deadIdBuffer = remember { mutableListOf<Long>() }

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

                val bounceLeft = currentOverlayTopLeft.x
                val bounceRight = currentOverlayTopLeft.x + currentOverlayWidth
                val bounceTop = currentOverlayTopLeft.y
                val bounceBottom = currentOverlayTopLeft.y + currentOverlayHeight
                val bounceDamping = 0.6f

                deadIdBuffer.clear()
                val size = fracturingChars.size
                for (i in 0 until size) {
                    val char = fracturingChars.getOrNull(i) ?: continue
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

                    // Piecewise lifetime (~2.5 s total):
                    //   0.0 .. 1.5 s  — hold at full opacity while the debris
                    //                   is still readable and falling.
                    //   1.5 .. 2.5 s  — linear fade out.
                    // Chosen so a full-word deletion leaves particles on screen
                    // long enough to register as debris rather than a flicker,
                    // without cluttering the overlay for very long.
                    char.ageSec += dt
                    val hold = 1.5f
                    val fade = 1.0f
                    char.alpha = when {
                        char.ageSec <= hold -> 1f
                        char.ageSec >= hold + fade -> 0f
                        else -> 1f - (char.ageSec - hold) / fade
                    }
                    if (char.alpha <= 0f) {
                        char.isDead = true
                        deadIdBuffer.add(char.id)
                    }
                }
                for (id in deadIdBuffer) onCharAnimationFinished(id)
            }
        }
    }

    val measuredChars = remember { mutableMapOf<String, androidx.compose.ui.text.TextLayoutResult>() }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val shadowOffset = 2f
        val charCount = fracturingChars.size
        for (i in 0 until charCount) {
            val char = fracturingChars.getOrNull(i) ?: continue
            if (char.isDead) continue

            val baseLayout = measuredChars.getOrPut(char.text) {
                textMeasurer.measure(text = char.text, style = char.style)
            }
            val width = baseLayout.size.width.toFloat()
            val height = baseLayout.size.height.toFloat()

            for (frag in char.fragments) {
                val clipLeft = frag.clipRect.left * width
                val clipTop = frag.clipRect.top * height
                val clipRight = frag.clipRect.right * width
                val clipBottom = frag.clipRect.bottom * height
                val pivotX = (clipLeft + clipRight) * 0.5f
                val pivotY = (clipTop + clipBottom) * 0.5f
                val pivot = Offset(pivotX, pivotY)

                val localX = frag.currentX - overlayTopLeft.x
                val localY = frag.currentY - overlayTopLeft.y

                translate(left = localX + shadowOffset, top = localY + shadowOffset) {
                    rotate(degrees = frag.rotation, pivot = pivot) {
                        clipRect(clipLeft, clipTop, clipRight, clipBottom) {
                            drawText(baseLayout, alpha = char.alpha * 0.8f)
                        }
                    }
                }
                translate(left = localX, top = localY) {
                    rotate(degrees = frag.rotation, pivot = pivot) {
                        clipRect(clipLeft, clipTop, clipRight, clipBottom) {
                            drawText(baseLayout, alpha = char.alpha)
                        }
                    }
                }
            }
        }

        if (deadIdBuffer.isNotEmpty()) {
            measuredChars.keys.retainAll(fracturingChars.mapNotNullTo(HashSet()) { if (!it.isDead) it.text else null })
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
    cursorXState: State<Float>,
    cursorYState: State<Float>,
    maxSpeed: Float,
    steerAccel: Float,
    velocityDamping: Float,
    dragVelScale: Float,
    maxTime: Float,
    charColor: Color,
    shadowColor: Color,
    onComplete: () -> Unit,
) {
    val latestCursorX by cursorXState
    val latestCursorY by cursorYState
    val currentOverlayTopLeft by rememberUpdatedState(overlayTopLeft)
    val currentOverlayWidth by rememberUpdatedState(overlayWidth)
    val currentOverlayHeight by rememberUpdatedState(overlayHeight)

    LaunchedEffect(fc.id) {
        if (fc.delayMs > 0) {
            kotlinx.coroutines.delay(fc.delayMs)
        }

        // When cursor position is unknown, target the upper-third center of
        // the screen instead of a hardcoded offset.  The old -400px fallback
        // sends chars the wrong direction in freeform keyboard placement.
        val fallbackTargetX = currentOverlayTopLeft.x + currentOverlayWidth / 2f
        val fallbackTargetY = currentOverlayTopLeft.y + currentOverlayHeight / 3f

        // Re-read cursor AFTER the spawn delay — for sequential spray emits the
        // per-char delay can accumulate and the cursor may have been reported
        // late (CursorAnchorInfo lags commitText by a frame or two).  Using the
        // fresh reading avoids locking the whole spray batch to a stale NaN
        // fallback target.
        val initTargetX = if (!latestCursorX.isNaN()) latestCursorX else fallbackTargetX
        val initTargetY = if (!latestCursorY.isNaN()) latestCursorY else fallbackTargetY

        val dx0 = initTargetX - fc.startX
        val dy0 = initTargetY - fc.startY
        val dist0 = sqrt(dx0 * dx0 + dy0 * dy0).coerceAtLeast(1f)

        val bounceDamping = 0.6f

        var velX = fc.initVelX
        var velY = fc.initVelY
        if (velX == 0f && velY == 0f) {
            val launchSpeed = (dist0 * 5f).coerceIn(600f, maxSpeed)
            velX = dx0 / dist0 * launchSpeed
            velY = dy0 / dist0 * launchSpeed
        } else if (fc.isSpray) {
            val sprayMaxSpeed = maxSpeed * 1.8f
            val speed = sqrt(velX * velX + velY * velY)
            if (speed > sprayMaxSpeed) {
                velX = velX / speed * sprayMaxSpeed
                velY = velY / speed * sprayMaxSpeed
            }
        } else {
            velX *= dragVelScale
            velY *= dragVelScale
            // Anti-overshoot: if the inherited swipe velocity is pointing away from the target,
            // the wrong-way component causes a long rubberband arc before homing takes over.
            // Scale the component of velocity that points *away* from the target, keeping the
            // component that already points toward it. This gives steering a huge head start
            // without making the flick feel snapped.
            val dirX = dx0 / dist0
            val dirY = dy0 / dist0
            val vAlong = velX * dirX + velY * dirY
            if (vAlong < 0f) {
                // Remove 85% of the opposing velocity; keep 15% for a gentle "correction arc".
                val wrongX = dirX * vAlong
                val wrongY = dirY * vAlong
                velX -= wrongX * 0.85f
                velY -= wrongY * 0.85f
            }
            val speed = sqrt(velX * velX + velY * velY)
            if (speed > maxSpeed) {
                velX = velX / speed * maxSpeed
                velY = velY / speed * maxSpeed
            }
        }

        var prevNanos = 0L
        var totalTime = 0f
        val sprayFreeFlight = 0.08f
        // Track distance between frames for the near-latch heuristic.  Starts
        // as NaN so the bootstrap frame can seed it from the actual current
        // distance rather than from `dist0` — otherwise a single-frame cursor
        // jump at spawn looks like an overshoot and kills the char before it
        // ever moves.  See Bug 3 in FLOATING-ANIM notes.
        var prevDist = Float.NaN
        // Widen arrival for high-speed passes: if the char flies through the target fast,
        // a single frame can skip the entire ARRIVE_RADIUS window. Track whether we've
        // ever been close, so we can latch an arrival even if one frame overshoots.
        var everNear = false
        val nearLatchRadius = ARRIVE_RADIUS * 2.5f
        // Reference distance for the alpha fade.  Re-captured on the first real
        // frame so a cursor update arriving between spawn and first integration
        // doesn't lock the denominator to a stale `dist0`.
        var fadeRefDist = dist0

        while (isActive && fc.active) {
            val nanos = withFrameNanos { it }
            if (prevNanos == 0L) {
                prevNanos = nanos
                // Seed prev-distance and fade reference from the char's real
                // current position on the bootstrap frame.  Prevents spurious
                // "everNear && dist > prevDist" triggers on frame 2 when the
                // char has moved and prev was stuck on dist0.
                val bootToX = (if (!latestCursorX.isNaN()) latestCursorX else fallbackTargetX) - fc.posX
                val bootToY = (if (!latestCursorY.isNaN()) latestCursorY else fallbackTargetY) - fc.posY
                val bootDist = sqrt(bootToX * bootToX + bootToY * bootToY)
                prevDist = bootDist
                fadeRefDist = bootDist.coerceAtLeast(1f)
                continue
            }
            val dt = ((nanos - prevNanos) / 1_000_000_000f).coerceAtMost(0.05f)
            prevNanos = nanos
            totalTime += dt

            val targetX = if (!latestCursorX.isNaN()) latestCursorX else fallbackTargetX
            val targetY = if (!latestCursorY.isNaN()) latestCursorY else fallbackTargetY

            val toX = targetX - fc.posX
            val toY = targetY - fc.posY
            val dist = sqrt(toX * toX + toY * toY)

            // Standard arrival + time-out.  `fc.active = false` stops the
            // loop; the smooth fade-out runs after `break` so the char
            // doesn't pop in a single frame (important when a whole spray
            // batch arrives together).
            if (dist < ARRIVE_RADIUS || totalTime >= maxTime) {
                fc.active = false
                break
            }

            // Pass-through / near-latch: if we were inside the soft-arrive ring and the
            // distance just started growing, call it arrived. Kills the rubberband pass.
            //
            // Bug 2 fix: require that the PREVIOUS frame was also inside the
            // near-latch ring.  A cursor jump (autocorrect apply, tap-to-move,
            // IME commit) can suddenly increase `dist` for every in-flight char
            // with `everNear == true`, latching the entire batch to arrival in
            // one frame — the user sees all chars pop at once.  Requiring two
            // consecutive frames near the target treats real overshoots (the
            // char passes through) and filters cursor teleports.
            if (!fc.isSpray && dist < nearLatchRadius) everNear = true
            if (!fc.isSpray && everNear &&
                prevDist < nearLatchRadius &&
                dist > prevDist + 2f
            ) {
                fc.active = false
                break
            }

            val steerX = toX / dist
            val steerY = toY / dist
            if (fc.isSpray && totalTime < sprayFreeFlight) {
                val rampT = totalTime / sprayFreeFlight
                val spraySteer = steerAccel * (0.2f + 0.8f * rampT)
                velX += steerX * spraySteer * dt
                velY += steerY * spraySteer * dt
            } else {
                velX += steerX * steerAccel * dt
                velY += steerY * steerAccel * dt
            }

            // Split velocity into parallel (approach) and perpendicular (cross-track).
            // Standard damping on the approach axis, aggressive damping on the cross-track
            // axis. This kills lateral oscillation that otherwise makes the char snake
            // side-to-side while closing on the cursor.
            if (!fc.isSpray) {
                val vAlong = velX * steerX + velY * steerY
                val alongX = steerX * vAlong
                val alongY = steerY * vAlong
                val perpX = velX - alongX
                val perpY = velY - alongY

                val alongDamp = (1f - velocityDamping * dt).coerceAtLeast(0f)
                val perpDamp = (1f - velocityDamping * 3.5f * dt).coerceAtLeast(0f)

                velX = alongX * alongDamp + perpX * perpDamp
                velY = alongY * alongDamp + perpY * perpDamp
            } else {
                val dampFactor = (1f - velocityDamping * dt).coerceAtLeast(0f)
                velX *= dampFactor
                velY *= dampFactor
            }

            val effectiveMaxSpeed = if (fc.isSpray && totalTime < sprayFreeFlight) {
                maxSpeed * 2f
            } else {
                maxSpeed
            }

            // Cap the *approach* speed as we near the target — the previous code capped
            // total speed, which fought perpendicular velocity but happily let the char
            // sail past the cursor at max speed. Here we brake the component that's
            // actually going to overshoot.
            if (!fc.isSpray) {
                val vAlong = velX * steerX + velY * steerY
                if (vAlong > 0f) {
                    // Approach-speed ceiling: sqrt(2 * accel * dist) is the speed that still
                    // lets steering decelerate before we reach the target. Use a safety
                    // multiplier so we don't grind to a halt at long range.
                    val approachCap = kotlin.math.min(
                        effectiveMaxSpeed,
                        sqrt(2f * steerAccel * dist) * 0.9f + 200f,
                    )
                    if (vAlong > approachCap) {
                        val excess = vAlong - approachCap
                        velX -= steerX * excess
                        velY -= steerY * excess
                    }
                }
            }

            // Keep the global cap as a safety net.
            val speed = sqrt(velX * velX + velY * velY)
            if (speed > effectiveMaxSpeed) {
                val scale = effectiveMaxSpeed / speed
                velX *= scale
                velY *= scale
            }

            fc.posX += velX * dt
            fc.posY += velY * dt

            if (fc.spinSpeed != 0f) {
                val spinDamp = if (totalTime > sprayFreeFlight) 0.92f else 1f
                fc.rotation += fc.spinSpeed * spinDamp * dt
            }

            val bL = currentOverlayTopLeft.x
            val bR = currentOverlayTopLeft.x + currentOverlayWidth
            val bT = currentOverlayTopLeft.y
            val bB = currentOverlayTopLeft.y + currentOverlayHeight
            if (fc.posX < bL) { fc.posX = bL; velX = -velX * bounceDamping }
            if (fc.posX > bR) { fc.posX = bR; velX = -velX * bounceDamping }
            if (fc.posY < bT) { fc.posY = bT; velY = -velY * bounceDamping }
            if (fc.posY > bB) { fc.posY = bB; velY = -velY * bounceDamping }

            // Bug 1 fix: fade denominator was `dist0 * 0.3f`, captured at spawn.
            // If the cursor moved between spawn and first frame, `dist0` could
            // be way off, leaving chars either pinned to alpha=1 forever or
            // fading prematurely.  `fadeRefDist` is seeded from the real first
            // frame and gently tracks moving-away cursors so the fade stays
            // reasonable even if the user keeps moving the caret.
            if (dist > fadeRefDist) fadeRefDist = dist
            fc.alpha = (dist / (fadeRefDist * 0.3f)).coerceIn(0f, 1f)
            prevDist = dist
        }
        // Smooth fade-out so a whole batch arriving on the same frame doesn't
        // pop out together.  fadeOutAndRemove is a no-op if alpha is already 0.
        fadeOutAndRemove(fc)
        onComplete()
    }

    // Sub-pixel offset via graphicsLayer translation.  `Modifier.offset { ... }`
    // rounded to IntOffset, which made low-speed approach motion stutter one
    // pixel at a time — visible as a whole batch of chars juddering in unison
    // when they all approach the same cursor at similar speeds.  translationX/Y
    // on graphicsLayer is a float GPU transform, no rounding.
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
            .graphicsLayer(
                translationX = fc.posX - overlayTopLeft.x,
                translationY = fc.posY - overlayTopLeft.y,
                alpha = fc.alpha,
                rotationZ = fc.rotation,
                scaleX = fc.scale,
                scaleY = fc.scale,
            ),
    )
}

/**
 * Smoothly fades `fc.alpha` from its current value down to 0 over ~8 frames
 * (~130 ms at 60 Hz).  Called after the physics loop breaks so a whole batch
 * arriving on the same frame doesn't pop out together — instead each char
 * finishes its own short fade before [FloatingCharOverlay]'s `onComplete`
 * removes it from the snapshot list.  A no-op if alpha is already 0.
 */
private suspend fun fadeOutAndRemove(fc: FloatingChar) {
    val startAlpha = fc.alpha
    if (startAlpha <= 0f) return
    val steps = 8
    for (i in 1..steps) {
        withFrameNanos { /* pace to vsync */ }
        fc.alpha = (startAlpha * (1f - i.toFloat() / steps)).coerceAtLeast(0f)
    }
    fc.alpha = 0f
}
