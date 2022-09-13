package org.thoughtcrime.securesms.components

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.View.OnLongClickListener
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatImageButton
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.components.menu.ActionItem
import org.thoughtcrime.securesms.components.menu.SignalContextMenu
import org.thoughtcrime.securesms.conversation.MessageSendType
import org.thoughtcrime.securesms.util.ViewUtil
import java.lang.AssertionError
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The send button you see in a conversation.
 * Also encapsulates the long-press menu that allows users to switch [MessageSendType]s.
 */
class SendButton(context: Context, attributeSet: AttributeSet?) : AppCompatImageButton(context, attributeSet), OnLongClickListener {

  companion object {
    private val TAG = Log.tag(SendButton::class.java)
  }

  private val listeners: MutableList<SendTypeChangedListener> = CopyOnWriteArrayList()

  private var availableSendTypes: List<MessageSendType> = MessageSendType.getAllAvailable(context, false)
  private var activeMessageSendType: MessageSendType? = null
  private var defaultTransportType: MessageSendType.TransportType = MessageSendType.TransportType.SMS
  private var defaultSubscriptionId: Int? = null
  private var popupContainer: ViewGroup? = null

  init {
    setOnLongClickListener(this)
    ViewUtil.mirrorIfRtl(this, getContext())
  }

  /**
   * @return True if the [selectedSendType] was chosen manually by the user, otherwise false.
   */
  val isManualSelection: Boolean
    get() = activeMessageSendType != null

  /**
   * The actively-selected send type.
   */
  val selectedSendType: MessageSendType
    get() {
      activeMessageSendType?.let {
        return it
      }

      if (defaultTransportType === MessageSendType.TransportType.SMS) {
        for (type in availableSendTypes) {
          if (type.usesSmsTransport && (defaultSubscriptionId == null || type.simSubscriptionId == defaultSubscriptionId)) {
            return type
          }
        }
      }

      for (type in availableSendTypes) {
        if (type.transportType === defaultTransportType) {
          return type
        }
      }

      Log.w(TAG, "No options of default type! Resetting. DefaultTransportType: $defaultTransportType, AllAvailable: ${availableSendTypes.map { it.transportType }}")

      val signalType: MessageSendType? = availableSendTypes.firstOrNull { it.usesSignalTransport }
      if (signalType != null) {
        Log.w(TAG, "No options of default type, but Signal type is available. Switching. DefaultTransportType: $defaultTransportType, AllAvailable: ${availableSendTypes.map { it.transportType }}")
        defaultTransportType = MessageSendType.TransportType.SIGNAL
        onSelectionChanged(signalType, false)
        return signalType
      } else if (availableSendTypes.isEmpty()) {
        Log.w(TAG, "No send types available at all! Enabling the Signal transport.")
        defaultTransportType = MessageSendType.TransportType.SIGNAL
        availableSendTypes = listOf(MessageSendType.SignalMessageSendType)
        onSelectionChanged(MessageSendType.SignalMessageSendType, false)
        return MessageSendType.SignalMessageSendType
      } else {
        throw AssertionError("No options of default type! DefaultTransportType: $defaultTransportType, AllAvailable: ${availableSendTypes.map { it.transportType }}")
      }
    }

  fun addOnSelectionChangedListener(listener: SendTypeChangedListener) {
    listeners.add(listener)
  }

  fun triggerSelectedChangedEvent() {
    onSelectionChanged(newType = selectedSendType, isManualSelection = false)
  }

  fun resetAvailableTransports(isMediaMessage: Boolean) {
    availableSendTypes = MessageSendType.getAllAvailable(context, isMediaMessage)
    activeMessageSendType = null
    defaultTransportType = MessageSendType.TransportType.SMS
    defaultSubscriptionId = null
    onSelectionChanged(newType = selectedSendType, isManualSelection = false)
  }

  fun disableTransportType(type: MessageSendType.TransportType) {
    availableSendTypes = availableSendTypes.filterNot { it.transportType == type }
  }

  fun setDefaultTransport(type: MessageSendType.TransportType) {
    if (defaultTransportType == type) {
      return
    }
    defaultTransportType = type
    onSelectionChanged(newType = selectedSendType, isManualSelection = false)
  }

  fun setSendType(sendType: MessageSendType?) {
    if (activeMessageSendType == sendType) {
      return
    }
    activeMessageSendType = sendType
    onSelectionChanged(newType = selectedSendType, isManualSelection = true)
  }

  fun setDefaultSubscriptionId(subscriptionId: Int?) {
    if (defaultSubscriptionId == subscriptionId) {
      return
    }
    defaultSubscriptionId = subscriptionId
    onSelectionChanged(newType = selectedSendType, isManualSelection = false)
  }

  /**
   * Must be called with a view that is acceptable for determining the bounds of the popup selector.
   */
  fun setPopupContainer(container: ViewGroup) {
    popupContainer = container
  }

  private fun onSelectionChanged(newType: MessageSendType, isManualSelection: Boolean) {
    setImageResource(newType.buttonDrawableRes)
    contentDescription = context.getString(newType.titleRes)

    for (listener in listeners) {
      listener.onSendTypeChanged(newType, isManualSelection)
    }
  }

  override fun onLongClick(v: View): Boolean {
    if (!isEnabled || availableSendTypes.size == 1) {
      return false
    }

    val currentlySelected: MessageSendType = selectedSendType

    val items = availableSendTypes
      .filterNot { it == currentlySelected }
      .map { option ->
        ActionItem(
          iconRes = option.menuDrawableRes,
          title = option.getTitle(context),
          action = { setSendType(option) }
        )
      }

    SignalContextMenu.Builder((parent as View), popupContainer!!)
      .preferredVerticalPosition(SignalContextMenu.VerticalPosition.ABOVE)
      .offsetY(ViewUtil.dpToPx(8))
      .show(items)

    return true
  }

  interface SendTypeChangedListener {
    fun onSendTypeChanged(newType: MessageSendType, manuallySelected: Boolean)
  }
}
