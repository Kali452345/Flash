package com.transfer.flash.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class FlashThemeTokensTest {

    @Test
    fun lightColors_haveExpectedValues() {
        val colors = FlashColors.light()
        assertNotNull(colors.accentPrimary)
        assertNotNull(colors.backgroundApp)
        assertNotNull(colors.textPrimary)
        assertEquals(FlashColors.light().accentPrimary, colors.accentPrimary)
    }

    @Test
    fun darkColors_haveExpectedValues() {
        val darkColors = FlashColors.dark()
        assertNotNull(darkColors.accentPrimary)
        assertNotNull(darkColors.backgroundApp)
        assertNotNull(darkColors.textPrimary)
    }

    @Test
    fun spacing_tokensArePositive() {
        assert(FlashSpacing.space2.value > 0)
        assert(FlashSpacing.space4.value > 0)
        assert(FlashSpacing.space8.value > 0)
        assert(FlashSpacing.space12.value > 0)
        assert(FlashSpacing.space16.value > 0)
    }

    @Test
    fun dimensions_tokensArePositive() {
        assert(FlashDimensions.headerHeight.value > 0)
        assert(FlashDimensions.minTouchTarget.value > 0)
    }
}
