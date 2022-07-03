package org.thoughtcrime.securesms.keyvalue

import org.json.JSONObject
import org.signal.core.util.StringSerializer
import org.thoughtcrime.securesms.database.model.DistributionListId
import org.thoughtcrime.securesms.groups.GroupId

internal class StoryValues(store: KeyValueStore) : SignalStoreValues(store) {

  companion object {
    /*
     * User option to completely disable stories
     */
    private const val MANUAL_FEATURE_DISABLE = "stories.disable"

    private const val LAST_FONT_VERSION_CHECK = "stories.last.font.version.check"

    /**
     * Used to check whether we should display certain dialogs.
     */
    private const val USER_HAS_ADDED_TO_A_STORY = "user.has.added.to.a.story"

    /**
     * Rolling window of latest two private or group stories a user has sent to.
     */
    private const val LATEST_STORY_SENDS = "latest.story.sends"

    /**
     * Video Trim tooltip marker
     */
    private const val VIDEO_TOOLTIP_SEEN_MARKER = "stories.video.will.be.trimmed.tooltip.seen"

    /**
     * Cannot send to story tooltip marker
     */
    private const val CANNOT_SEND_SEEN_MARKER = "stories.cannot.send.video.tooltip.seen"

    /**
     * Whether or not the user has see the "Navigation education" view
     */
    private const val USER_HAS_SEEN_FIRST_NAV_VIEW = "stories.user.has.seen.first.navigation.view"
  }

  override fun onFirstEverAppLaunch() = Unit

  override fun getKeysToIncludeInBackup(): MutableList<String> = mutableListOf(
    MANUAL_FEATURE_DISABLE,
    USER_HAS_ADDED_TO_A_STORY,
    VIDEO_TOOLTIP_SEEN_MARKER,
    CANNOT_SEND_SEEN_MARKER,
    USER_HAS_SEEN_FIRST_NAV_VIEW
  )

  var isFeatureDisabled: Boolean by booleanValue(MANUAL_FEATURE_DISABLE, false)

  var lastFontVersionCheck: Long by longValue(LAST_FONT_VERSION_CHECK, 0)

  var userHasBeenNotifiedAboutStories: Boolean by booleanValue(USER_HAS_ADDED_TO_A_STORY, false)

  var videoTooltipSeen by booleanValue(VIDEO_TOOLTIP_SEEN_MARKER, false)

  var cannotSendTooltipSeen by booleanValue(CANNOT_SEND_SEEN_MARKER, false)

  var userHasSeenFirstNavView: Boolean by booleanValue(USER_HAS_SEEN_FIRST_NAV_VIEW, false)

  fun setLatestStorySend(storySend: StorySend) {
    synchronized(this) {
      val storySends: List<StorySend> = getList(LATEST_STORY_SENDS, StorySendSerializer)
      val newStorySends: List<StorySend> = listOf(storySend) + storySends.take(1)
      putList(LATEST_STORY_SENDS, newStorySends, StorySendSerializer)
    }
  }

  fun getLatestActiveStorySendTimestamps(activeCutoffTimestamp: Long): List<StorySend> {
    val storySends: List<StorySend> = getList(LATEST_STORY_SENDS, StorySendSerializer)
    return storySends.filter { it.timestamp >= activeCutoffTimestamp }
  }

  private object StorySendSerializer : StringSerializer<StorySend> {

    override fun serialize(data: StorySend): String {
      return JSONObject()
        .put("timestamp", data.timestamp)
        .put("groupId", if (data.identifier is StorySend.Identifier.Group) data.identifier.groupId.toString() else null)
        .put("distributionListId", if (data.identifier is StorySend.Identifier.DistributionList) data.identifier.distributionListId.serialize() else null)
        .toString()
    }

    override fun deserialize(data: String): StorySend {
      val jsonData = JSONObject(data)

      val timestamp = jsonData.getLong("timestamp")

      val identifier = if (jsonData.has("groupId")) {
        val group = jsonData.getString("groupId")
        StorySend.Identifier.Group(GroupId.parse(group))
      } else {
        val distributionListId = jsonData.getString("distributionListId")
        StorySend.Identifier.DistributionList(DistributionListId.from(distributionListId))
      }

      return StorySend(timestamp, identifier)
    }
  }
}
