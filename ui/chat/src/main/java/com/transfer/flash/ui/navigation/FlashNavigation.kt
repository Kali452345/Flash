package com.transfer.flash.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.transfer.flash.ui.theme.FlashTheme

/** Top-level destinations of the Flash showcase app (UI-033). */
enum class FlashDestination(val title: String) {
    ChatList("Chats"),
    Conversation("Conversation"),
    Transfers("Transfers"),
    NearbyDevices("Nearby devices"),
}

/**
 * Immutable snapshot of one back-stack entry (UI-033).
 *
 * [isValid] enforces that a [FlashDestination.Conversation] entry always carries
 * a non-blank [conversationId] and that other destinations carry none.
 */
data class FlashBackStackState(
    val destination: FlashDestination = FlashDestination.ChatList,
    val conversationId: String? = null,
) {
    fun isValid(): Boolean = when (destination) {
        FlashDestination.Conversation -> !conversationId.isNullOrBlank()
        else -> conversationId == null
    }
}

/** Pure helpers backing the navigation logic so it can be unit tested without Compose. */
object FlashNavigationMath {

    /** Maximum retained entries including the root; older entries drop off the bottom. */
    const val MAX_STACK_DEPTH = 10

    /**
     * Stack size after an operation: pushing adds one entry capped at [MAX_STACK_DEPTH],
     * any other operation keeps the current size.
     */
    fun nextStackSize(currentSize: Int, pushing: Boolean): Int =
        if (pushing) minOf(currentSize + 1, MAX_STACK_DEPTH) else currentSize

    /**
     * True when a push must be ignored because it repeats the current entry,
     * including re-pushing the exact same conversation.
     */
    fun shouldIgnorePush(
        currentDestination: FlashDestination,
        currentConversationId: String?,
        newDestination: FlashDestination,
        newConversationId: String?,
    ): Boolean = currentDestination == newDestination && currentConversationId == newConversationId

    /** Title shown for a destination; a loaded conversation name wins over the enum title. */
    fun resolveConversationTitle(
        destination: FlashDestination,
        conversationName: String? = null,
    ): String = conversationName?.takeIf { it.isNotBlank() } ?: destination.title
}

/**
 * Dependency-free navigation state holder (UI-033).
 *
 * A plain class with no Compose runtime types so the push/back logic is unit-testable;
 * wrap it with [rememberFlashNavigationState] inside composition. Single source of truth:
 * screens are rendered from [current], system back calls [back].
 */
class FlashNavigationState(initial: FlashDestination = FlashDestination.ChatList) {

    private val stack = mutableListOf(FlashBackStackState(initial))

    /** Current top-of-stack entry; never empty (root survives all operations). */
    val current: FlashBackStackState
        get() = stack.last()

    /** Number of retained entries, root included. */
    val stackSize: Int
        get() = stack.size

    /** True when at least one entry sits above the root. */
    val canGoBack: Boolean
        get() = stack.size > 1

    /**
     * Pushes [destination] onto the stack unless the push duplicates the current entry
     * (same destination and conversationId) or produces an invalid entry. When the stack
     * exceeds [FlashNavigationMath.MAX_STACK_DEPTH] the oldest entry below the root drops off;
     * the root itself is always preserved.
     */
    fun navigate(destination: FlashDestination, conversationId: String? = null) {
        val next = FlashBackStackState(destination, conversationId)
        if (!next.isValid()) return
        if (FlashNavigationMath.shouldIgnorePush(
                current.destination, current.conversationId, destination, conversationId,
            )
        ) {
            return
        }
        stack.add(next)
        if (stack.size > FlashNavigationMath.MAX_STACK_DEPTH && stack.size > 1) {
            stack.removeAt(1)
        }
    }

    /**
     * Pops the top entry. Returns true when the stack changed, false when already at root
     * (letting the caller fall through to system back / exit behavior).
     */
    fun back(): Boolean {
        if (!canGoBack) return false
        stack.removeAt(stack.lastIndex)
        return true
    }
}

/** Remembers a [FlashNavigationState] across recomposition. */
@Composable
fun rememberFlashNavigationState(): FlashNavigationState = remember { FlashNavigationState() }

/**
 * Screen-level transition wrapper (UI-033) over foundation `AnimatedContent`, driven
 * exclusively by the reserved UI-037 motion tokens `screenEnter()` / `screenExit()`.
 *
 * Deliberately generic: the caller decides what type identifies a "screen"
 * (e.g. [FlashBackStackState]) so this stays reusable without coupling to the nav contract.
 */
@Composable
fun <T> FlashAnimatedScreen(
    targetState: T,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val motion = FlashTheme.motion
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            motion.screenEnter() togetherWith motion.screenExit()
        },
        label = "flashNavigation",
    ) { page ->
        content(page)
    }
}
