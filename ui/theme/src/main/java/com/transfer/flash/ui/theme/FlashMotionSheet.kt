package com.transfer.flash.ui.theme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * QA surface demonstrating Flash motion tokens (UI-037).
 */
@Composable
fun FlashMotionSheet(modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val motion = FlashTheme.motion

    var statusIndex by remember { mutableIntStateOf(0) }
    var showMessage by remember { mutableStateOf(true) }
    var tapPulse by remember { mutableStateOf(false) }

    val statusLabels = remember {
        listOf("Online", "Connecting…", "typing…", "Offline")
    }

    val tapScale by animateFloatAsState(
        targetValue = if (tapPulse) 0.96f else 1f,
        animationSpec = motion.springSnappySpec<Float>(),
        label = "flashMotionTapPulse",
    )

    LaunchedEffect(tapPulse) {
        if (tapPulse) {
            delay(motion.fastMillis.toLong().coerceAtLeast(1L))
            tapPulse = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backgroundApp)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(FlashSpacing.space16),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space16),
    ) {
        Text(
            text = "Flash Motion — UI-037",
            style = typography.headingMedium,
            color = colors.textPrimary,
        )
        Text(
            text = "fast ${motion.fastMillis}ms · normal ${motion.normalMillis}ms · slow ${motion.slowMillis}ms" +
                if (motion.reduceMotion) " · reduce motion ON" else "",
            style = typography.captionDefault,
            color = colors.textSecondary,
        )

        MotionSection(title = "statusCrossfade (header)") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedContent(
                    targetState = statusLabels[statusIndex],
                    transitionSpec = { motion.statusCrossfade() },
                    label = "motionSheetStatus",
                ) { label ->
                    Text(
                        text = label,
                        style = typography.metadataEmphasis,
                        color = if (label == "typing…") colors.accentPrimary else colors.textSecondary,
                    )
                }
                Button(onClick = { statusIndex = (statusIndex + 1) % statusLabels.size }) {
                    Text("Cycle")
                }
            }
        }

        MotionSection(title = "messageEnter / messageExit") {
            AnimatedVisibility(
                visible = showMessage,
                enter = motion.messageEnter(),
                exit = motion.messageExit(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(FlashShapes.bubbleGrouped)
                        .background(colors.chatBgOutgoing)
                        .padding(FlashSpacing.space12),
                ) {
                    Text(
                        text = "New message tail insert",
                        style = typography.bodyDefault,
                        color = colors.chatTextOutgoing,
                    )
                }
            }
            Button(onClick = { showMessage = !showMessage }) {
                Text(if (showMessage) "Hide message" else "Show message")
            }
        }

        MotionSection(title = "springSnappy (micro tap)") {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .scale(tapScale)
                    .clip(RoundedCornerShape(FlashShapes.radiusFull))
                    .background(colors.accentPrimary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Send",
                    style = typography.captionEmphasis,
                    color = colors.textOnAccent,
                )
            }
            Button(onClick = { tapPulse = true }) {
                Text("Pulse")
            }
        }

        Spacer(modifier = Modifier.height(FlashSpacing.space24))
    }
}

@Composable
private fun MotionSection(
    title: String,
    content: @Composable () -> Unit,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(colors.backgroundSurface)
            .padding(FlashSpacing.space12),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        Text(
            text = title,
            style = typography.headingSmall,
            color = colors.textPrimary,
        )
        content()
    }
}

@Preview(name = "Motion sheet — light", showBackground = true, widthDp = 390)
@Composable
private fun FlashMotionSheetLightPreview() {
    FlashTheme(darkTheme = false) {
        FlashMotionSheet()
    }
}

@Preview(name = "Motion sheet — dark", showBackground = true, widthDp = 390)
@Composable
private fun FlashMotionSheetDarkPreview() {
    FlashTheme(darkTheme = true) {
        FlashMotionSheet()
    }
}

@Preview(name = "Motion sheet — reduce motion", showBackground = true, widthDp = 390)
@Composable
private fun FlashMotionSheetReducedPreview() {
    FlashTheme(motion = FlashMotion(reduceMotion = true)) {
        FlashMotionSheet()
    }
}
