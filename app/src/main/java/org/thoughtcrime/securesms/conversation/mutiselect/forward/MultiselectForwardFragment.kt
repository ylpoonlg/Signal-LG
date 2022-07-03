package org.thoughtcrime.securesms.conversation.mutiselect.forward

import android.content.res.ColorStateList
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.ContactFilterView
import org.thoughtcrime.securesms.components.TooltipPopup
import org.thoughtcrime.securesms.components.WrapperDialogFragment
import org.thoughtcrime.securesms.contacts.paged.ContactSearchConfiguration
import org.thoughtcrime.securesms.contacts.paged.ContactSearchKey
import org.thoughtcrime.securesms.contacts.paged.ContactSearchMediator
import org.thoughtcrime.securesms.contacts.paged.ContactSearchState
import org.thoughtcrime.securesms.conversation.ui.error.SafetyNumberChangeDialog
import org.thoughtcrime.securesms.database.model.IdentityRecord
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.mediasend.v2.stories.ChooseGroupStoryBottomSheet
import org.thoughtcrime.securesms.mediasend.v2.stories.ChooseStoryTypeBottomSheet
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.sharing.MultiShareArgs
import org.thoughtcrime.securesms.sharing.ShareSelectionAdapter
import org.thoughtcrime.securesms.sharing.ShareSelectionMappingModel
import org.thoughtcrime.securesms.stories.Stories
import org.thoughtcrime.securesms.stories.Stories.getHeaderAction
import org.thoughtcrime.securesms.stories.dialogs.StoryDialogs
import org.thoughtcrime.securesms.stories.settings.create.CreateStoryFlowDialogFragment
import org.thoughtcrime.securesms.stories.settings.create.CreateStoryWithViewersFragment
import org.thoughtcrime.securesms.stories.settings.privacy.HideStoryFromDialogFragment
import org.thoughtcrime.securesms.util.BottomSheetUtil
import org.thoughtcrime.securesms.util.FeatureFlags
import org.thoughtcrime.securesms.util.FullscreenHelper
import org.thoughtcrime.securesms.util.LifecycleDisposable
import org.thoughtcrime.securesms.util.Util
import org.thoughtcrime.securesms.util.fragments.findListener
import org.thoughtcrime.securesms.util.fragments.requireListener
import org.thoughtcrime.securesms.util.views.SimpleProgressDialog
import org.thoughtcrime.securesms.util.visible

/**
 * Allows selection and optional sending to one or more users.
 *
 * This fragment is designed to be displayed in a Dialog fragment, and thus has two available constructors to display as a bottom sheet or full screen dialog.
 *
 * To customize the available recipients, a parent must implement `SearchConfigurationProvider`
 *
 * This fragment will emit one of two possible result values at the same key, `RESULT_KEY`:
 *
 * - If the arguments contain a non-empty list of MultiShareArgs, then messages will be sent when the selection is confirmed. This will result in `RESULT_SENT` being true.
 * - If the arguments contain an empty list of MultiShareArgs, then the selection will be returned on confirmation. This will result in `RESULT_SELECTION` being set.
 *
 * It is up to the user of this fragment to handle the result accordingly utilizing a fragment result listener.
 */
