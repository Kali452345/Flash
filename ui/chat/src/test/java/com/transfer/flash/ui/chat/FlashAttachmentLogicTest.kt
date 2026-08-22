package com.transfer.flash.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashAttachmentLogicTest {

    @Test
    fun `attachment types define all 5 core Flash action categories`() {
        val types = FlashAttachmentType.values()
        assertEquals(5, types.size)

        val labels = types.map { it.label }
        assertTrue(labels.contains("Gallery"))
        assertTrue(labels.contains("Files"))
        assertTrue(labels.contains("Camera"))
        assertTrue(labels.contains("Audio"))
        assertTrue(labels.contains("Flash P2P"))
    }

    @Test
    fun `attachment types have valid non-empty labels and icons`() {
        for (type in FlashAttachmentType.values()) {
            assertTrue("Label for $type must not be blank", type.label.isNotBlank())
            assertNotNull("Icon for $type must not be null", type.icon)
            assertNotNull("Container color for $type must not be null", type.containerColor)
        }
    }
}
