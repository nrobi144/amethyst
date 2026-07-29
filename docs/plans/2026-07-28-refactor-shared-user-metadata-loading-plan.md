---
title: Extract Android's per-user metadata subscription system to commons + adopt on Desktop
type: refactor
status: active
date: 2026-07-28
---

# Extract Android's per-user metadata subscription system to commons + adopt on Desktop

## Enhancement Summary (deepened 2026-07-28)

Deepened with 5 parallel agents (reactions-coupling audit, KMP-placement check, exhaustive Desktop
cutover inventory, architecture review, simplicity review). Key corrections folded in:

1. **Account seam corrected** — a dedicated narrow `UserFinderAccount` interface, NOT widening the fat
   `IAccount`. The 4 sub-assemblers read `trustProviderList` + `declaredFollowsPerOutboxRelay`, neither
   of which exists on `DesktopIAccount` → interface fields are nullable/empty-tolerant (Desktop degrades).
   My original 5-field draft was factually incomplete; Phase 1 derives the exact union by compiling.
2. **Reactions extraction sequenced after metadata** (it structurally depends on `UserFinderFilterAssembler`).
   Metadata end-to-end is the first testable milestone.
3. **Desktop cutover is large: 149+ read sites across 29 files** (`.profilePicture()`,
   `.toBestDisplayName()`, `countReactions()`, `.zaps`…). Metadata-only is ~76 sites/~25 files. Phase 3
   splits 3a (add per-row subscription, keep viewport path) → 3b (remove viewport path) for rollback.
4. **New hard gates:** `verifyKmpPurity` (iOS — commons builds for iosArm64/iosSimulatorArm64: no
   `@Volatile`/`@Synchronized`/`System.currentTimeMillis`/`java.util.UUID` in moved files); assemblers
   stay **CLI-safe** (no Compose-UI imports, only `@Stable`); `:napplet` reachability guard; account-switch
   `clear()`; two-instance-per-pubkey dedup survives the move. `EOSEAccountFast`
   (`amethyst/service/relays/EOSE.kt`) is amethyst-only → must move to commons too.
5. **`NoteActions.kt` TODO** is at ~1538–1543 and fetches a **lightning address on-demand**, not kind-0 —
   weak fit for `observeUser*`; handle in the later zap/reactions work, not the metadata cutover.

## Overview

Amethyst currently has **two divergent ways** of loading user metadata (Nostr kind 0 — display names,
avatars, banners, about text):

- **Android** (`amethyst/`) — a mature **per-user, composition-scoped** system: an `observeUser*(user)`
  composable subscribes to that user's metadata when it enters composition and unsubscribes (with a 30s
  grace window) when it leaves. Subscriptions from every on-screen user are **coalesced into one batched
  REQ** by `UserFinderFilterAssembler`. Because a `LazyColumn` only composes visible items (+ a small
  prefetch buffer), this already behaves as "load metadata only for users that are on screen" — with no
  manual viewport plumbing.
- **Desktop** (`desktopApp/`) — a bespoke **feed-level viewport-batch** system: `FeedScreen` reads
  `listState.layoutInfo.visibleItemsInfo` via `snapshotFlow`, debounces 500ms, and calls
  `FeedMetadataCoordinator.loadMetadataBatched(authors)`. Load-once, never unsubscribes. Single avatars
  outside feeds (dialogs, sidebars, thread headers) don't reliably gate on visibility — and
  `NoteActions.kt:1501` carries a literal `// TODO: Use UserFinderFilterAssemblerSubscription pattern from Amethyst`.

**We are keeping the Android model** (per-user, composition-scoped, batched) and making it the shared
mechanism: extract it to `commons/` and adopt it on Desktop, retiring Desktop's bespoke viewport-batch
path for metadata. This gives Desktop "load only when the user is visible" for free — everywhere a user
is rendered, not just in the main feed — and deletes a parallel code path.

**Why this is far less risky than it looks:** the three hardest coupling points already have commons
seams in place (discovered during research — see the audit below). The bulk of the work is *retyping
against interfaces that already exist* and *moving files*, not rewriting logic.

## Problem Statement / Motivation

- **Duplication & drift.** Two metadata pipelines, two mental models, two sets of bugs. Desktop's path
  lacks the outbox-relay discovery, the reports/cards sub-loaders, and the EOSE-per-user widening logic
  the Android path has.
