/**
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.calls.links

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.signal.core.ui.Buttons
import org.signal.core.ui.theme.SignalTheme
import org.signal.ringrtc.CallLinkRootKey
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.conversation.colors.AvatarColor
import org.thoughtcrime.securesms.conversation.colors.AvatarColorPair
import org.thoughtcrime.securesms.database.CallLinkTable
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.service.webrtc.links.CallLinkCredentials
import org.thoughtcrime.securesms.service.webrtc.links.CallLinkRoomId
import org.thoughtcrime.securesms.service.webrtc.links.SignalCallLinkState
import java.time.Instant

@Preview
@Composable
private fun SignalCallRowPreview() {
  val avatarColor = remember { AvatarColor.random() }
  val callLink = remember {
    val credentials = CallLinkCredentials.generate()
    CallLinkTable.CallLink(
      recipientId = RecipientId.UNKNOWN,
      roomId = CallLinkRoomId.fromCallLinkRootKey(CallLinkRootKey(credentials.linkKeyBytes)),
      credentials = credentials,
      state = SignalCallLinkState(
        name = "Call Name",
        restrictions = org.signal.ringrtc.CallLinkState.Restrictions.NONE,
        expiration = Instant.MAX,
        revoked = false
      ),
      avatarColor = avatarColor
    )
  }
  SignalTheme(false) {
    SignalCallRow(
      callLink = callLink,
      onJoinClicked = {}
    )
  }
}

@Composable
fun SignalCallRow(
  callLink: CallLinkTable.CallLink,
  onJoinClicked: () -> Unit,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = dimensionResource(id = R.dimen.core_ui__gutter))
      .border(
        width = 1.25.dp,
        color = MaterialTheme.colorScheme.outline,
        shape = RoundedCornerShape(18.dp)
      )
      .padding(16.dp)
  ) {
    val callColorPair = AvatarColorPair.create(LocalContext.current, callLink.avatarColor)

    Image(
      imageVector = ImageVector.vectorResource(id = R.drawable.symbol_video_display_bold_40),
      contentScale = ContentScale.Inside,
      contentDescription = null,
      colorFilter = ColorFilter.tint(Color(callColorPair.foregroundColor)),
      modifier = Modifier
        .size(64.dp)
        .background(
          color = Color(callColorPair.backgroundColor),
          shape = CircleShape
        )
    )

    Spacer(modifier = Modifier.width(10.dp))

    Column(
      modifier = Modifier
        .weight(1f)
        .align(CenterVertically)
    ) {
      Text(
        text = callLink.state.name.ifEmpty { stringResource(id = R.string.CreateCallLinkBottomSheetDialogFragment__signal_call) }
      )
      Text(
        text = callLink.credentials?.let { CallLinks.url(it.linkKeyBytes) } ?: "",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }

    Spacer(modifier = Modifier.width(10.dp))

    Buttons.Small(
      onClick = onJoinClicked,
      modifier = Modifier.align(CenterVertically)
    ) {
      Text(text = stringResource(id = R.string.CreateCallLinkBottomSheetDialogFragment__join))
    }
  }
}