class MultiselectForwardFragment :
  Fragment(R.layout.multiselect_forward_fragment),
  SafetyNumberChangeDialog.Callback,
  ChooseStoryTypeBottomSheet.Callback,
  WrapperDialogFragment.WrapperDialogFragmentCallback {

  private val viewModel: MultiselectForwardViewModel by viewModels(factoryProducer = this::createViewModelFactory)
  private val disposables = LifecycleDisposable()

  private lateinit var contactFilterView: ContactFilterView
  private lateinit var addMessage: EditText
  private lateinit var contactSearchMediator: ContactSearchMediator
  private lateinit var contactSearchRecycler: RecyclerView

  private lateinit var callback: Callback
  private var dismissibleDialog: SimpleProgressDialog.DismissibleDialog? = null
  private var handler: Handler? = null

  private fun createViewModelFactory(): MultiselectForwardViewModel.Factory {
    return MultiselectForwardViewModel.Factory(getMultiShareArgs(), isSelectionOnly, MultiselectForwardRepository())
  }

  private fun getMultiShareArgs(): ArrayList<MultiShareArgs> = requireNotNull(requireArguments().getParcelableArrayList(ARG_MULTISHARE_ARGS))

  private val forceDisableAddMessage: Boolean
    get() = requireArguments().getBoolean(ARG_FORCE_DISABLE_ADD_MESSAGE, false)

  private val isSelectionOnly: Boolean
    get() = requireArguments().getBoolean(ARG_FORCE_SELECTION_ONLY, false)

  private val sendButtonTint: Int
    get() = requireArguments().getInt(ARG_SEND_BUTTON_TINT, -1)

  override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
    return if (parentFragment != null) {
      requireParentFragment().onGetLayoutInflater(savedInstanceState)
    } else {
      super.onGetLayoutInflater(savedInstanceState)
    }
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    view.minimumHeight = resources.displayMetrics.heightPixels

    contactSearchRecycler = view.findViewById(R.id.contact_selection_list)
    contactSearchMediator = ContactSearchMediator(this, contactSearchRecycler, FeatureFlags.shareSelectionLimit(), !isSingleRecipientSelection(), this::getConfiguration, this::filterContacts)

    callback = findListener()!!
    disposables.bindTo(viewLifecycleOwner.lifecycle)

    contactFilterView = view.findViewById(R.id.contact_filter_edit_text)

    contactFilterView.setOnSearchInputFocusChangedListener { _, hasFocus ->
      if (hasFocus) {
        callback.onSearchInputFocused()
      }
    }

    contactFilterView.setOnFilterChangedListener {
      contactSearchMediator.onFilterChanged(it)
    }

    val container = callback.getContainer()
    val title: TextView? = container.findViewById(R.id.title)
    val bottomBarAndSpacer = LayoutInflater.from(requireContext()).inflate(R.layout.multiselect_forward_fragment_bottom_bar_and_spacer, container, false)
    val bottomBar: ViewGroup = bottomBarAndSpacer.findViewById(R.id.bottom_bar)
    val bottomBarSpacer: View = bottomBarAndSpacer.findViewById(R.id.bottom_bar_spacer)
    val shareSelectionRecycler: RecyclerView = bottomBar.findViewById(R.id.selected_list)
    val shareSelectionAdapter = ShareSelectionAdapter()
    val sendButtonFrame: View = bottomBar.findViewById(R.id.share_confirm_frame)
    val sendButton: View = bottomBar.findViewById(R.id.share_confirm)
    val backgroundHelper: View = bottomBar.findViewById(R.id.background_helper)

    if (sendButtonTint != -1) {
      ViewCompat.setBackgroundTintList(sendButton, ColorStateList.valueOf(sendButtonTint))
    }

    FullscreenHelper.configureBottomBarLayout(requireActivity(), bottomBarSpacer, bottomBar)

    backgroundHelper.setBackgroundColor(callback.getDialogBackgroundColor())
    bottomBarSpacer.setBackgroundColor(callback.getDialogBackgroundColor())

    title?.setText(requireArguments().getInt(ARG_TITLE))

    addMessage = bottomBar.findViewById(R.id.add_message)

    sendButton.doOnNextLayout {
      val rect = Rect()
      sendButton.getHitRect(rect)
      rect.top -= sendButtonFrame.paddingTop
      rect.left -= sendButtonFrame.paddingStart
      rect.right += sendButtonFrame.paddingEnd
      rect.bottom += sendButtonFrame.paddingBottom
      sendButtonFrame.touchDelegate = TouchDelegate(rect, sendButton)
    }

    sendButton.setOnClickListener {
      onSend(it)
    }

    sendButton.visible = !isSingleRecipientSelection()

    shareSelectionRecycler.adapter = shareSelectionAdapter

    bottomBar.visible = false

    container.addView(bottomBarAndSpacer)

    contactSearchMediator.getSelectionState().observe(viewLifecycleOwner) { contactSelection ->
      if (contactSelection.isNotEmpty() && isSingleRecipientSelection()) {
        onSend(sendButton)
        return@observe
      }

      shareSelectionAdapter.submitList(contactSelection.mapIndexed { index, key -> ShareSelectionMappingModel(key.requireShareContact(), index == 0) })

      addMessage.visible = !forceDisableAddMessage && contactSelection.any { key -> key !is ContactSearchKey.RecipientSearchKey.Story } && getMultiShareArgs().isNotEmpty()

      if (contactSelection.isNotEmpty() && !bottomBar.isVisible) {
        bottomBar.animation = AnimationUtils.loadAnimation(requireContext(), R.anim.slide_fade_from_bottom)
        bottomBar.visible = true
      } else if (contactSelection.isEmpty() && bottomBar.isVisible) {
        bottomBar.animation = AnimationUtils.loadAnimation(requireContext(), R.anim.slide_fade_to_bottom)
        bottomBar.visible = false
      }
    }

    viewModel.state.observe(viewLifecycleOwner) {
      when (it.stage) {
        MultiselectForwardState.Stage.Selection -> {}
        MultiselectForwardState.Stage.FirstConfirmation -> displayFirstSendConfirmation()
        is MultiselectForwardState.Stage.SafetyConfirmation -> displaySafetyNumberConfirmation(it.stage.identities)
        MultiselectForwardState.Stage.LoadingIdentities -> {}
        MultiselectForwardState.Stage.SendPending -> {
          handler?.removeCallbacksAndMessages(null)
          dismissibleDialog?.dismiss()
          dismissibleDialog = SimpleProgressDialog.showDelayed(requireContext())
        }
        MultiselectForwardState.Stage.SomeFailed -> dismissWithSuccess(R.plurals.MultiselectForwardFragment_messages_sent)
        MultiselectForwardState.Stage.AllFailed -> dismissAndShowToast(R.plurals.MultiselectForwardFragment_messages_failed_to_send)
        MultiselectForwardState.Stage.Success -> dismissWithSuccess(R.plurals.MultiselectForwardFragment_messages_sent)
        is MultiselectForwardState.Stage.SelectionConfirmed -> dismissWithSelection(it.stage.selectedContacts)
      }

      sendButton.isEnabled = it.stage == MultiselectForwardState.Stage.Selection
    }

    setFragmentResultListener(CreateStoryWithViewersFragment.REQUEST_KEY) { _, bundle ->
      val recipientId: RecipientId = bundle.getParcelable(CreateStoryWithViewersFragment.STORY_RECIPIENT)!!
      contactSearchMediator.setKeysSelected(setOf(ContactSearchKey.RecipientSearchKey.Story(recipientId)))
      contactFilterView.clear()
    }

    setFragmentResultListener(ChooseGroupStoryBottomSheet.GROUP_STORY) { _, bundle ->
      val groups: Set<RecipientId> = bundle.getParcelableArrayList<RecipientId>(ChooseGroupStoryBottomSheet.RESULT_SET)?.toSet() ?: emptySet()
      val keys: Set<ContactSearchKey.RecipientSearchKey.Story> = groups.map { ContactSearchKey.RecipientSearchKey.Story(it) }.toSet()
      contactSearchMediator.addToVisibleGroupStories(keys)
      contactSearchMediator.setKeysSelected(keys)
      contactFilterView.clear()
    }
  }

  override fun onResume() {
    super.onResume()

    val now = System.currentTimeMillis()
    val expiringMessages = getMultiShareArgs().filter { it.expiresAt > 0L }
    val firstToExpire = expiringMessages.minByOrNull { it.expiresAt }
    val earliestExpiration = firstToExpire?.expiresAt ?: -1L

    if (earliestExpiration > 0) {
      if (earliestExpiration <= now) {
        handleMessageExpired()
      } else {
        handler = Handler(Looper.getMainLooper())
        handler?.postDelayed(this::handleMessageExpired, earliestExpiration - now)
      }
    }
  }

  override fun onPause() {
    super.onPause()

    handler?.removeCallbacksAndMessages(null)
  }

  override fun onDestroyView() {
    dismissibleDialog?.dismissNow()
    super.onDestroyView()
  }

  private fun displayFirstSendConfirmation() {
    SignalStore.tooltips().markMultiForwardDialogSeen()

    val messageCount = getMessageCount()

    MaterialAlertDialogBuilder(requireContext())
      .setTitle(R.string.MultiselectForwardFragment__faster_forwards)
      .setMessage(R.string.MultiselectForwardFragment__forwarded_messages_are_now)
      .setPositiveButton(resources.getQuantityString(R.plurals.MultiselectForwardFragment_send_d_messages, messageCount, messageCount)) { d, _ ->
        d.dismiss()
        viewModel.confirmFirstSend(addMessage.text.toString(), contactSearchMediator.getSelectedContacts())
      }
      .setNegativeButton(android.R.string.cancel) { d, _ ->
        d.dismiss()
        viewModel.cancelSend()
      }
      .show()
  }

  private fun onSend(sendButton: View) {
    sendButton.isEnabled = false

    StoryDialogs.guardWithAddToYourStoryDialog(
      requireContext(),
      contactSearchMediator.getSelectedContacts(),
      onAddToStory = {
        performSend()
      },
      onEditViewers = {
        sendButton.isEnabled = true
        HideStoryFromDialogFragment().show(childFragmentManager, null)
      },
      onCancel = {
        sendButton.isEnabled = true
      }
    )
  }

  private fun performSend() {
    viewModel.send(addMessage.text.toString(), contactSearchMediator.getSelectedContacts())
  }

  private fun displaySafetyNumberConfirmation(identityRecords: List<IdentityRecord>) {
    SafetyNumberChangeDialog.show(childFragmentManager, identityRecords)
  }

  private fun dismissWithSuccess(@PluralsRes toastTextResId: Int) {
    requireListener<Callback>().setResult(
      Bundle().apply {
        putBoolean(RESULT_SENT, true)
      }
    )

    dismissAndShowToast(toastTextResId)
  }

  private fun dismissAndShowToast(@PluralsRes toastTextResId: Int) {
    val argCount = getMessageCount()

    callback.onFinishForwardAction()
    dismissibleDialog?.dismiss()
    Toast.makeText(requireContext(), requireContext().resources.getQuantityString(toastTextResId, argCount), Toast.LENGTH_SHORT).show()
    callback.exitFlow()
  }

  private fun getMessageCount(): Int = getMultiShareArgs().size + if (addMessage.text.isNotEmpty()) 1 else 0

  private fun handleMessageExpired() {
    callback.onFinishForwardAction()
    dismissibleDialog?.dismiss()
    Toast.makeText(requireContext(), resources.getQuantityString(R.plurals.MultiselectForwardFragment__couldnt_forward_messages, getMultiShareArgs().size), Toast.LENGTH_LONG).show()
    callback.exitFlow()
  }

  private fun dismissWithSelection(selectedContacts: Set<ContactSearchKey>) {
    callback.onFinishForwardAction()
    dismissibleDialog?.dismiss()

    val resultsBundle = Bundle().apply {
      putParcelableArrayList(RESULT_SELECTION, ArrayList(selectedContacts.map { it.requireParcelable() }))
    }

    callback.setResult(resultsBundle)
    callback.exitFlow()
  }

  override fun onSendAnywayAfterSafetyNumberChange(changedRecipients: MutableList<RecipientId>) {
    viewModel.confirmSafetySend(addMessage.text.toString(), contactSearchMediator.getSelectedContacts())
  }

  override fun onMessageResentAfterSafetyNumberChange() {
    throw UnsupportedOperationException()
  }

  override fun onCanceled() {
    viewModel.cancelSend()
  }

  private fun getStorySendRequirements(): Stories.MediaTransform.SendRequirements {
    return requireListener<Callback>().getStorySendRequirements() ?: viewModel.snapshot.storySendRequirements
  }

  private fun filterContacts(view: View?, contactSet: Set<ContactSearchKey>): Set<ContactSearchKey> {
    val storySendRequirements = getStorySendRequirements()
    val resultsSet = contactSet.filterNot {
      it is ContactSearchKey.RecipientSearchKey && it.isStory && storySendRequirements == Stories.MediaTransform.SendRequirements.CAN_NOT_SEND
    }

    if (view != null && contactSet.any { it is ContactSearchKey.RecipientSearchKey && it.isStory }) {
      @Suppress("NON_EXHAUSTIVE_WHEN_STATEMENT")
      when (storySendRequirements) {
        Stories.MediaTransform.SendRequirements.REQUIRES_CLIP -> {
          if (!SignalStore.storyValues().videoTooltipSeen) {
            displayTooltip(view, R.string.MultiselectForwardFragment__videos_will_be_trimmed) {
              SignalStore.storyValues().videoTooltipSeen = true
            }
          }
        }
        Stories.MediaTransform.SendRequirements.CAN_NOT_SEND -> {
          if (!SignalStore.storyValues().cannotSendTooltipSeen) {
            displayTooltip(view, R.string.MultiselectForwardFragment__videos_sent_to_stories_cant) {
              SignalStore.storyValues().cannotSendTooltipSeen = true
            }
          }
        }
      }
    }

    return resultsSet.toSet()
  }

  private fun displayTooltip(anchor: View, @StringRes text: Int, onDismiss: () -> Unit) {
    TooltipPopup
      .forTarget(anchor)
      .setText(text)
      .setTextColor(ContextCompat.getColor(requireContext(), R.color.signal_colorOnPrimary))
      .setBackgroundTint(ContextCompat.getColor(requireContext(), R.color.signal_colorPrimary))
      .setOnDismissListener {
        onDismiss()
      }
      .show(TooltipPopup.POSITION_BELOW)
  }

  private fun getConfiguration(contactSearchState: ContactSearchState): ContactSearchConfiguration {
    return findListener<SearchConfigurationProvider>()?.getSearchConfiguration(childFragmentManager, contactSearchState) ?: ContactSearchConfiguration.build {
      query = contactSearchState.query

      if (Stories.isFeatureEnabled() && isSelectedMediaValidForStories()) {
        val expandedConfig: ContactSearchConfiguration.ExpandConfig? = if (isSelectedMediaValidForNonStories()) {
          ContactSearchConfiguration.ExpandConfig(
            isExpanded = contactSearchState.expandedSections.contains(ContactSearchConfiguration.SectionKey.STORIES),
            maxCountWhenNotExpanded = { it + 1 }
          )
        } else {
          null
        }

        addSection(
          ContactSearchConfiguration.Section.Stories(
            groupStories = contactSearchState.groupStories,
            includeHeader = true,
            headerAction = getHeaderAction(childFragmentManager),
            expandConfig = expandedConfig
          )
        )
      }

      if (isSelectedMediaValidForNonStories()) {
        if (query.isNullOrEmpty()) {
          addSection(
            ContactSearchConfiguration.Section.Recents(
              includeHeader = true,
              includeSelf = true
            )
          )
        }

        addSection(
          ContactSearchConfiguration.Section.Individuals(
            includeHeader = true,
            transportType = if (includeSms()) ContactSearchConfiguration.TransportType.ALL else ContactSearchConfiguration.TransportType.PUSH,
            includeSelf = true
          )
        )

        addSection(
          ContactSearchConfiguration.Section.Groups(
            includeHeader = true,
            includeMms = includeSms()
          )
        )
      }
    }
  }

  private fun includeSms(): Boolean {
    return Util.isDefaultSmsProvider(requireContext()) && requireArguments().getBoolean(ARG_CAN_SEND_TO_NON_PUSH)
  }

  private fun isSingleRecipientSelection(): Boolean {
    return requireArguments().getBoolean(ARG_SELECT_SINGLE_RECIPIENT, false)
  }

  private fun isSelectedMediaValidForStories(): Boolean {
    return getMultiShareArgs().all { it.isValidForStories }
  }

  private fun isSelectedMediaValidForNonStories(): Boolean {
    return getMultiShareArgs().all { it.isValidForNonStories }
  }

  override fun onGroupStoryClicked() {
    ChooseGroupStoryBottomSheet().show(parentFragmentManager, ChooseGroupStoryBottomSheet.GROUP_STORY)
  }

  override fun onNewStoryClicked() {
    CreateStoryFlowDialogFragment().show(parentFragmentManager, CreateStoryWithViewersFragment.REQUEST_KEY)
  }

  interface Callback {
    fun onFinishForwardAction()
    fun exitFlow()
    fun onSearchInputFocused()
    fun setResult(bundle: Bundle)
    fun getContainer(): ViewGroup
    fun getDialogBackgroundColor(): Int
    fun getStorySendRequirements(): Stories.MediaTransform.SendRequirements? = null
  }

  companion object {
    const val ARG_MULTISHARE_ARGS = "multiselect.forward.fragment.arg.multishare.args"
    const val ARG_CAN_SEND_TO_NON_PUSH = "multiselect.forward.fragment.arg.can.send.to.non.push"
    const val ARG_TITLE = "multiselect.forward.fragment.title"
    const val ARG_FORCE_DISABLE_ADD_MESSAGE = "multiselect.forward.fragment.force.disable.add.message"
    const val ARG_FORCE_SELECTION_ONLY = "multiselect.forward.fragment.force.disable.add.message"
    const val ARG_SELECT_SINGLE_RECIPIENT = "multiselect.forward.framgent.select.single.recipient"
    const val ARG_SEND_BUTTON_TINT = "multiselect.forward.fragment.send.button.tint"
    const val RESULT_KEY = "result_key"
    const val RESULT_SELECTION = "result_selection_recipients"
    const val RESULT_SENT = "result_sent"

    @JvmStatic
    fun showBottomSheet(supportFragmentManager: FragmentManager, multiselectForwardFragmentArgs: MultiselectForwardFragmentArgs) {
      val fragment = MultiselectForwardBottomSheet()

      showDialogFragment(supportFragmentManager, fragment, multiselectForwardFragmentArgs)
    }

    @JvmStatic
    fun showFullScreen(supportFragmentManager: FragmentManager, multiselectForwardFragmentArgs: MultiselectForwardFragmentArgs) {
      val fragment = MultiselectForwardFullScreenDialogFragment()

      showDialogFragment(supportFragmentManager, fragment, multiselectForwardFragmentArgs)
    }

    @JvmStatic
    fun create(multiselectForwardFragmentArgs: MultiselectForwardFragmentArgs): Fragment {
      return MultiselectForwardFragment().apply {
        arguments = createArgumentsBundle(multiselectForwardFragmentArgs)
      }
    }

    private fun showDialogFragment(supportFragmentManager: FragmentManager, fragment: DialogFragment, multiselectForwardFragmentArgs: MultiselectForwardFragmentArgs) {
      fragment.arguments = createArgumentsBundle(multiselectForwardFragmentArgs)

      fragment.show(supportFragmentManager, BottomSheetUtil.STANDARD_BOTTOM_SHEET_FRAGMENT_TAG)
    }

    private fun createArgumentsBundle(multiselectForwardFragmentArgs: MultiselectForwardFragmentArgs): Bundle {
      return Bundle().apply {
        putParcelableArrayList(ARG_MULTISHARE_ARGS, ArrayList(multiselectForwardFragmentArgs.multiShareArgs))
        putBoolean(ARG_CAN_SEND_TO_NON_PUSH, multiselectForwardFragmentArgs.canSendToNonPush)
        putInt(ARG_TITLE, multiselectForwardFragmentArgs.title)
        putBoolean(ARG_FORCE_DISABLE_ADD_MESSAGE, multiselectForwardFragmentArgs.forceDisableAddMessage)
        putBoolean(ARG_FORCE_SELECTION_ONLY, multiselectForwardFragmentArgs.forceSelectionOnly)
        putBoolean(ARG_SELECT_SINGLE_RECIPIENT, multiselectForwardFragmentArgs.selectSingleRecipient)
        putInt(ARG_SEND_BUTTON_TINT, multiselectForwardFragmentArgs.sendButtonTint)
      }
    }
  }

  override fun onWrapperDialogFragmentDismissed() {
    contactSearchMediator.refresh()
  }
}
