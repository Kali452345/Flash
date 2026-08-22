package com.transfer.flash.ui.icons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlashIconSheet(
    modifier: Modifier = Modifier,
    icons: List<FlashIconSpec> = FlashIcons.mvpChatSet,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography

    Column(
        modifier = modifier
            .background(colors.backgroundApp)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(FlashSpacing.space16),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space16),
    ) {
        Text(
            text = "Flash Icons — MVP set",
            style = typography.headingMedium,
            color = colors.textPrimary,
        )
        Text(
            text = "1.5dp stroke · 20dp grid · Flash-owned vectors",
            style = typography.captionDefault,
            color = colors.textSecondary,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
            verticalArrangement = Arrangement.spacedBy(FlashSpacing.space16),
        ) {
            icons.forEach { spec ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(FlashSpacing.space4),
                ) {
                    FlashIcon(
                        icon = spec,
                        modifier = Modifier.size(FlashSpacing.space32),
                        state = FlashIconState.Default,
                    )
                    Text(
                        text = spec.contentDescription.substringBefore(' '),
                        style = typography.metadataDefault,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(top = FlashSpacing.space4),
                    )
                }
            }
        }

        Text(
            text = "State variants",
            style = typography.headingSmall,
            color = colors.textPrimary,
            modifier = Modifier.padding(top = FlashSpacing.space8),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space16)) {
            FlashIconState.entries.forEach { state ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FlashIcon(icon = FlashIcons.Send, state = state)
                    Text(
                        text = state.name,
                        style = typography.metadataDefault,
                        color = colors.textTertiary,
                    )
                }
            }
        }
    }
}

@Preview(name = "Flash icons — light", showBackground = true, widthDp = 390)
@Composable
private fun FlashIconSheetLightPreview() {
    FlashTheme(darkTheme = false) {
        FlashIconSheet()
    }
}

@Preview(name = "Flash icons — dark", showBackground = true, widthDp = 390)
@Composable
private fun FlashIconSheetDarkPreview() {
    FlashTheme(darkTheme = true) {
        FlashIconSheet()
    }
}