- **Desktop under-loads outside feeds.** The per-composable pattern loads metadata for *any* on-screen
  user (avatars in dialogs, participant lists, thread headers, search rows). Desktop's feed-only viewport
  path misses these — hence the standing TODO.
- **Desktop over-loads inside feeds relative to visibility semantics.** `loadMetadataBatched` marks
  pubkeys as queued-forever; it never reacts to a user leaving the viewport, and its "visible ± 10"
  buffer is a hand-rolled approximation of what `LazyColumn` composition already gives us.
- **The user explicitly prefers the Android design.** It is idiomatic Compose (`DisposableEffect` +
  `LocalLifecycleOwner`), and the batching assembler already solves the "don't fan out N REQs" concern
  that motivated the Desktop batch path in the first place.

## Proposed Solution (high level)

Move the per-user subscription machinery from `amethyst/service/relayClient/reqCommand/user/` into
`commons/relayClient/`, decoupling it from the three Android-only types it references, then point both
Android and Desktop composables at the shared `observeUser*` helpers.

The shared primitive stack (top → bottom):

```
observeUserInfo/Picture/Name/Banner/AboutMe(user, userFinder, account)   ← commons composables
   └─ UserFinderFilterAssemblerSubscription(user, userFinder, account)
        └─ LifecycleAwareKeyDataSourceSubscription(state, dataSource)     ← ALREADY in commons
             └─ UserFinderFilterAssembler : ComposeSubscriptionManager<UserFinderQueryState>  ← move to commons
                  ├─ UserOutboxFinderSubAssembler   ┐
                  ├─ UserWatcherSubAssembler        │ move to commons,
                  ├─ UserReportsSubAssembler        │ retype cache: LocalCache → ICacheProvider
                  └─ UserCardsSubAssembler          ┘ retype account: Account → IAccount
```

## Research Findings — the coupling audit

### Current metadata-loading call sites (the "review all metadata loading" ask)

| System | Where | Scope | Visibility semantics |
|--------|-------|-------|----------------------|
| `observeUser*` → `UserFinderFilterAssembler` | `amethyst/service/relayClient/reqCommand/user/` (`UserObservers.kt`, `UserFinderFilterAssembler*.kt`) | Android-only | **Composition-scoped** (subscribe on enter, 30s-grace unsubscribe on leave); batched |
| `FeedMetadataCoordinator` (`loadMetadataForNotes` / `loadMetadataBatched` / `loadKind3Batched`) | `commons/relayClient/assemblers/FeedMetadataCoordinator.kt` | Shared, but only Desktop wires it | **Load-once**, viewport-triggered on Desktop only; never unsubscribes |
| `MetadataFilterAssembler` (`SingleSubEoseManager`) | `commons/relayClient/assemblers/MetadataFilterAssembler.kt` | Shared | Long-lived batched kind-0 sub for a pubkey set |
| `MetadataPreloader` + `MetadataRateLimiter` | `commons/relayClient/preload/` | Shared | 20/sec rate-limited background warmer |
| `DesktopRelaySubscriptionsCoordinator` (wraps `FeedMetadataCoordinator`, `MetadataPreloader`, `OutboxDispatcher`) | `desktopApp/subscriptions/DesktopRelaySubscriptionsCoordinator.kt` | Desktop-only | Routes to `DesktopLocalCache.consume` |
| `FeedScreen` `visibleItemsInfo → loadMetadataBatched` | `desktopApp/ui/FeedScreen.kt:938-954` | Desktop-only | The hand-rolled viewport gate we intend to retire for metadata |
| `createMetadata*Subscription` helpers | `desktopApp/subscriptions/ProfileSubscription.kt` | Desktop-only | Ad-hoc single/batch kind-0 subs |

30 files in `amethyst/` call the metadata observers (`observeUserInfo/Picture/Name/Banner/AboutMe`).

### The three coupling points — and the seam that already exists for each

