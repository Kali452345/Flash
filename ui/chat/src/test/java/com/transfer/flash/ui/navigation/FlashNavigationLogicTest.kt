package com.transfer.flash.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for UI-033 dependency-free navigation logic (pure state holder + math helpers).
 */
class FlashNavigationLogicTest {

    @Test
    fun `initial state is ChatList root and cannot go back`() {
        val nav = FlashNavigationState()
        assertEquals(FlashDestination.ChatList, nav.current.destination)
        assertNull(nav.current.conversationId)
        assertEquals(1, nav.stackSize)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun `back at root returns false and keeps state`() {
        val nav = FlashNavigationState()
        assertFalse(nav.back())
        assertEquals(FlashDestination.ChatList, nav.current.destination)
    }

    @Test
    fun `navigate pushes entry and back pops it`() {
        val nav = FlashNavigationState()
        nav.navigate(FlashDestination.Conversation, conversationId = "conv-1")
        assertEquals(FlashDestination.Conversation, nav.current.destination)
        assertEquals("conv-1", nav.current.conversationId)
        assertTrue(nav.canGoBack)

        assertTrue(nav.back())
        assertEquals(FlashDestination.ChatList, nav.current.destination)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun `duplicate consecutive push to same destination is ignored`() {
        val nav = FlashNavigationState()
        nav.navigate(FlashDestination.Transfers)
        nav.navigate(FlashDestination.Transfers)
        nav.navigate(FlashDestination.Transfers)
        assertEquals(2, nav.stackSize)
        assertEquals(FlashDestination.Transfers, nav.current.destination)
    }

    @Test
    fun `re-pushing the same conversation is ignored`() {
        val nav = FlashNavigationState()
        nav.navigate(FlashDestination.Conversation, conversationId = "conv-a")
        nav.navigate(FlashDestination.Conversation, conversationId = "conv-a")
        assertEquals(2, nav.stackSize)
        assertEquals("conv-a", nav.current.conversationId)
    }

    @Test
    fun `switching between two conversations counts as distinct pushes`() {
        val nav = FlashNavigationState()
        nav.navigate(FlashDestination.Conversation, conversationId = "conv-a")
        nav.navigate(FlashDestination.Conversation, conversationId = "conv-b")
        assertEquals(3, nav.stackSize)
        assertEquals("conv-b", nav.current.conversationId)

        assertTrue(nav.back())
        assertEquals("conv-a", nav.current.conversationId)
        assertTrue(nav.back())
        assertEquals(FlashDestination.ChatList, nav.current.destination)
    }

    @Test
    fun `same destination with different conversationId is a distinct push`() {
        val nav = FlashNavigationState()
        nav.navigate(FlashDestination.NearbyDevices)
        nav.navigate(FlashDestination.NearbyDevices) // ignored: exact duplicate
        nav.navigate(FlashDestination.Conversation, conversationId = "peer-x")
        assertEquals(3, nav.stackSize)
    }

    @Test
    fun `stack depth is capped while the root is preserved`() {
        val nav = FlashNavigationState()
        for (i in 1..12) {
            nav.navigate(FlashDestination.Conversation, conversationId = "conv-$i")
        }
        assertEquals(FlashNavigationMath.MAX_STACK_DEPTH, nav.stackSize)
        // Root (ChatList) must survive; oldest pushed conversation dropped off.
        assertEquals(FlashBackStackState(FlashDestination.ChatList), nav.stackPeekAt(0))
        assertEquals("conv-12", nav.current.conversationId)
        assertEquals(
            FlashNavigationMath.MAX_STACK_DEPTH,
            FlashNavigationMath.nextStackSize(FlashNavigationMath.MAX_STACK_DEPTH, pushing = true),
        )
    }

    @Test
    fun `navigate with invalid Conversation entry is rejected`() {
        val nav = FlashNavigationState()
        nav.navigate(FlashDestination.Conversation) // missing id
        nav.navigate(FlashDestination.Conversation, conversationId = "   ") // blank id
        assertEquals(1, nav.stackSize)
        assertEquals(FlashDestination.ChatList, nav.current.destination)
    }

    @Test
    fun `non-conversation destinations reject a conversationId`() {
        assertFalse(FlashBackStackState(FlashDestination.ChatList, "conv-1").isValid())
        assertFalse(FlashBackStackState(FlashDestination.Transfers, "conv-1").isValid())
        assertTrue(FlashBackStackState(FlashDestination.Transfers).isValid())
        assertTrue(FlashBackStackState(FlashDestination.Conversation, "conv-1").isValid())
    }

    @Test
    fun `math helpers agree with state holder behavior`() {
        assertEquals(4, FlashNavigationMath.nextStackSize(3, pushing = true))
        assertEquals(3, FlashNavigationMath.nextStackSize(3, pushing = false))
        assertTrue(
            FlashNavigationMath.shouldIgnorePush(
                FlashDestination.Conversation, "c1", FlashDestination.Conversation, "c1",
            ),
        )
        assertFalse(
            FlashNavigationMath.shouldIgnorePush(
                FlashDestination.Conversation, "c1", FlashDestination.Conversation, "c2",
            ),
        )
        assertFalse(
            FlashNavigationMath.shouldIgnorePush(
                FlashDestination.ChatList, null, FlashDestination.Transfers, null,
            ),
        )
    }

    @Test
    fun `conversation title falls back to destination title`() {
        assertEquals(
            "Ravi",
            FlashNavigationMath.resolveConversationTitle(FlashDestination.Conversation, "Ravi"),
        )
        assertEquals(
            "Conversation",
            FlashNavigationMath.resolveConversationTitle(FlashDestination.Conversation, "  "),
        )
        assertEquals(
            "Chats",
            FlashNavigationMath.resolveConversationTitle(FlashDestination.ChatList),
        )
    }
}

/** Test-only access to an arbitrary stack slot without exposing mutable internals. */
private fun FlashNavigationState.stackPeekAt(index: Int): FlashBackStackState {
    val field = FlashNavigationState::class.java.getDeclaredField("stack")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val stack = field.get(this) as List<FlashBackStackState>
    return stack[index]
}
