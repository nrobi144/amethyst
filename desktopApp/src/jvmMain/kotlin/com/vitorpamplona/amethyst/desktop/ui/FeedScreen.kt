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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vitorpamplona.amethyst.commons.model.Note
import com.vitorpamplona.amethyst.commons.ui.components.EmptyState
import com.vitorpamplona.amethyst.commons.ui.components.LoadingState
import com.vitorpamplona.amethyst.desktop.DesktopPreferences
import com.vitorpamplona.amethyst.desktop.account.AccountState
import com.vitorpamplona.amethyst.desktop.cache.DesktopLocalCache
import com.vitorpamplona.amethyst.desktop.network.DesktopRelayConnectionManager
import com.vitorpamplona.amethyst.desktop.subscriptions.DesktopRelaySubscriptionsCoordinator
import com.vitorpamplona.amethyst.desktop.subscriptions.FeedMode
import com.vitorpamplona.amethyst.desktop.subscriptions.FilterBuilders
import com.vitorpamplona.amethyst.desktop.subscriptions.SubscriptionConfig
import com.vitorpamplona.amethyst.desktop.subscriptions.createContactListSubscription
import com.vitorpamplona.amethyst.desktop.subscriptions.createFollowingFeedSubscription
import com.vitorpamplona.amethyst.desktop.subscriptions.createGlobalFeedSubscription
import com.vitorpamplona.amethyst.desktop.subscriptions.rememberSubscription
import com.vitorpamplona.amethyst.desktop.ui.note.NoteCard
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip51Lists.bookmarkList.BookmarkListEvent
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class LightboxState(
    val urls: List<String>,
    val index: Int,
    val seekPosition: Float = 0f,
    val fullscreen: Boolean = false,
)

/**
 * Note card that reads counts from the Note model (cache-backed).
 * No longer requires per-screen zap/reaction/reply state maps.
 */
@Composable
fun FeedNoteCard(
    note: Note,
    relayManager: DesktopRelayConnectionManager,
    localCache: DesktopLocalCache,
    account: AccountState.LoggedIn?,
    nwcConnection: com.vitorpamplona.quartz.nip47WalletConnect.Nip47WalletConnect.Nip47URINorm? = null,
    onReply: () -> Unit,
    onZapFeedback: (ZapFeedback) -> Unit,
    onNavigateToProfile: (String) -> Unit = {},
    onNavigateToThread: (String) -> Unit = {},
    bookmarkList: BookmarkListEvent? = null,
    isBookmarked: Boolean = false,
    onBookmarkChanged: (BookmarkListEvent) -> Unit = {},
) {
    val event = note.event ?: return

    Column(
        modifier =
            Modifier.clickable {
                onNavigateToThread(event.id)
            },
    ) {
        NoteCard(
            note = event.toNoteDisplayData(localCache),
            onAuthorClick = onNavigateToProfile,
        )

        // Action buttons (only if logged in)
        if (account != null) {
            NoteActionsRow(
                event = event,
                relayManager = relayManager,
                localCache = localCache,
                account = account,
                nwcConnection = nwcConnection,
                onReplyClick = onReply,
                onZapFeedback = onZapFeedback,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                zapCount = note.zaps.size,
                zapAmountSats = note.zapsAmount.toLong(),
                zapReceipts = emptyList(), // ZapReceipt conversion deferred
                reactionCount = note.countReactions(),
                replyCount = note.replies.size,
                repostCount = note.boosts.size,
                bookmarkList = bookmarkList,
                isBookmarked = isBookmarked,
                onBookmarkChanged = onBookmarkChanged,
            )
        }
    }
}