| # | Android coupling | Used for | Existing commons seam | Action |
|---|------------------|----------|-----------------------|--------|
| 1 | `cache: com.vitorpamplona.amethyst.model.LocalCache` in all 4 sub-assemblers | only `cache.getUserIfExists(pubkey)` | **`ICacheProvider`** (`commons/model/cache/ICacheProvider.kt`) — declares `getUserIfExists`; implemented by **both** `LocalCache` *and* `DesktopLocalCache` | Retype `LocalCache` → `ICacheProvider`. Mechanical. |
| 2 | `User` (`amethyst.model.User`) | model | `amethyst.model.User` is a **`typealias`** for `commons.model.User` | Nothing — already shared. |
| 3a | `pickRelaysToLoadUsers(users, accounts: Collection<Account>, …)` | outbox relay selection | A **second, `Account`-free overload already exists** (`FilterFindFollowMetadataForKey.kt:81`) taking plain relay sets (`indexRelays`, `homeRelays`, `searchRelays`, `connected`, `commonRelays`, `cannotConnect`, `hasTried`) | Move the pure overload to commons; keep the `Account` adapter overload Android-side (or reimplement thinly per platform). |
| 3b | `account: com.vitorpamplona.amethyst.model.Account` in `UserFinderQueryState` + `UserCardsSubAssembler` (`homeRelays`, `userProfile().pubkeyHex`, `relayUrl`, `pubkey`); `UserWatcherSubAssembler` (`indexerRelayList`) | relay hints | **`IAccount`** (`commons/model/IAccount.kt`) — implemented by `amethyst.Account` and `DesktopIAccount`, but **does not yet expose the relay-hint fields** | Widen `IAccount` with the relay-hint surface the sub-assemblers read; implement on both accounts. **This is the one genuinely new interface work.** |

Supporting facts:
- `LifecycleAwareKeyDataSourceSubscription` (the DisposableEffect + `LocalLifecycleOwner` primitive) is
  **already in commons** and already used on both platforms — it works on Desktop.
- `EOSEAccountFast`, `DefaultIndexerRelayList`, `DefaultSearchRelayList`, `RelayOfflineTracker`,
  `groupByRelay`, `SingleSubEoseManager`, `BaseEoseManager` are all already commons/quartz — no extra moves.
- Desktop's `DesktopRelaySubscriptionsCoordinator` already holds `indexRelays`, `localCache`
  (`ICacheProvider`), a `cachedOutbox` accessor, and an `OutboxDispatcher` — i.e. all the ingredients to
  construct a `UserFinderFilterAssembler`; it just never built one.

## Technical Approach

### Where things land (source-set placement)

`LifecycleAwareKeyDataSourceSubscription` proves `commonMain` can host `@Composable` relay-subscription
code (it depends on `androidx.lifecycle.compose.LocalLifecycleOwner`, available on Android + JVM). So the
whole per-user stack goes to:

```
commons/src/commonMain/kotlin/com/vitorpamplona/amethyst/commons/relayClient/
  user/
    UserFinderQueryState.kt          (new — { user: User, account: IAccount })
    UserFinderFilterAssembler.kt     (moved)
    UserFinderFilterAssemblerSubscription.kt  (moved; reparam’d)
    UserObservers.kt                 (moved — metadata subset only, see scope)
    loaders/UserOutboxFinderSubAssembler.kt   (moved; cache→ICacheProvider)
    watchers/UserWatcherSubAssembler.kt       (moved; cache→ICacheProvider)
    watchers/UserReportsSubAssembler.kt       (moved; cache→ICacheProvider)
    watchers/UserCardsSubAssembler.kt         (moved; cache→ICacheProvider, account→IAccount)
    watchers/FilterUserMetadataForKey.kt      (moved)
    watchers/FilterReportsToKey.kt            (moved)
    relayPicking/PickRelaysToLoadUsers.kt     (moved — the Account-free overload only)
```

### Scope boundary — which `observeUser*` come over now

`UserObservers.kt` has 21 functions. Only the **pure-metadata** ones are in scope for this refactor
(they read `user.metadata().flow` and just need `userFinder` + `account`):

- **In scope:** `observeUserInfo`, `observeUserPicture`, `observeUserBanner`, `observeUserAboutMe`,
  and the metadata half of `observeUserName` (the display-name-from-metadata fallback).
- **Deferred (need `Account.contactCards` / hashtag / bookmark / pin / trust-provider / status state,
  which are richer account subsystems not yet in commons):** `observeUserNickname`,
  `observeUserContactCardsScore/FollowerCount`, `observeUserTagFollow*`, `observeUserBookmark*`,
  `observeUserPinnedNotesCount`, `observeUserStatuses`, all `observeUserIsFollowing*`. These stay in
  `amethyst/` and keep calling the (now-shared) `UserFinderFilterAssemblerSubscription`. Extracting them
  is a separate follow-up once `IAccount` grows a contact-card surface.

### Decoupling the `observeUser*` signature from `AccountViewModel`

