package org.thoughtcrime.securesms.stories.settings.privacy

import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.stories.settings.my.MyStoryPrivacyState

data class ChooseInitialMyStoryMembershipState(val recipientId: RecipientId? = null, val privacyState: MyStoryPrivacyState = MyStoryPrivacyState())
