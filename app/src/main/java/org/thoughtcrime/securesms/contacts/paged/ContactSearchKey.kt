package org.thoughtcrime.securesms.contacts.paged

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.sharing.ShareContact

/**
 * Represents a row in a list of Contact results.
 */
sealed class ContactSearchKey {

  /**
   * Generates a ShareContact object used to display which contacts have been selected. This should *not*
   * be used for the final sharing process, as it is not always truthful about, for example, KnownRecipient of
   * a group vs. a group's Story.
   */
  open fun requireShareContact(): ShareContact = error("This key cannot be converted into a ShareContact")

  open fun requireParcelable(): ParcelableRecipientSearchKey = error("This key cannot be parcelized")

  sealed class RecipientSearchKey : ContactSearchKey() {

    abstract val recipientId: RecipientId
    abstract val isStory: Boolean

    data class Story(override val recipientId: RecipientId) : RecipientSearchKey() {
      override fun requireShareContact(): ShareContact {
        return ShareContact(recipientId)
      }

      override fun requireParcelable(): ParcelableRecipientSearchKey {
        return ParcelableRecipientSearchKey(ParcelableType.STORY, recipientId)
      }

      override val isStory: Boolean = true
    }

    /**
     * Key to a recipient which already exists in our database
     */
    data class KnownRecipient(override val recipientId: RecipientId) : RecipientSearchKey() {
      override fun requireShareContact(): ShareContact {
        return ShareContact(recipientId)
      }

      override fun requireParcelable(): ParcelableRecipientSearchKey {
        return ParcelableRecipientSearchKey(ParcelableType.KNOWN_RECIPIENT, recipientId)
      }

      override val isStory: Boolean = false
    }
  }

  /**
   * Key to a header for a given section
   */
  data class Header(val sectionKey: ContactSearchConfiguration.SectionKey) : ContactSearchKey()

  /**
   * Key to an expand button for a given section
   */
  data class Expand(val sectionKey: ContactSearchConfiguration.SectionKey) : ContactSearchKey()

  @Parcelize
  data class ParcelableRecipientSearchKey(val type: ParcelableType, val recipientId: RecipientId) : Parcelable {
    fun asRecipientSearchKey(): RecipientSearchKey {
      return when (type) {
        ParcelableType.STORY -> RecipientSearchKey.Story(recipientId)
        ParcelableType.KNOWN_RECIPIENT -> RecipientSearchKey.KnownRecipient(recipientId)
      }
    }
  }

  enum class ParcelableType {
    STORY,
    KNOWN_RECIPIENT
  }
}
