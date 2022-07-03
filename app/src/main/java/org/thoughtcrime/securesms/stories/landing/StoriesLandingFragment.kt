package org.thoughtcrime.securesms.stories.landing

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.transition.TransitionInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityOptionsCompat
import androidx.core.app.SharedElementCallback
import androidx.core.view.ViewCompat
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.kotlin.subscribeBy
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.DSLConfiguration
import org.thoughtcrime.securesms.components.settings.DSLSettingsAdapter
import org.thoughtcrime.securesms.components.settings.DSLSettingsFragment
import org.thoughtcrime.securesms.components.settings.DSLSettingsText
import org.thoughtcrime.securesms.components.settings.configure
import org.thoughtcrime.securesms.conversation.ConversationIntents
import org.thoughtcrime.securesms.conversation.mutiselect.forward.MultiselectForwardFragment
import org.thoughtcrime.securesms.conversation.mutiselect.forward.MultiselectForwardFragmentArgs
import org.thoughtcrime.securesms.conversation.ui.error.SafetyNumberChangeDialog
import org.thoughtcrime.securesms.database.model.MediaMmsMessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.StoryViewState
import org.thoughtcrime.securesms.main.Material3OnScrollHelperBinder
import org.thoughtcrime.securesms.mediasend.v2.MediaSelectionActivity
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.stories.StoryTextPostModel
import org.thoughtcrime.securesms.stories.StoryViewerArgs
import org.thoughtcrime.securesms.stories.dialogs.StoryContextMenu
import org.thoughtcrime.securesms.stories.dialogs.StoryDialogs
import org.thoughtcrime.securesms.stories.my.MyStoriesActivity
import org.thoughtcrime.securesms.stories.settings.StorySettingsActivity
import org.thoughtcrime.securesms.stories.tabs.ConversationListTab
import org.thoughtcrime.securesms.stories.tabs.ConversationListTabsViewModel
import org.thoughtcrime.securesms.stories.viewer.StoryViewerActivity
import org.thoughtcrime.securesms.util.LifecycleDisposable
import org.thoughtcrime.securesms.util.fragments.requireListener
import org.thoughtcrime.securesms.util.visible
import java.util.concurrent.TimeUnit

/**
 * The "landing page" for Stories.
 */
class StoriesLandingFragment : DSLSettingsFragment(layoutId = R.layout.stories_landing_fragment) {

  companion object {
    private const val LIST_SMOOTH_SCROLL_TO_TOP_THRESHOLD = 25
  }

  private lateinit var emptyNotice: View
  private lateinit var cameraFab: FloatingActionButton

  private val lifecycleDisposable = LifecycleDisposable()

  private val viewModel: StoriesLandingViewModel by viewModels(
    factoryProducer = {
      StoriesLandingViewModel.Factory(StoriesLandingRepository(requireContext()))
    }
  )

  private val tabsViewModel: ConversationListTabsViewModel by viewModels(ownerProducer = { requireActivity() })

