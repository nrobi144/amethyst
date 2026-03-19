/*
 * Copyright (c) 2025 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.vitorpamplona.amethyst.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vitorpamplona.amethyst.commons.model.Note
import com.vitorpamplona.amethyst.commons.ui.components.EmptyState
import com.vitorpamplona.amethyst.commons.ui.components.LoadingState
import com.vitorpamplona.amethyst.commons.ui.thread.drawReplyLevel
import com.vitorpamplona.amethyst.desktop.account.AccountState
import com.vitorpamplona.amethyst.desktop.cache.DesktopLocalCache
import com.vitorpamplona.amethyst.desktop.network.DesktopRelayConnectionManager
import com.vitorpamplona.amethyst.desktop.subscriptions.DesktopRelaySubscriptionsCoordinator
import com.vitorpamplona.amethyst.desktop.subscriptions.FilterBuilders
import com.vitorpamplona.amethyst.desktop.subscriptions.SubscriptionConfig
import com.vitorpamplona.amethyst.desktop.subscriptions.createNoteSubscription
import com.vitorpamplona.amethyst.desktop.subscriptions.createThreadRepliesSubscription
import com.vitorpamplona.amethyst.desktop.subscriptions.rememberSubscription
import com.vitorpamplona.amethyst.desktop.ui.note.NoteCard
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip51Lists.bookmarkList.BookmarkListEvent
import com.vitorpamplona.quartz.nip51Lists.bookmarkList.tags.EventBookmark

/**
 * Desktop Thread Screen - displays a note and all its replies in a thread view.
 *
 * Routes all events through cache via coordinator.consumeEvent().
 * Reads zap/reaction/reply counts from Note model (no per-screen state maps).
 */
