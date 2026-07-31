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
package com.vitorpamplona.amethyst.service.relayClient.reqCommand.event

import androidx.compose.runtime.Composable
import com.vitorpamplona.amethyst.model.Note
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel

// The per-note event-finder subscription assembler + its query state moved to
// commons.relayClient.event so Desktop (and any KMP front end) can reuse them.
// These aliases + the AccountViewModel overload keep the many existing Android
// call sites — which still import from this package — compiling unchanged.
typealias EventFinderFilterAssembler = com.vitorpamplona.amethyst.commons.relayClient.event.EventFinderFilterAssembler
typealias EventFinderQueryState = com.vitorpamplona.amethyst.commons.relayClient.event.EventFinderQueryState

/**
 * Android convenience overload: unpacks the [AccountViewModel] into the narrow
 * account seam ([Account][com.vitorpamplona.amethyst.model.Account] implements
 * `UserFinderAccount`) and the shared event-finder data source, then delegates
 * to the commons subscription.
 */
@Composable
fun EventFinderFilterAssemblerSubscription(
    note: Note,
    accountViewModel: AccountViewModel,
) = com.vitorpamplona.amethyst.commons.relayClient.event.EventFinderFilterAssemblerSubscription(
    note = note,
    account = accountViewModel.account,
    dataSource = accountViewModel.dataSources().eventFinder,
)