Android's observers take `accountViewModel: AccountViewModel`. Desktop has no such VM. The commons
observers take the two things actually used:

```kotlin
@Composable
fun observeUserInfo(
    user: User,
    userFinder: UserFinderFilterAssembler,
    account: IAccount,
): State<UserInfo?>
```

To avoid threading two extra params through 30+ call sites, provide them via **CompositionLocals** in
commons and keep thin Android/Desktop wrappers:

```kotlin
val LocalUserFinder = staticCompositionLocalOf<UserFinderFilterAssembler> { error("not provided") }
val LocalMetadataAccount = staticCompositionLocalOf<IAccount> { error("not provided") }
```

- Android: an `observeUserInfo(user, accountViewModel)` shim reads
  `accountViewModel.dataSources().userFinder` + `accountViewModel.account` (call sites unchanged).
- Desktop: provide `LocalUserFinder`/`LocalMetadataAccount` once near the app root; call the commons
  `observeUser*` directly (or via a similarly thin Desktop shim).

### Dedicated `UserFinderAccount` interface (coupling 3b — CORRECTED after deepening review)

**Do NOT widen the fat `IAccount`.** `IAccount` is a *behavioral capability* interface for the acting
user (`sendNip17PrivateMessage`, `sendGiftWraps`, `chatroomList`, `marmotGroupList`, `isAcceptable`,
content-filter fields) — bolting metadata-subscription relay hints onto it is an ISP violation. It also
today exposes **zero** relay flows, so "widening" is real work regardless.

**Critical correction (architecture review):** `UserFinderFilterAssembler.group` constructs **all 4**
sub-assemblers unconditionally, so moving the assembler drags all four — and their account reads are
**wider than relay hints** and partly **unbacked on Desktop**:

| Sub-assembler | Reads from account | Desktop backing? |
|---|---|---|
| `UserWatcherSubAssembler` | `indexerRelayList.flow` | yes (nip65/index relays) |
| `UserOutboxFinderSubAssembler` | relay sets via `pickRelaysToLoadUsers` | yes |
| `UserReportsSubAssembler` | **`declaredFollowsPerOutboxRelay`** | **NO — exists nowhere in commons/desktop** |
| `UserCardsSubAssembler` | `homeRelays`, `userProfile().pubkeyHex`, **`trustProviderList.liveUserRankProvider`** | **NO `trustProviderList` on `DesktopIAccount`** |

Introduce a **dedicated, narrow `UserFinderAccount`** in commons — the *actual union* the 4 sub-assemblers
read, with **nullable / empty-tolerant** fields that document exactly where Desktop degrades:

```kotlin
// commons/relayClient/user/UserFinderAccount.kt  — CLI-safe, no Compose-UI imports
interface UserFinderAccount {
    val indexerRelays: StateFlow<Set<NormalizedRelayUrl>>            // Watcher
    val homeRelays:    StateFlow<Set<NormalizedRelayUrl>>            // Cards + outbox picking
    val searchRelays:  StateFlow<Set<NormalizedRelayUrl>>           // outbox picking fallback
    val myPubkey:      HexKey                                        // Cards moderator/self
    val myRelayUrl:    NormalizedRelayUrl                            // Cards
    val declaredFollowsPerOutboxRelay: StateFlow<Map<NormalizedRelayUrl, Set<HexKey>>>  // Reports; Desktop → MutableStateFlow(emptyMap())
    val trustProvider: StateFlow<TrustProvider?>                     // Cards; Desktop → MutableStateFlow(null)
}
```

- `amethyst.Account` implements it by delegating to its existing state objects (behaviour unchanged).
- `DesktopIAccount` implements it, **degrading** `trustProvider`/`declaredFollowsPerOutboxRelay` to
  empty/null. Consequence: Desktop card ranking + report loading are best-effort (acceptable v1 — Desktop
  has no trust-provider subsystem wired; call this out in the PR).
- The pure `pickRelaysToLoadUsers(indexRelays, homeRelays, searchRelays, …)` overload still moves to
  commons and consumes **live snapshots** read from this interface at each filter rebuild (relay lists
  change; a frozen data-class snapshot would go stale — so a flow-based interface, not a snapshot).
- `UserFinderQueryState(user, account: UserFinderAccount)` — multi-account union of relays across the
  distinct accounts in the current key set still works.

