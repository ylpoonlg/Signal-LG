package org.thoughtcrime.securesms.mediasend.v2

import android.os.Bundle
import org.thoughtcrime.securesms.contacts.paged.ContactSearchKey
import org.thoughtcrime.securesms.recipients.RecipientId

sealed class MediaSelectionDestination {

  object Wallpaper : MediaSelectionDestination() {
    override fun toBundle(): Bundle {
      return Bundle().apply {
        putBoolean(WALLPAPER, true)
      }
    }
  }

  object Avatar : MediaSelectionDestination() {
    override fun toBundle(): Bundle {
      return Bundle().apply {
        putBoolean(AVATAR, true)
      }
    }
  }

  object ChooseAfterMediaSelection : MediaSelectionDestination() {
    override fun toBundle(): Bundle {
      return Bundle.EMPTY
    }
  }

  class SingleRecipient(private val id: RecipientId) : MediaSelectionDestination() {
    override fun getRecipientSearchKey(): ContactSearchKey.RecipientSearchKey = ContactSearchKey.RecipientSearchKey.KnownRecipient(id)

    override fun toBundle(): Bundle {
      return Bundle().apply {
        putParcelable(RECIPIENT, id)
      }
    }
  }

  class MultipleRecipients(val recipientSearchKeys: List<ContactSearchKey.RecipientSearchKey>) : MediaSelectionDestination() {

    companion object {
      fun fromParcel(parcelables: List<ContactSearchKey.ParcelableRecipientSearchKey>): MultipleRecipients {
        return MultipleRecipients(parcelables.map { it.asRecipientSearchKey() }.filterIsInstance(ContactSearchKey.RecipientSearchKey::class.java))
      }
    }

    override fun getRecipientSearchKeyList(): List<ContactSearchKey.RecipientSearchKey> = recipientSearchKeys

    override fun toBundle(): Bundle {
      return Bundle().apply {
        putParcelableArrayList(RECIPIENT_LIST, ArrayList(recipientSearchKeys.map { it.requireParcelable() }))
      }
    }
  }

  open fun getRecipientSearchKey(): ContactSearchKey.RecipientSearchKey? = null
  open fun getRecipientSearchKeyList(): List<ContactSearchKey.RecipientSearchKey> = emptyList()

  abstract fun toBundle(): Bundle

  companion object {
    private const val WALLPAPER = "wallpaper"
    private const val AVATAR = "avatar"
    private const val RECIPIENT = "recipient"
    private const val RECIPIENT_LIST = "recipient_list"

    fun fromBundle(bundle: Bundle): MediaSelectionDestination {
      return when {
        bundle.containsKey(WALLPAPER) -> Wallpaper
        bundle.containsKey(AVATAR) -> Avatar
        bundle.containsKey(RECIPIENT) -> SingleRecipient(requireNotNull(bundle.getParcelable(RECIPIENT)))
        bundle.containsKey(RECIPIENT_LIST) -> MultipleRecipients.fromParcel(requireNotNull(bundle.getParcelableArrayList(RECIPIENT_LIST)))
        else -> ChooseAfterMediaSelection
      }
    }
  }
}
