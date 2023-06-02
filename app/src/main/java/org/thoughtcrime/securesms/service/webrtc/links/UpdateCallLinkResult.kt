/**
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.service.webrtc.links

/**
 * Result type for call link updates.
 */
sealed interface UpdateCallLinkResult {
  data class Success(
    val state: SignalCallLinkState
  ) : UpdateCallLinkResult

  class Failure(
    val status: Short
  ) : UpdateCallLinkResult

  object NotAuthorized : UpdateCallLinkResult
}
