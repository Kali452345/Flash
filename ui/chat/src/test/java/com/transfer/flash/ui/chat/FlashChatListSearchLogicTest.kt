package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.model.FlashChatListItemUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashChatListSearchLogicTest {

    private fun chat(
        id: String,
        title: String,
        previewText: String,
    ) = FlashChatListItemUi(
        id = id,
        title = title,
        avatarInitials = "X",
        previewText = previewText,
        timestamp = "10:00 AM",
    )

    private val sampleChats = listOf(
        chat("c1", "False School", "Flash keeps this conversation local and private."),
        chat("c2", "Alex Chen", "Can you send the build over Wi-Fi Direct?"),
        chat("c3", "Design Team", "Belal: Updated the motion tokens doc"),
    )

    @Test
    fun `filter matches title case-insensitively`() {
        val result = FlashChatListSearchMath.filterChats(sampleChats, "ALEX")
        assertEquals(listOf("c2"), result.map { it.id })
    }

    @Test
    fun `filter matches preview text`() {
        val result = FlashChatListSearchMath.filterChats(sampleChats, "wi-fi direct")
        assertEquals(listOf("c2"), result.map { it.id })
    }

    @Test
    fun `filter matches title or preview and preserves original order`() {
        // "flash" appears in c1's title AND c1's preview; "doc" matches only c3's preview
        val result = FlashChatListSearchMath.filterChats(sampleChats, "doc")
        assertEquals(listOf("c3"), result.map { it.id })

        // Query matching several chats keeps list order (no relevance re-sorting)
        val multi = FlashChatListSearchMath.filterChats(sampleChats, "a")
        assertTrue(multi.size > 1)
        assertEquals(
            sampleChats.filter {
                it.title.contains("a", ignoreCase = true) || it.previewText.contains("a", ignoreCase = true)
            }.map { it.id },
            multi.map { it.id },
        )
    }

    @Test
    fun `blank query returns all items unfiltered`() {
        assertEquals(3, FlashChatListSearchMath.filterChats(sampleChats, "").size)
        assertEquals(3, FlashChatListSearchMath.filterChats(sampleChats, "   ").size)
        assertEquals(sampleChats.map { it.id }, FlashChatListSearchMath.filterChats(sampleChats, " ").map { it.id })
    }

    @Test
    fun `query with no matches yields empty list`() {
        assertTrue(FlashChatListSearchMath.filterChats(sampleChats, "zebra").isEmpty())
    }

    @Test
    fun `search active only for non-blank trimmed query`() {
        assertFalse(FlashChatListSearchMath.isSearchActive(""))
        assertFalse(FlashChatListSearchMath.isSearchActive("   "))
        assertTrue(FlashChatListSearchMath.isSearchActive("flash"))
        assertTrue(FlashChatListSearchMath.isSearchActive("  flash "))
    }

    @Test
    fun `result count label is singular and plural`() {
        assertEquals("0 chats", FlashChatListSearchMath.resultCountLabel(0))
        assertEquals("1 chat", FlashChatListSearchMath.resultCountLabel(1))
        assertEquals("12 chats", FlashChatListSearchMath.resultCountLabel(12))
    }

    @Test
    fun `recent searches dedupe case-insensitively keeping first spelling`() {
        val recents = FlashChatListSearchMath.recentSearches(
            listOf("Build", "build", "  BUILD ", "wi-fi"),
        )
        assertEquals(listOf("Build", "wi-fi"), recents)
    }

    @Test
    fun `recent searches trims entries and skips blanks`() {
        val recents = FlashChatListSearchMath.recentSearches(
            listOf("", "   ", " motion doc "),
        )
        assertEquals(listOf("motion doc"), recents)
    }

    @Test
    fun `recent searches capped at limit most recent`() {
        val recents = FlashChatListSearchMath.recentSearches(
            listOf("q1", "q2", "q3", "q4", "q5", "q6", "q7"),
            limit = 5,
        )
        assertEquals(listOf("q1", "q2", "q3", "q4", "q5"), recents)
    }
}
