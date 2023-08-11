package org.thoughtcrime.securesms.database

import android.net.Uri
import org.signal.core.util.Bitmask
import org.signal.core.util.toOptional
import org.signal.libsignal.zkgroup.profiles.ExpiringProfileKeyCredential
import org.thoughtcrime.securesms.badges.models.Badge
import org.thoughtcrime.securesms.conversation.colors.AvatarColor
import org.thoughtcrime.securesms.conversation.colors.ChatColors
import org.thoughtcrime.securesms.database.model.GroupRecord
import org.thoughtcrime.securesms.database.model.ProfileAvatarFileDetails
import org.thoughtcrime.securesms.database.model.RecipientRecord
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.profiles.ProfileName
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientDetails
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.wallpaper.ChatWallpaper
import org.whispersystems.signalservice.api.push.ServiceId.ACI
import java.util.UUID
import kotlin.random.Random

/**
 * Test utilities to create recipients in different states.
 */
object RecipientDatabaseTestUtils {

  fun createRecipient(
    resolved: Boolean = false,
    groupName: String? = null,
    isSelf: Boolean = false,
    participants: List<RecipientId> = listOf(),
    recipientId: RecipientId = RecipientId.from(Random.nextLong()),
    serviceId: ACI? = ACI.from(UUID.randomUUID()),
    username: String? = null,
    e164: String? = null,
    email: String? = null,
    groupId: GroupId? = null,
    groupType: RecipientTable.RecipientType = RecipientTable.RecipientType.INDIVIDUAL,
    blocked: Boolean = false,
    muteUntil: Long = -1,
    messageVibrateState: RecipientTable.VibrateState = RecipientTable.VibrateState.DEFAULT,
    callVibrateState: RecipientTable.VibrateState = RecipientTable.VibrateState.DEFAULT,
    messageRingtone: Uri = Uri.EMPTY,
    callRingtone: Uri = Uri.EMPTY,
    expireMessages: Int = 0,
    registered: RecipientTable.RegisteredState = RecipientTable.RegisteredState.REGISTERED,
    profileKey: ByteArray = Random.nextBytes(32),
    expiringProfileKeyCredential: ExpiringProfileKeyCredential? = null,
    systemProfileName: ProfileName = ProfileName.EMPTY,
    systemDisplayName: String? = null,
    systemContactPhoto: String? = null,
    systemPhoneLabel: String? = null,
    systemContactUri: String? = null,
    signalProfileName: ProfileName = ProfileName.EMPTY,
    signalProfileAvatar: String? = null,
    profileAvatarFileDetails: ProfileAvatarFileDetails = ProfileAvatarFileDetails.NO_DETAILS,
    profileSharing: Boolean = false,
    lastProfileFetch: Long = 0L,
    notificationChannel: String? = null,
    unidentifiedAccessMode: RecipientTable.UnidentifiedAccessMode = RecipientTable.UnidentifiedAccessMode.UNKNOWN,
    capabilities: Long = 0L,
    storageId: ByteArray? = null,
    mentionSetting: RecipientTable.MentionSetting = RecipientTable.MentionSetting.ALWAYS_NOTIFY,
    wallpaper: ChatWallpaper? = null,
    chatColors: ChatColors? = null,
    avatarColor: AvatarColor = AvatarColor.A100,
    about: String? = null,
    aboutEmoji: String? = null,
    syncExtras: RecipientRecord.SyncExtras = RecipientRecord.SyncExtras(
      null,
      null,
      null,
      IdentityTable.VerifiedStatus.DEFAULT,
      false,
      false,
      0,
      null
    ),
    extras: Recipient.Extras? = null,
    hasGroupsInCommon: Boolean = false,
    badges: List<Badge> = emptyList(),
    isReleaseChannel: Boolean = false,
    isActive: Boolean = true,
    groupRecord: GroupRecord? = null
  ): Recipient = Recipient(
    recipientId,
    RecipientDetails(
      groupName = groupName,
      systemContactName = systemDisplayName,
      isSelf = isSelf,
      registeredState = registered,
      record = RecipientRecord(
        id = recipientId,
        aci = serviceId,
        pni = null,
        username = username,
        e164 = e164,
        email = email,
        groupId = groupId,
        distributionListId = null,
        recipientType = groupType,
        isBlocked = blocked,
        muteUntil = muteUntil,
        messageVibrateState = messageVibrateState,
        callVibrateState = callVibrateState,
        messageRingtone = messageRingtone,
        callRingtone = callRingtone,
        expireMessages = expireMessages,
        registered = registered,
        profileKey = profileKey,
        expiringProfileKeyCredential = expiringProfileKeyCredential,
        systemProfileName = systemProfileName,
        systemDisplayName = systemDisplayName,
        systemContactPhotoUri = systemContactPhoto,
        systemPhoneLabel = systemPhoneLabel,
        systemContactUri = systemContactUri,
        signalProfileName = signalProfileName,
        signalProfileAvatar = signalProfileAvatar,
        profileAvatarFileDetails = profileAvatarFileDetails,
        profileSharing = profileSharing,
        lastProfileFetch = lastProfileFetch,
        notificationChannel = notificationChannel,
        unidentifiedAccessMode = unidentifiedAccessMode,
        capabilities = RecipientRecord.Capabilities(
          rawBits = capabilities,
          groupsV1MigrationCapability = Recipient.Capability.deserialize(Bitmask.read(capabilities, RecipientTable.Capabilities.GROUPS_V1_MIGRATION, RecipientTable.Capabilities.BIT_LENGTH).toInt()),
          senderKeyCapability = Recipient.Capability.deserialize(Bitmask.read(capabilities, RecipientTable.Capabilities.SENDER_KEY, RecipientTable.Capabilities.BIT_LENGTH).toInt()),
          announcementGroupCapability = Recipient.Capability.deserialize(Bitmask.read(capabilities, RecipientTable.Capabilities.ANNOUNCEMENT_GROUPS, RecipientTable.Capabilities.BIT_LENGTH).toInt()),
          changeNumberCapability = Recipient.Capability.deserialize(Bitmask.read(capabilities, RecipientTable.Capabilities.CHANGE_NUMBER, RecipientTable.Capabilities.BIT_LENGTH).toInt()),
          storiesCapability = Recipient.Capability.deserialize(Bitmask.read(capabilities, RecipientTable.Capabilities.STORIES, RecipientTable.Capabilities.BIT_LENGTH).toInt()),
          giftBadgesCapability = Recipient.Capability.deserialize(Bitmask.read(capabilities, RecipientTable.Capabilities.GIFT_BADGES, RecipientTable.Capabilities.BIT_LENGTH).toInt()),
          pnpCapability = Recipient.Capability.deserialize(Bitmask.read(capabilities, RecipientTable.Capabilities.PNP, RecipientTable.Capabilities.BIT_LENGTH).toInt()),
          paymentActivation = Recipient.Capability.deserialize(Bitmask.read(capabilities, RecipientTable.Capabilities.PAYMENT_ACTIVATION, RecipientTable.Capabilities.BIT_LENGTH).toInt())
        ),
        storageId = storageId,
        mentionSetting = mentionSetting,
        wallpaper = wallpaper,
        chatColors = chatColors,
        avatarColor = avatarColor,
        about = about,
        aboutEmoji = aboutEmoji,
        syncExtras = syncExtras,
        extras = extras,
        hasGroupsInCommon = hasGroupsInCommon,
        badges = badges,
        needsPniSignature = false,
        hiddenState = Recipient.HiddenState.NOT_HIDDEN,
        callLinkRoomId = null
      ),
      participantIds = participants,
      isReleaseChannel = isReleaseChannel,
      avatarColor = null,
      groupRecord = groupRecord.toOptional()
    ),
    resolved
  )
}