@Composable
fun ThreadScreen(
    noteId: String,
    relayManager: DesktopRelayConnectionManager,
    localCache: DesktopLocalCache,
    account: AccountState.LoggedIn?,
    nwcConnection: com.vitorpamplona.quartz.nip47WalletConnect.Nip47WalletConnect.Nip47URINorm? = null,
    subscriptionsCoordinator: DesktopRelaySubscriptionsCoordinator? = null,
    onBack: () -> Unit,
    onNavigateToProfile: (String) -> Unit = {},
    onNavigateToThread: (String) -> Unit = {},
    onZapFeedback: (ZapFeedback) -> Unit = {},
    onReply: (Event) -> Unit = {},
) {
    val connectedRelays by relayManager.connectedRelays.collectAsState()

    // Root note from cache
    var rootNote by remember(noteId) { mutableStateOf(localCache.getNoteIfExists(noteId)) }

    // Track EOSE
    var rootNoteEoseReceived by remember(noteId) { mutableStateOf(false) }
    var repliesEoseReceived by remember(noteId) { mutableStateOf(false) }

    // Reply notes: collected from Note.replies in cache after events are consumed
    var replyNotes by remember(noteId) { mutableStateOf<List<Note>>(emptyList()) }

    // Cache for calculating reply levels
    val levelCache = remember(noteId) { mutableMapOf<String, Int>() }

    // Bookmark state
    var bookmarkList by remember { mutableStateOf<BookmarkListEvent?>(null) }
    var bookmarkedEventIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Refresh reply list from cache when new events arrive
    fun refreshReplies() {
        val root = localCache.getNoteIfExists(noteId)
        rootNote = root
        if (root != null) {
            val replies = mutableListOf<Note>()
            collectReplies(root, replies)
            replyNotes = replies.sortedBy { it.createdAt() ?: 0 }
        }
    }

    // Load metadata for thread authors via coordinator
    LaunchedEffect(rootNote, replyNotes, subscriptionsCoordinator) {
        if (subscriptionsCoordinator != null) {
            val pubkeys = mutableListOf<String>()
            rootNote?.author?.pubkeyHex?.let { pubkeys.add(it) }
            pubkeys.addAll(replyNotes.mapNotNull { it.author?.pubkeyHex })
            if (pubkeys.isNotEmpty()) {
                subscriptionsCoordinator.loadMetadataForPubkeys(pubkeys.distinct())
            }
        }
    }

    // Subscribe to user's bookmark list
    rememberSubscription(connectedRelays, account, relayManager = relayManager) {
        if (connectedRelays.isNotEmpty() && account != null) {
            SubscriptionConfig(
                subId = "thread-bookmarks-${account.pubKeyHex.take(8)}",
                filters =
                    listOf(
                        FilterBuilders.byAuthors(
                            authors = listOf(account.pubKeyHex),
                            kinds = listOf(BookmarkListEvent.KIND),
                            limit = 1,
                        ),
                    ),
                relays = connectedRelays,
                onEvent = { event, _, _, _ ->
                    if (event is BookmarkListEvent) {
                        bookmarkList = event
                        bookmarkedEventIds =
                            event
                                .publicBookmarks()
                                .filterIsInstance<EventBookmark>()
                                .map { it.eventId }
                                .toSet()
                    }
                },
                onEose = { _, _ -> },
            )
        } else {
            null
        }
    }

    // Subscribe to the root note — route through cache
    rememberSubscription(connectedRelays, noteId, relayManager = relayManager) {
        if (connectedRelays.isNotEmpty()) {
            createNoteSubscription(
                relays = connectedRelays,
                noteId = noteId,
                onEvent = { event, _, relay, _ ->
                    subscriptionsCoordinator?.consumeEvent(event, relay)
                    if (event.id == noteId) {
                        levelCache[event.id] = 0
                        refreshReplies()
                    }
                },
                onEose = { _, _ ->
                    rootNoteEoseReceived = true
                    refreshReplies()
                },
            )
        } else {
            null
        }
    }

    // Subscribe to replies — route through cache
    rememberSubscription(connectedRelays, noteId, relayManager = relayManager) {
        if (connectedRelays.isNotEmpty()) {
            createThreadRepliesSubscription(
                relays = connectedRelays,
                noteId = noteId,
                onEvent = { event, _, relay, _ ->
                    subscriptionsCoordinator?.consumeEvent(event, relay)
                    refreshReplies()
                },
                onEose = { _, _ ->
                    repliesEoseReceived = true
                    refreshReplies()
                },
            )
        } else {
            null
        }
    }

    // Calculate reply level for a Note
    fun calculateLevel(note: Note): Int {
        val event = note.event ?: return 1
        levelCache[event.id]?.let { return it }

        val replyToId = findReplyToId(event)
        val level =
            if (replyToId == null || replyToId == noteId) {
                1
            } else {
                (levelCache[replyToId] ?: 0) + 1
            }
        levelCache[event.id] = level
        return level
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header with back button
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Thread",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        val rootNoteVal = rootNote
        if (connectedRelays.isEmpty()) {
            LoadingState("Connecting to relays...")
        } else if (rootNoteVal == null && !rootNoteEoseReceived) {
            LoadingState("Loading thread...")
        } else if (rootNoteVal == null && rootNoteEoseReceived) {
            EmptyState(
                title = "Note not found",
                description = "This note may have been deleted or is not available from connected relays",
                onRefresh = onBack,
                refreshLabel = "Go back",
            )
        } else if (rootNoteVal != null) {
            val rootEvent = rootNoteVal.event
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                // Root note
                item(key = noteId) {
                    Column(modifier = Modifier.clickable { }) {
                        if (rootEvent != null) {
                            NoteCard(
                                note = rootEvent.toNoteDisplayData(localCache),
                                onAuthorClick = onNavigateToProfile,
                            )
                        }
                        if (account != null && rootEvent != null) {
                            NoteActionsRow(
                                event = rootEvent,
                                relayManager = relayManager,
                                localCache = localCache,
                                account = account,
                                nwcConnection = nwcConnection,
                                onReplyClick = { onReply(rootEvent) },
                                onZapFeedback = onZapFeedback,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                zapCount = rootNoteVal.zaps.size,
                                zapAmountSats = rootNoteVal.zapsAmount.toLong(),
                                reactionCount = rootNoteVal.countReactions(),
                                replyCount = rootNoteVal.replies.size,
                                repostCount = rootNoteVal.boosts.size,
                                bookmarkList = bookmarkList,
                                isBookmarked = bookmarkedEventIds.contains(noteId),
                                onBookmarkChanged = { newList ->
                                    bookmarkList = newList
                                    bookmarkedEventIds =
                                        newList
                                            .publicBookmarks()
                                            .filterIsInstance<EventBookmark>()
                                            .map { it.eventId }
                                            .toSet()
                                },
                            )
                        }
                    }
                    HorizontalDivider(thickness = 1.dp)
                }

                // Reply notes with level indicators
                items(replyNotes, key = { it.idHex }) { replyNote ->
                    val level = calculateLevel(replyNote)
                    val replyEvent = replyNote.event ?: return@items

                    Column(
                        modifier =
                            Modifier
                                .drawReplyLevel(
                                    level = level,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    selected =
                                        if (replyNote.idHex == noteId) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        },
                                ).clickable {
                                    onNavigateToThread(replyNote.idHex)
                                },
                    ) {
                        NoteCard(
                            note = replyEvent.toNoteDisplayData(localCache),
                            onAuthorClick = onNavigateToProfile,
                        )
                        if (account != null) {
                            NoteActionsRow(
                                event = replyEvent,
                                relayManager = relayManager,
                                localCache = localCache,
                                account = account,
                                nwcConnection = nwcConnection,
                                onReplyClick = { onReply(replyEvent) },
                                onZapFeedback = onZapFeedback,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                zapCount = replyNote.zaps.size,
                                zapAmountSats = replyNote.zapsAmount.toLong(),
                                reactionCount = replyNote.countReactions(),
                                replyCount = replyNote.replies.size,
                                repostCount = replyNote.boosts.size,
                                bookmarkList = bookmarkList,
                                isBookmarked = bookmarkedEventIds.contains(replyNote.idHex),
                                onBookmarkChanged = { newList ->
                                    bookmarkList = newList
                                    bookmarkedEventIds =
                                        newList
                                            .publicBookmarks()
                                            .filterIsInstance<EventBookmark>()
                                            .map { it.eventId }
                                            .toSet()
                                },
                            )
                        }
                    }
                    HorizontalDivider(thickness = 1.dp)
                }

                // Empty state for no replies
                if (replyNotes.isEmpty() && repliesEoseReceived) {
                    item {
                        Spacer(Modifier.height(32.dp))
                        Text(
                            "No replies yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else if (replyNotes.isEmpty() && !repliesEoseReceived) {
                    item {
                        Spacer(Modifier.height(32.dp))
                        Text(
                            "Loading replies...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Recursively collects all replies from a Note's reply graph. */
private fun collectReplies(
    note: Note,
    result: MutableList<Note>,
) {
    for (reply in note.replies) {
        if (reply !in result) {
            result.add(reply)
            collectReplies(reply, result)
        }
    }
}

/**
 * Finds the event ID this event is replying to.
 * Uses NIP-10 markers (reply/root) or falls back to last e-tag.
 */
private fun findReplyToId(event: Event): String? {
    val eTags = event.tags.filter { it.size >= 2 && it[0] == "e" }
    if (eTags.isEmpty()) return null

    val replyTag = eTags.find { it.size >= 4 && it[3] == "reply" }
    if (replyTag != null) return replyTag[1]

    val rootTag = eTags.find { it.size >= 4 && it[3] == "root" }
    if (rootTag != null && eTags.size == 1) return rootTag[1]

    return eTags.lastOrNull()?.get(1)
}