@Composable
fun FeedScreen(
    relayManager: DesktopRelayConnectionManager,
    localCache: DesktopLocalCache,
    account: AccountState.LoggedIn? = null,
    nwcConnection: com.vitorpamplona.quartz.nip47WalletConnect.Nip47WalletConnect.Nip47URINorm? = null,
    subscriptionsCoordinator: DesktopRelaySubscriptionsCoordinator? = null,
    initialFeedMode: FeedMode? = null,
    onCompose: () -> Unit = {},
    onNavigateToProfile: (String) -> Unit = {},
    onNavigateToThread: (String) -> Unit = {},
    onZapFeedback: (ZapFeedback) -> Unit = {},
) {
    val connectedRelays by relayManager.connectedRelays.collectAsState()
    val configuredRelays by remember {
        relayManager.relayStatuses
            .map { it.keys }
            .distinctUntilChanged()
    }.collectAsState(emptySet())
    val scope = rememberCoroutineScope()

    var replyToEvent by remember { mutableStateOf<Event?>(null) }
    var feedMode by remember { mutableStateOf(initialFeedMode ?: DesktopPreferences.feedMode) }
    var followedUsers by remember { mutableStateOf<Set<String>>(emptySet()) }
    var bookmarkList by remember { mutableStateOf<BookmarkListEvent?>(null) }
    var bookmarkedEventIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Track EOSE to know when initial load is complete
    var eoseReceivedCount by remember { mutableStateOf(0) }
    val initialLoadComplete = eoseReceivedCount > 0

    // Get notes from cache — filtered by kind 1, sorted by createdAt desc
    // This is a simple approach: query cache on each recomposition.
    // The FeedViewModel approach (Phase 3 full) would make this reactive via event stream.
    val feedNotes =
        remember(localCache.notes.size(), feedMode, followedUsers, eoseReceivedCount) {
            val allNotes = localCache.notes.filterIntoSet { _, note -> note.event?.kind == 1 }
            val filtered =
                if (feedMode == FeedMode.FOLLOWING && followedUsers.isNotEmpty()) {
                    allNotes.filter { it.author?.pubkeyHex in followedUsers }
                } else {
                    allNotes.toList()
                }
            filtered.sortedByDescending { it.createdAt() ?: 0 }.take(2500)
        }

    // Load followed users for Following feed mode
    rememberSubscription(configuredRelays, account, feedMode, relayManager = relayManager) {
        if (configuredRelays.isNotEmpty() && account != null && feedMode == FeedMode.FOLLOWING) {
            createContactListSubscription(
                relays = configuredRelays,
                pubKeyHex = account.pubKeyHex,
                onEvent = { event, _, _, _ ->
                    if (event is ContactListEvent) {
                        followedUsers = event.verifiedFollowKeySet()
                    }
                },
            )
        } else {
            null
        }
    }

    // Load user's bookmark list
    rememberSubscription(configuredRelays, account, relayManager = relayManager) {
        if (configuredRelays.isNotEmpty() && account != null) {
            SubscriptionConfig(
                subId = "bookmarks-${account.pubKeyHex.take(8)}",
                filters =
                    listOf(
                        FilterBuilders.byAuthors(
                            authors = listOf(account.pubKeyHex),
                            kinds = listOf(BookmarkListEvent.KIND),
                            limit = 1,
                        ),
                    ),
                relays = configuredRelays,
                onEvent = { event, _, _, _ ->
                    if (event is BookmarkListEvent) {
                        bookmarkList = event
                        val pubIds =
                            event
                                .publicBookmarks()
                                .filterIsInstance<com.vitorpamplona.quartz.nip51Lists.bookmarkList.tags.EventBookmark>()
                                .map { it.eventId }
                                .toSet()
                        bookmarkedEventIds = pubIds
                    }
                },
                onEose = { _, _ -> },
            )
        } else {
            null
        }
    }

    // Reset EOSE when feed mode changes
    remember(feedMode) {
        eoseReceivedCount = 0
    }

    // Subscribe to feed — route ALL events through cache via coordinator
    rememberSubscription(configuredRelays, feedMode, followedUsers, relayManager = relayManager) {
        if (configuredRelays.isEmpty()) {
            return@rememberSubscription null
        }

        when (feedMode) {
            FeedMode.GLOBAL -> {
                createGlobalFeedSubscription(
                    relays = configuredRelays,
                    onEvent = { event, _, relay, _ ->
                        subscriptionsCoordinator?.consumeEvent(event, relay)
                    },
                    onEose = { _, _ ->
                        eoseReceivedCount++
                    },
                )
            }

            FeedMode.FOLLOWING -> {
                if (followedUsers.isNotEmpty()) {
                    createFollowingFeedSubscription(
                        relays = configuredRelays,
                        followedUsers = followedUsers.toList(),
                        onEvent = { event, _, relay, _ ->
                            subscriptionsCoordinator?.consumeEvent(event, relay)
                        },
                        onEose = { _, _ ->
                            eoseReceivedCount++
                        },
                    )
                } else {
                    null
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    Column(modifier = Modifier.fillMaxSize()) {
        // Header with compose button — wraps on narrow columns
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column {
                FlowRow(
                    verticalArrangement = Arrangement.Center,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (feedMode == FeedMode.GLOBAL) "Global Feed" else "Following Feed",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )

                    // Feed mode selector
                    if (account != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(
                                selected = feedMode == FeedMode.GLOBAL,
                                onClick = {
                                    feedMode = FeedMode.GLOBAL
                                    DesktopPreferences.feedMode = FeedMode.GLOBAL
                                },
                                label = { Text("Global") },
                            )
                            FilterChip(
                                selected = feedMode == FeedMode.FOLLOWING,
                                onClick = {
                                    feedMode = FeedMode.FOLLOWING
                                    DesktopPreferences.feedMode = FeedMode.FOLLOWING
                                },
                                label = { Text("Following") },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${connectedRelays.size} relays connected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (feedMode == FeedMode.FOLLOWING) {
                        Text(
                            " • ${followedUsers.size} followed",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { relayManager.connect() },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // New Post button (primary action)
            Button(
                onClick = onCompose,
                enabled = account != null && !account.isReadOnly,
            ) {
                Icon(Icons.Default.Add, "New Post", Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("New Post")
            }
        }

        Spacer(Modifier.height(8.dp))

        if (connectedRelays.isEmpty()) {
            LoadingState("Connecting to relays...")
        } else if (feedMode == FeedMode.FOLLOWING && followedUsers.isEmpty()) {
            LoadingState("Loading followed users...")
        } else if (feedNotes.isEmpty() && !initialLoadComplete) {
            LoadingState("Loading notes...")
        } else if (feedNotes.isEmpty() && initialLoadComplete) {
            EmptyState(
                title =
                    if (feedMode == FeedMode.FOLLOWING) {
                        "No notes from followed users"
                    } else {
                        "No notes found"
                    },
                description =
                    if (feedMode == FeedMode.FOLLOWING) {
                        "Notes from people you follow will appear here"
                    } else {
                        "Notes from the network will appear here"
                    },
                onRefresh = { relayManager.connect() },
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(feedNotes, key = { it.idHex }) { note ->
                    FeedNoteCard(
                        note = note,
                        relayManager = relayManager,
                        localCache = localCache,
                        account = account,
                        nwcConnection = nwcConnection,
                        onReply = { replyToEvent = note.event },
                        onZapFeedback = onZapFeedback,
                        onNavigateToProfile = onNavigateToProfile,
                        onNavigateToThread = onNavigateToThread,
                        bookmarkList = bookmarkList,
                        isBookmarked = bookmarkedEventIds.contains(note.idHex),
                        onBookmarkChanged = { newList ->
                            bookmarkList = newList
                            val pubIds =
                                newList
                                    .publicBookmarks()
                                    .filterIsInstance<com.vitorpamplona.quartz.nip51Lists.bookmarkList.tags.EventBookmark>()
                                    .map { it.eventId }
                                    .toSet()
                            bookmarkedEventIds = pubIds
                        },
                    )
                }
            }
        }

        // Reply dialog
        if (replyToEvent != null && account != null) {
            ComposeNoteDialog(
                onDismiss = { replyToEvent = null },
                relayManager = relayManager,
                account = account,
                replyTo = replyToEvent,
            )
        }
    }
}