> **Exact field list VERIFIED against the fresh tree** (reads are `keys.mapTo{ it.account }` then
> `account.X`, so a `.account.` grep misses them — read the files):
> - `UserWatcherSubAssembler`: `account.indexerRelayList.flow.value`
> - `UserOutboxFinderSubAssembler`: relay sets via `pickRelaysToLoadUsers(...)` (follows/outbox/index/search/connected)
> - `UserCardsSubAssembler`: `account.homeRelays.flow.value`, `account.userProfile().pubkeyHex`,
>   `account.trustProviderList.liveUserRankProvider.value` → `.relayUrl` + `.pubkey`
> - `UserReportsSubAssembler`: `account.declaredFollowsPerOutboxRelay.value`
> Compiling `:amethyst` against the interface is the proof it's complete.

### Newly-surfaced prerequisite — the `amethyst.service.relays` EOSE cluster must move too

The 4 sub-assemblers import `EOSEAccountFast`, `MutableTime`, `SincePerRelayMap` from
`com.vitorpamplona.amethyst.service.relays` (`EOSE.kt`) — **Android-only** infra, and `EOSEAccountFast`
uses `synchronized(lock)` which **fails `verifyKmpPurity`** (iOS forbids `kotlin.synchronized`; use
`KmpLock.withLock {}`). So the move set for Phase 2 grows to include this cluster, KMP-purified. Note
commons already has a **separate** `com.vitorpamplona.amethyst.commons.relays.SincePerRelayMap` — reconcile
(prefer the commons one; don't ship two). This is the single biggest under-estimate in the original plan:
the per-user stack sits on an Android-only EOSE/time substrate that must be relocated + purified first.

### CompositionLocal injection (corrected)

Provide **`LocalUserFinder: UserFinderFilterAssembler`** and **`LocalUserFinderAccount: UserFinderAccount`**
(the *narrow* type, not `IAccount`) via `staticCompositionLocalOf { error("not provided") }`. The commons
`observeUser*` keep **explicit `userFinder` + `account` params** as their real signature; a thin overload
reads the Locals. (Two Locals, because the subscription helper needs the account to build the query state
for the multi-account relay union — but both are narrow types. Android keeps its
`observeUserX(user, accountViewModel)` shims so call sites don't change.)

**Guard:** the `error("not provided")` default is a landmine on the Android **`:napplet`** process and in
previews/detached dialogs. Phase 2/3 must assert no `observeUser*` is reachable from `:napplet`
composition (or provide a no-op finder there).

### The Compose "load only when visible" answer (the user's explicit question)

**Recommendation: composition-scoped subscription (`DisposableEffect` + `LocalLifecycleOwner`) is the
right Compose solution — and it's the one Android already uses.** Rationale:

1. **`LazyColumn`/`LazyRow` compose only the visible window + a small prefetch buffer.** An item that
   scrolls out of the viewport is *disposed*, firing `onDispose` → unsubscribe. So "subscribe in
   `DisposableEffect`" ≈ "subscribe while visible", with **zero** manual `visibleItemsInfo` bookkeeping.
2. **Batching removes the per-item-REQ cost.** `UserFinderFilterAssembler` coalesces every currently
   subscribed user into one REQ (`invalidateKeys` → recompute one filter). Per-composable subscription
   does **not** mean per-composable REQ — which is exactly the concern that birthed Desktop's
   `loadMetadataBatched`. That concern is already solved upstream.
3. **The 30s unsubscribe grace** (already in `LifecycleAwareKeyDataSourceSubscription`) absorbs
   scroll-back and app backgrounding without tearing down and refetching.

**Known nuance — composition ⊋ pixel-visibility.** Items in the prefetch buffer (just past the edge) and
composables inside a *composed-but-hidden* parent (a kept-alive non-selected tab, an off-screen pager
page) stay subscribed. For **metadata** this over-subscription is benign and even desirable: kind 0 is
replaceable, load-once, tiny, and pre-warming the just-off-screen row makes scrolling feel instant. So we
**do not** need true pixel visibility here.

**If pixel-precise visibility is ever required** (it isn't for this task), Compose Foundation
(`androidx.compose.foundation` at BOM `2026.06.00`) offers `Modifier.onLayoutRectChanged` /
`onVisibilityChanged` — cheaper and more correct than `onGloballyPositioned` polling. Note this as the
escape hatch, don't build it now.

### What happens to Desktop's `FeedMetadataCoordinator` viewport path

- **Metadata:** retire the `FeedScreen.kt:938-954` `visibleItemsInfo → loadMetadataBatched(authors)`
  wiring once Desktop note rows call `observeUserInfo/Picture`. The per-row subscription supersedes it.
- **Reactions & quoted/reposted-note prefetch:** `loadMetadataForNotes` *also* enqueues reactions
  (kind 7) and referenced-note (`ids`) fetches. **Keep those** — this refactor is metadata-only. Split
  `loadMetadataForNotes` so the metadata half can be dropped without touching reactions.
- `MetadataPreloader`/`MetadataRateLimiter` stay as the **background warm** path (progressive off-screen
  preloading) — complementary to, not replaced by, composition-scoped foreground loading.

## Implementation Phases

### Phase 1 — Decouple in place (no moves yet), Android stays green
Goal: make the sub-assemblers reference only commons-visible types, while still living in `amethyst/`.
- `UserWatcherSubAssembler`, `UserOutboxFinderSubAssembler`, `UserReportsSubAssembler`,
  `UserCardsSubAssembler`: change `cache: LocalCache` → `cache: ICacheProvider`. *(File: each
  `*SubAssembler.kt`.)*
- Introduce `UserFinderRelayHints` + widen `IAccount`; implement on `amethyst.Account`. Refactor
  `UserCardsSubAssembler` / `UserWatcherSubAssembler` to read hints via `IAccount` instead of concrete
  `Account`.
- Move the `Account`-free `pickRelaysToLoadUsers` overload's body behind a commons-friendly signature;
  have the Android `Account` overload delegate to it.
- **Gate:** `./gradlew :amethyst:compileDebugKotlin :commons:compileKotlinJvm` green; existing Android
  metadata still loads (manual smoke + existing tests).

### Phase 2 — Move the stack to `commons/commonMain`
- Physically move the files listed in *"Where things land"* into
  `commons/.../relayClient/user/`. Update packages/imports.
- `UserFinderQueryState` now `{ user: User, account: IAccount }`.
- Move the in-scope `observeUser*` (metadata subset) into commons; add `LocalUserFinder` /
  `LocalMetadataAccount` CompositionLocals; add Android shims preserving the
  `observeUserX(user, accountViewModel)` call signature.
- **Gate:** `:commons:compileKotlinJvm`, `:commons:compileDebugKotlinAndroid`,
  `:amethyst:compileDebugKotlin` green; `:commons:jvmTest` green. Android app metadata unchanged.

### Phase 3 — Wire Desktop
- Build a `UserFinderFilterAssembler` inside `DesktopRelaySubscriptionsCoordinator` (client +
  `localCache as ICacheProvider` + a `RelayOfflineTracker`).
- Implement `UserFinderRelayHints` on `DesktopIAccount`.
- Provide `LocalUserFinder` / `LocalMetadataAccount` at the Desktop app root (`Main.kt` / `App()`).
- Replace Desktop metadata reads at the source — the standing TODO at `NoteActions.kt:1501`, plus
  `WoTBadgedAvatar.kt`, `UserDisplayNameLayout.kt`, `UserProfileScreen.kt`, thread/search/participant
  avatars — with `observeUserInfo/Picture/Name`.
- Split `FeedMetadataCoordinator.loadMetadataForNotes` into metadata vs reactions; drop the metadata call
  from `FeedScreen` viewport handler (keep reactions/quoted-note prefetch).
- **Gate:** `:desktopApp:compileKotlin` green; run desktop app, verify avatars/names populate on feed,
  profile, thread, search, dialogs — and that they load for on-screen users only.

### Phase 4 — Cleanup & guardrails
- Remove now-dead Desktop helpers superseded by the shared path (`ProfileSubscription.kt`
  `createMetadata*Subscription` if fully unused).
- Add a `commons/jvmTest` covering: (a) sub-assembler builds a single batched kind-0 filter for N
  subscribed users; (b) unsubscribe removes a user from the next filter; (c) `ICacheProvider` seam works
  with a fake cache.
- Update `commons/ARCHITECTURE.md` + `relay-client` skill notes to document the shared `observeUser*`
  entry point as the canonical way to load metadata.
- `./gradlew spotlessApply`.

## System-Wide Impact

- **Interaction graph:** `observeUserPicture` → `UserFinderFilterAssemblerSubscription` →
  `LifecycleAwareKeyDataSourceSubscription` (`ON_START` subscribe / `ON_STOP`+30s unsubscribe) →
  `ComposeSubscriptionManager.invalidateKeys` → 4 sub-assemblers recompute filters →
  `client.subscribe/unsubscribe` → events → `ICacheProvider` consume → `user.metadata().flow` →
  `collectAsStateWithLifecycle` → recompose. Two levels deep the risk is **filter thrash**: rapid
  scroll invalidates keys often; mitigated because assemblers diff against `allKeys()` and REQs only
  change when the *set* changes.
- **Error propagation:** `UserOutboxFinderSubAssembler` has a fallback tier (indexer+search) for pubkeys
  no relay could place — preserve it verbatim across the move (it fixed a real "late kind 10002 never
  reaches UI" bug). Batched EOSE gate + `EOSEAccountFast` retry semantics must survive the move.
- **State lifecycle risks:** Android and Desktop keep **separate** `LocalCache`/`DesktopLocalCache`
  instances (by design — different processes/apps). The shared assembler must receive the *platform's*
  cache via `ICacheProvider`; do not introduce a shared singleton cache.
- **API surface parity:** any agent/CLI (`amy`) path that shows a user must be able to trigger the same
  load — but `amy` is headless (no composition). It already uses `fetchFirst`/`fetchAll` accessories;
  out of scope, note it.
- **Integration scenarios:** (1) scroll a 200-note feed → only ~visible authors' kind-0 REQ'd, one
  batched REQ, off-screen disposed; (2) open a profile of a never-seen user → avatar+name populate;
  (3) open a DM participant sheet → all participant avatars load; (4) background the app mid-scroll →
  subs torn down after 30s, not immediately; (5) account switch → `clear()` resets queued/EOSE state.

## Acceptance Criteria

- [ ] The 4 sub-assemblers reference `ICacheProvider`, not `amethyst.model.LocalCache`.
- [ ] `UserFinderFilterAssembler`, `UserFinderQueryState`, `UserFinderFilterAssemblerSubscription`, and the
      metadata `observeUser*` live in `commons/commonMain`.
- [ ] `UserFinderQueryState` holds `IAccount`; `IAccount` exposes a read-only `UserFinderRelayHints`.
- [ ] Android metadata behaviour is unchanged (same call sites, same visuals) — verified on device/emulator.
- [ ] Desktop loads metadata **per on-screen user** via `observeUser*`: feed rows, profile, thread header,
      search results, DM participant lists, and the `NoteActions.kt:1501` site all populate name+avatar.
- [ ] Desktop's `FeedScreen` no longer drives metadata via `visibleItemsInfo → loadMetadataBatched`;
      reactions/quoted-note prefetch still works.
- [ ] One batched kind-0 REQ per relay for the currently-subscribed user set (not N REQs) — asserted by a
      commons test and observed in the relay log.
- [ ] Off-screen users unsubscribe after the 30s grace (scroll away, confirm REQ set shrinks).
- [ ] `./gradlew :commons:jvmTest :amethyst:testDebugUnitTest` green; `spotlessApply` clean.

## Alternatives Considered

- **Keep both systems, just share more of `FeedMetadataCoordinator`.** Rejected — the user prefers the
  per-user model, and it uniquely covers non-feed avatars.
- **True pixel-visibility via `onVisibilityChanged`/`onLayoutRectChanged` for every avatar.** Rejected as
  over-engineering: composition already approximates visibility, metadata over-fetch is benign, and per-
  avatar layout callbacks add cost for no user-visible benefit. Documented as a future escape hatch only.
- **Big-bang move (skip Phase 1 in-place decoupling).** Rejected — decoupling against interfaces while
  still compiling in `amethyst/` keeps every step green and bisectable.

## Sources & References

- Android per-user system: `amethyst/.../reqCommand/user/UserObservers.kt`,
  `UserFinderFilterAssembler.kt`, `UserFinderFilterAssemblerSubscription.kt`,
  `watchers/UserWatcherSubAssembler.kt`, `watchers/UserCardsSubAssembler.kt`,
  `loaders/UserOutboxFinderSubAssembler.kt`, `account/follows/FilterFindFollowMetadataForKey.kt`.
- Commons seams: `commons/.../model/cache/ICacheProvider.kt`, `commons/.../model/IAccount.kt`,
  `commons/.../relayClient/subscriptions/LifecycleAwareKeyDataSourceSubscription.kt`,
  `commons/.../relayClient/composeSubscriptionManagers/ComposeSubscriptionManager.kt`,
  `commons/.../model/User.kt` (metadata), `amethyst/.../model/User.kt` (typealias).
- Desktop side: `desktopApp/.../subscriptions/DesktopRelaySubscriptionsCoordinator.kt`,
  `desktopApp/.../model/DesktopIAccount.kt`, `desktopApp/.../cache/DesktopLocalCache.kt`,
  `desktopApp/.../ui/FeedScreen.kt:938-954`, `desktopApp/.../ui/NoteActions.kt:1501` (TODO).
- Prior brainstorm (background, not the origin): `docs/brainstorms/2026-04-29-feed-metadata-loading-optimization-brainstorm.md`
  and `docs/plans/2026-04-29-perf-viewport-aware-metadata-loading-plan.md` — the Desktop viewport-batch
  path this refactor supersedes for metadata.
- Related skills: `relay-client`, `account-state`, `compose-side-effects`, `kotlin-multiplatform`.

## Resolved Decisions (user directives, 2026-07-28)

The guiding principle for this work: **do it properly even at higher effort, and maximize sharing —
port the Android logic into `commons/` and reuse it on Desktop rather than reimplement.** Concretely:

1. **Widen `IAccount`** with a read-only `UserFinderRelayHints` surface (chosen over a snapshot param).
2. **Reactions move to per-row composition too**, on *both* platforms — not just metadata. This pulls
   the Android per-note subscription family (`EventFinderFilterAssembler` /
   `EventFinderFilterAssemblerSubscription` / `observeNote*` reaction/zap/repost counters) into the same
   extraction. Desktop's `FeedScreen` `visibleItemsInfo → loadMetadataBatched`/reactions viewport wiring
   is **fully retired**; per-row composition owns both metadata and reactions. `MetadataPreloader` /
   `MetadataRateLimiter` remain only as an optional off-screen background warmer.
3. **`observeUserName`:** ship a metadata-only `observeUserName` in commons now; Android keeps a thin
   wrapper that layers the contact-card petname on top. Desktop uses the metadata-only version.
4. **Desktop relay hints:** implement `UserFinderRelayHints` on `DesktopIAccount` from whatever relay
   sets it has; degrade gracefully to indexer relays when home/search are empty (the pure
   `pickRelaysToLoadUsers` overload already tolerates empty tiers). Investigate & wire the best available.
5. **`ProfileSubscription.kt` `createMetadata*Subscription`:** delete once confirmed unused after the
   per-row cutover.
6. **Delivery:** one worktree branch, **phased commits** (one per phase), kept green at every gate, so the
   user can test the whole thing at the end. Not split into separate PRs.

## Scope Addendum — reactions per-row (decision #2)

Extend the extraction to the per-**note** subscription layer so reactions/zaps/reposts also load only
for visible notes:

- **Android source of truth:** `amethyst/service/relayClient/reqCommand/event/EventFinderFilterAssembler.kt`
  (+ `EventFinderFilterAssemblerSubscription`, its `loaders/`, and the `observeNote*` counters in the
  note UI). It already composes with `UserFinderFilterAssembler` (line references it via
  `AddressableAuthorRelayLoaderSubAssembler(cache, ::allKeys, userFinder)`), so both must move together.
- **Same three seams** as the user path: `cache: LocalCache → ICacheProvider`, `account → IAccount`,
  `User` already shared. Verify no *additional* Android-only coupling (event-kind observers may touch
  more `Account` state — audit during Phase 1).
- **Desktop cutover:** note rows call the shared `observeNote*`/`EventFinderFilterAssemblerSubscription`;
  the `snapshotFlow(visibleItemsInfo)` blocks in `FeedScreen.kt` (~L999–1010, L764, L1982) are removed.

## Open Questions (remaining, non-blocking)

1. **Depth of `EventFinderFilterAssembler` account coupling.** If the per-note loaders read more of
   `Account` than the relay-hint surface (e.g. mute/spam filtering, DM inbox resolution), decide per case:
   widen `IAccount` further vs keep that specific loader Android-side behind an interface. Audited in
   Phase 1; may expand `UserFinderRelayHints` into a broader `SubscriptionAccount` interface.
2. **Non-metadata `observeUser*` (contact cards, bookmarks, follows, statuses).** Still deferred — they
   need account subsystems not yet in commons. They keep calling the now-shared subscription entry point;
   full extraction is a later follow-up.
3. **iOS source set.** `commons` builds for iOS; confirm the moved `@Composable` + lifecycle code compiles
   for iOS if that target is active in the build (the lifecycle primitive is already KMP).
