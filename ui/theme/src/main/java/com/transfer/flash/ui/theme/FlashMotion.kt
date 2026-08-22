package com.transfer.flash.ui.theme

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset

/**
 * Flash motion design tokens (UI-037).
 *
 * All chat animations should reference [FlashTheme.motion] — no ad-hoc durations in components.
 */
class FlashMotion internal constructor(
    val reduceMotion: Boolean,
) {
    val fastMillis: Int
        get() = if (reduceMotion) 0 else FastMillis

    val normalMillis: Int
        get() = if (reduceMotion) 0 else NormalMillis

    val slowMillis: Int
        get() = if (reduceMotion) 0 else SlowMillis

    val emphasisMillis: Int
        get() = if (reduceMotion) 0 else EmphasisMillis

    /** Header status, metadata, small state swaps. */
    fun statusCrossfade(): ContentTransform {
        if (reduceMotion) {
            return EnterTransition.None togetherWith ExitTransition.None
        }
        return fadeIn(tween(fastMillis, easing = Decelerate)) togetherWith
            fadeOut(tween(fastMillis, easing = Accelerate))
    }

    /** New message tail insert (UI-005 / UI-006). */
    fun messageEnter(): EnterTransition {
        if (reduceMotion) {
            return EnterTransition.None
        }
        return fadeIn(tween(normalMillis, easing = Decelerate)) +
            slideInVertically(tween(normalMillis, easing = Decelerate)) { fullHeight -> fullHeight / 4 } +
            scaleIn(
                initialScale = 0.96f,
                animationSpec = spring(
                    dampingRatio = SpringSnappyDamping,
                    stiffness = SpringSnappyStiffness,
                ),
            )
    }

    /** Message remove / send failure rollback. */
    fun messageExit(): ExitTransition {
        if (reduceMotion) {
            return ExitTransition.None
        }
        return fadeOut(tween(fastMillis, easing = Accelerate))
    }

    /** UI-006: sibling glide while messages are inserted/removed; snap under reduce-motion. */
    fun messagePlacementSpec(): FiniteAnimationSpec<IntOffset> =
        if (reduceMotion) {
            snap()
        } else {
            spring(
                dampingRatio = SpringDefaultDamping,
                stiffness = SpringDefaultStiffness,
                visibilityThreshold = IntOffset.VisibilityThreshold,
            )
        }

    /** UI-006: list-item removal fade; zero-duration under reduce-motion. */
    fun messageFadeOutSpec(): FiniteAnimationSpec<Float> =
        tween(fastMillis, easing = Accelerate)

    /**
     * UI-006: one-shot 0→1 progress driving the Flash message entrance (alpha, quarter-height
     * rise, 4% scale) for a freshly appended tail message — the [messageEnter] channels at its
     * exact duration/easing. Historical messages and reduce-motion report 1f immediately.
     * The start value is fixed at item birth; later `animate` changes must not reset it.
     */
    @Composable
    fun rememberMessageEnterProgress(animate: Boolean): State<Float> {
        val progress = remember { Animatable(if (animate && !reduceMotion) 0f else 1f) }
        LaunchedEffect(Unit) {
            if (progress.value != 1f) {
                progress.animateTo(1f, tween(normalMillis, easing = Decelerate))
            }
        }
        return progress.asState()
    }

    /** Conversation ↔ list navigation (UI-033). */
    fun screenEnter(): EnterTransition {
        if (reduceMotion) {
            return EnterTransition.None
        }
        return fadeIn(tween(slowMillis, easing = Decelerate)) +
            slideInHorizontally(tween(slowMillis, easing = Standard)) { fullWidth -> (fullWidth * 0.3f).toInt() }
    }

    fun screenExit(): ExitTransition {
        if (reduceMotion) {
            return ExitTransition.None
        }
        return fadeOut(tween(slowMillis, easing = Accelerate)) +
            slideOutHorizontally(tween(slowMillis, easing = Accelerate)) { fullWidth -> (-fullWidth * 0.3f).toInt() }
    }

    /** Multi-line composer height growth (UI-011). */
    fun composerExpandEnter(): EnterTransition {
        if (reduceMotion) {
            return EnterTransition.None
        }
        return fadeIn(tween(normalMillis, easing = Decelerate)) +
            slideInVertically(
                animationSpec = spring(
                    dampingRatio = SpringGentleDamping,
                    stiffness = SpringGentleStiffness,
                ),
                initialOffsetY = { fullHeight -> fullHeight / 8 },
            )
    }

    /** Reply quote strip reveal (UI-010). */
    fun replyExpandEnter(): EnterTransition {
        if (reduceMotion) {
            return EnterTransition.None
        }
        return fadeIn(tween(normalMillis, easing = Decelerate)) +
            slideInVertically(tween(normalMillis, easing = Standard)) { fullHeight -> fullHeight / 6 }
    }

    /** Full-screen media open (UI-018). */
    fun mediaOpenEnter(): EnterTransition {
        if (reduceMotion) {
            return EnterTransition.None
        }
        return fadeIn(tween(slowMillis, easing = Decelerate)) +
            scaleIn(
                initialScale = 0.92f,
                animationSpec = tween(slowMillis, easing = Decelerate),
            )
    }

    fun mediaOpenExit(): ExitTransition {
        if (reduceMotion) {
            return ExitTransition.None
        }
        return fadeOut(tween(fastMillis, easing = Accelerate)) +
            scaleOut(
                targetScale = 0.96f,
                animationSpec = tween(fastMillis, easing = Accelerate),
            )
    }

    /** Micro tap feedback scale spec (UI-041 send / reaction). */
    fun <T> springSnappySpec(): SpringSpec<T> = spring(
        dampingRatio = SpringSnappyDamping,
        stiffness = SpringSnappyStiffness,
    )

    fun <T> springDefaultSpec(): SpringSpec<T> = spring(
        dampingRatio = SpringDefaultDamping,
        stiffness = SpringDefaultStiffness,
    )

    fun <T> springGentleSpec(): SpringSpec<T> = spring(
        dampingRatio = SpringGentleDamping,
        stiffness = SpringGentleStiffness,
    )

    fun <T> tweenFastSpec(): androidx.compose.animation.core.TweenSpec<T> =
        tween(fastMillis, easing = Standard)

    fun <T> tweenNormalSpec(): androidx.compose.animation.core.TweenSpec<T> =
        tween(normalMillis, easing = Standard)

    companion object {
        const val FastMillis = 120
        const val NormalMillis = 200
        const val SlowMillis = 320
        const val EmphasisMillis = 400

        const val SpringSnappyDamping = 0.85f
        const val SpringSnappyStiffness = 600f
        const val SpringDefaultDamping = 0.90f
        const val SpringDefaultStiffness = 400f
        const val SpringGentleDamping = 1.00f
        const val SpringGentleStiffness = 280f

        val Decelerate = CubicBezierEasing(0f, 0f, 0.2f, 1f)
        val Standard = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
        val Accelerate = CubicBezierEasing(0.4f, 0f, 1f, 1f)

        fun isReduceMotionEnabled(context: Context): Boolean {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
            if (scale == 0f) {
                return true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val accessibilityManager =
                    context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                if (accessibilityManager != null && accessibilityManager.isReduceMotionEnabledCompat()) {
                    return true
                }
            }
            return false
        }

        private fun AccessibilityManager.isReduceMotionEnabledCompat(): Boolean {
            return runCatching {
                AccessibilityManager::class.java
                    .getMethod("isReduceMotionEnabled")
                    .invoke(this) as Boolean
            }.getOrDefault(false)
        }
    }
}

@Composable
fun rememberFlashMotion(): FlashMotion {
    val context = LocalContext.current
    val reduceMotion = remember(context) { FlashMotion.isReduceMotionEnabled(context) }
    return remember(reduceMotion) { FlashMotion(reduceMotion = reduceMotion) }
}