  private lateinit var adapter: DSLSettingsAdapter

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setHasOptionsMenu(true)
  }

  override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
    menu.clear()
    inflater.inflate(R.menu.story_landing_menu, menu)
  }

  override fun onResume() {
    super.onResume()
    viewModel.isTransitioningToAnotherScreen = false
  }

  override fun bindAdapter(adapter: DSLSettingsAdapter) {
    this.adapter = adapter

    StoriesLandingItem.register(adapter)
    MyStoriesItem.register(adapter)
    ExpandHeader.register(adapter)

    requireListener<Material3OnScrollHelperBinder>().bindScrollHelper(recyclerView!!)

    lifecycleDisposable.bindTo(viewLifecycleOwner)
    emptyNotice = requireView().findViewById(R.id.empty_notice)
    cameraFab = requireView().findViewById(R.id.camera_fab)

    sharedElementEnterTransition = TransitionInflater.from(requireContext()).inflateTransition(R.transition.change_transform_fabs)
    setEnterSharedElementCallback(object : SharedElementCallback() {
      override fun onSharedElementStart(sharedElementNames: MutableList<String>?, sharedElements: MutableList<View>?, sharedElementSnapshots: MutableList<View>?) {
        if (sharedElementNames?.contains("camera_fab") == true) {
          cameraFab.setImageResource(R.drawable.ic_compose_outline_24)
          lifecycleDisposable += Single.timer(200, TimeUnit.MILLISECONDS)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeBy {
              cameraFab.setImageResource(R.drawable.ic_camera_outline_24)
            }
        }
      }
    })

    cameraFab.setOnClickListener {
      Permissions.with(this)
        .request(Manifest.permission.CAMERA)
        .ifNecessary()
        .withRationaleDialog(getString(R.string.ConversationActivity_to_capture_photos_and_video_allow_signal_access_to_the_camera), R.drawable.ic_camera_24)
        .withPermanentDenialDialog(getString(R.string.ConversationActivity_signal_needs_the_camera_permission_to_take_photos_or_video))
        .onAllGranted {
          startActivityIfAble(MediaSelectionActivity.camera(requireContext(), isStory = true))
        }
        .onAnyDenied { Toast.makeText(requireContext(), R.string.ConversationActivity_signal_needs_camera_permissions_to_take_photos_or_video, Toast.LENGTH_LONG).show() }
        .execute()
    }

    viewModel.state.observe(viewLifecycleOwner) {
      if (it.loadingState == StoriesLandingState.LoadingState.LOADED) {
        adapter.submitList(getConfiguration(it).toMappingModelList())
        emptyNotice.visible = it.hasNoStories
      }
    }

    requireActivity().onBackPressedDispatcher.addCallback(
      viewLifecycleOwner,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          tabsViewModel.onChatsSelected()
        }
      }
    )

    lifecycleDisposable += tabsViewModel.tabClickEvents
      .filter { it == ConversationListTab.STORIES }
      .subscribeBy(onNext = {
        val layoutManager = recyclerView?.layoutManager as? LinearLayoutManager ?: return@subscribeBy
        if (layoutManager.findFirstVisibleItemPosition() <= LIST_SMOOTH_SCROLL_TO_TOP_THRESHOLD) {
          recyclerView?.smoothScrollToPosition(0)
        } else {
          recyclerView?.scrollToPosition(0)
        }
      })
  }

  private fun getConfiguration(state: StoriesLandingState): DSLConfiguration {
    return configure {
      val (stories, hidden) = state.storiesLandingItems.map {
        createStoryLandingItem(it)
      }.partition {
        !it.data.isHidden
      }

      if (state.displayMyStoryItem) {
        customPref(
          MyStoriesItem.Model(
            onClick = {
              startActivityIfAble(Intent(requireContext(), MyStoriesActivity::class.java))
            },
            onClickThumbnail = {
              cameraFab.performClick()
            }
          )
        )
      }

      stories.forEach { item ->
        customPref(item)
      }

      if (hidden.isNotEmpty()) {
        customPref(
          ExpandHeader.Model(
            title = DSLSettingsText.from(R.string.StoriesLandingFragment__hidden_stories),
            isExpanded = state.isHiddenContentVisible,
            onClick = { viewModel.setHiddenContentVisible(it) }
          )
        )
      }

      if (state.isHiddenContentVisible) {
        hidden.forEach { item ->
          customPref(item)
        }
      }
    }
  }

  private fun createStoryLandingItem(data: StoriesLandingItemData): StoriesLandingItem.Model {
    return StoriesLandingItem.Model(
      data = data,
      onRowClick = { model, preview ->
        if (model.data.storyRecipient.isMyStory) {
          startActivityIfAble(Intent(requireContext(), MyStoriesActivity::class.java))
        } else if (model.data.primaryStory.messageRecord.isOutgoing && model.data.primaryStory.messageRecord.isFailed) {
          if (model.data.primaryStory.messageRecord.isIdentityMismatchFailure) {
            SafetyNumberChangeDialog.show(requireContext(), childFragmentManager, model.data.primaryStory.messageRecord)
          } else {
            StoryDialogs.resendStory(requireContext()) {
              lifecycleDisposable += viewModel.resend(model.data.primaryStory.messageRecord).subscribe()
            }
          }
        } else {
          val options = ActivityOptionsCompat.makeSceneTransitionAnimation(requireActivity(), preview, ViewCompat.getTransitionName(preview) ?: "")

          val record = model.data.primaryStory.messageRecord as MmsMessageRecord
          val blur = record.slideDeck.thumbnailSlide?.placeholderBlur
          val (text: StoryTextPostModel?, image: Uri?) = if (record.storyType.isTextStory) {
            StoryTextPostModel.parseFrom(record) to null
          } else {
            null to record.slideDeck.thumbnailSlide?.uri
          }

          startActivityIfAble(
            StoryViewerActivity.createIntent(
              context = requireContext(),
              storyViewerArgs = StoryViewerArgs(
                recipientId = model.data.storyRecipient.id,
                storyId = -1L,
                isInHiddenStoryMode = model.data.isHidden,
                storyThumbTextModel = text,
                storyThumbUri = image,
                storyThumbBlur = blur,
                recipientIds = viewModel.getRecipientIds(model.data.isHidden, model.data.storyViewState == StoryViewState.UNVIEWED),
                isUnviewedOnly = model.data.storyViewState == StoryViewState.UNVIEWED
              )
            ),
            options.toBundle()
          )
        }
      },
      onForwardStory = {
        MultiselectForwardFragmentArgs.create(requireContext(), it.data.primaryStory.multiselectCollection.toSet()) { args ->
          MultiselectForwardFragment.showBottomSheet(childFragmentManager, args)
        }
      },
      onGoToChat = {
        startActivityIfAble(ConversationIntents.createBuilder(requireContext(), it.data.storyRecipient.id, -1L).build())
      },
      onHideStory = {
        if (!it.data.isHidden) {
          handleHideStory(it)
        } else {
          lifecycleDisposable += viewModel.setHideStory(it.data.storyRecipient, !it.data.isHidden).subscribe()
        }
      },
      onShareStory = {
        StoryContextMenu.share(this@StoriesLandingFragment, it.data.primaryStory.messageRecord as MediaMmsMessageRecord)
      },
      onSave = {
        StoryContextMenu.save(requireContext(), it.data.primaryStory.messageRecord)
      },
      onDeleteStory = {
        handleDeleteStory(it)
      }
    )
  }

  private fun handleDeleteStory(model: StoriesLandingItem.Model) {
    lifecycleDisposable += StoryContextMenu.delete(requireContext(), setOf(model.data.primaryStory.messageRecord)).subscribe()
  }

  private fun handleHideStory(model: StoriesLandingItem.Model) {
    MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Signal_MaterialAlertDialog)
      .setTitle(R.string.StoriesLandingFragment__hide_story)
      .setMessage(getString(R.string.StoriesLandingFragment__new_story_updates, model.data.storyRecipient.getShortDisplayName(requireContext())))
      .setPositiveButton(R.string.StoriesLandingFragment__hide) { _, _ ->
        viewModel.setHideStory(model.data.storyRecipient, true).subscribe {
          Snackbar.make(cameraFab, R.string.StoriesLandingFragment__story_hidden, Snackbar.LENGTH_SHORT)
            .setAnchorView(cameraFab)
            .setAnimationMode(BaseTransientBottomBar.ANIMATION_MODE_FADE)
            .show()
        }
      }
      .setNegativeButton(android.R.string.cancel) { _, _ -> }
      .show()
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    return if (item.itemId == R.id.action_settings) {
      startActivityIfAble(StorySettingsActivity.getIntent(requireContext()))
      true
    } else {
      false
    }
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
  }

  private fun startActivityIfAble(intent: Intent, options: Bundle? = null) {
    if (viewModel.isTransitioningToAnotherScreen) {
      return
    }

    viewModel.isTransitioningToAnotherScreen = true
    startActivity(intent, options)
  }
}
