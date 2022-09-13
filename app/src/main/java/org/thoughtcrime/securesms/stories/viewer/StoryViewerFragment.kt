package org.thoughtcrime.securesms.stories.viewer

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.viewpager2.widget.ViewPager2
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.stories.StoryViewerArgs
import org.thoughtcrime.securesms.stories.viewer.page.StoryViewerPageFragment
import org.thoughtcrime.securesms.stories.viewer.reply.StoriesSharedElementCrossFaderView
import org.thoughtcrime.securesms.util.LifecycleDisposable

/**
 * Fragment which manages a vertical pager fragment of stories.
 */
class StoryViewerFragment :
  Fragment(R.layout.stories_viewer_fragment),
  StoryViewerPageFragment.Callback,
  StoriesSharedElementCrossFaderView.Callback {

  private val onPageChanged = OnPageChanged()

  private lateinit var storyPager: ViewPager2

  private val viewModel: StoryViewerViewModel by viewModels(
    factoryProducer = {
      StoryViewerViewModel.Factory(storyViewerArgs, StoryViewerRepository())
    }
  )

  private val lifecycleDisposable = LifecycleDisposable()

  private val storyViewerArgs: StoryViewerArgs by lazy { requireArguments().getParcelable(ARGS)!! }

  private lateinit var storyCrossfader: StoriesSharedElementCrossFaderView

  private var pagerOnPageSelectedLock: Boolean = false

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    storyCrossfader = view.findViewById(R.id.story_content_crossfader)
    storyPager = view.findViewById(R.id.story_item_pager)

    storyCrossfader.callback = this

    val adapter = StoryViewerPagerAdapter(
      this,
      storyViewerArgs.storyId,
      storyViewerArgs.isFromNotification,
      storyViewerArgs.groupReplyStartPosition,
      storyViewerArgs.isUnviewedOnly,
      storyViewerArgs.isFromMyStories,
      storyViewerArgs.isFromInfoContextMenuAction
    )

    storyPager.adapter = adapter
    storyPager.overScrollMode = ViewPager2.OVER_SCROLL_NEVER

    lifecycleDisposable += viewModel.allowParentScrolling.observeOn(AndroidSchedulers.mainThread()).subscribe {
      storyPager.isUserInputEnabled = it
    }

    storyPager.offscreenPageLimit = 1

    lifecycleDisposable.bindTo(viewLifecycleOwner)
    lifecycleDisposable += viewModel.state.observeOn(AndroidSchedulers.mainThread()).subscribe { state ->
      adapter.setPages(state.pages)
      if (state.pages.isNotEmpty() && storyPager.currentItem != state.page) {
        pagerOnPageSelectedLock = true
        storyPager.isUserInputEnabled = false
        storyPager.setCurrentItem(state.page, state.previousPage > -1)
        pagerOnPageSelectedLock = false

        if (state.page >= state.pages.size) {
          requireActivity().onBackPressed()
        }
      }

      when (state.crossfadeSource) {
        is StoryViewerState.CrossfadeSource.TextModel -> storyCrossfader.setSourceView(state.crossfadeSource.storyTextPostModel)
        is StoryViewerState.CrossfadeSource.ImageUri -> storyCrossfader.setSourceView(state.crossfadeSource.imageUri, state.crossfadeSource.imageBlur)
      }

      if (state.crossfadeTarget is StoryViewerState.CrossfadeTarget.Record) {
        storyCrossfader.setTargetView(state.crossfadeTarget.messageRecord)
        requireActivity().supportStartPostponedEnterTransition()
      }

      if (state.skipCrossfade) {
        viewModel.setCrossfaderIsReady(true)
      }

      if (state.loadState.isReady()) {
        storyCrossfader.alpha = 0f
      }
    }
  }

  override fun onResume() {
    super.onResume()
    viewModel.setIsScrolling(false)
    storyPager.registerOnPageChangeCallback(onPageChanged)
  }

  override fun onPause() {
    super.onPause()
    viewModel.setIsScrolling(false)
    storyPager.unregisterOnPageChangeCallback(onPageChanged)
  }

  override fun onGoToPreviousStory(recipientId: RecipientId) {
    viewModel.onGoToPrevious(recipientId)
  }

  override fun onFinishedPosts(recipientId: RecipientId) {
    viewModel.onGoToNext(recipientId)
  }

  override fun onStoryHidden(recipientId: RecipientId) {
    viewModel.onRecipientHidden()
  }

  override fun onReadyToAnimate() {
  }

  override fun onAnimationStarted() {
    viewModel.setCrossfaderIsReady(false)
  }

  override fun onAnimationFinished() {
    viewModel.setCrossfaderIsReady(true)
  }

  inner class OnPageChanged : ViewPager2.OnPageChangeCallback() {
    override fun onPageSelected(position: Int) {
      if (!pagerOnPageSelectedLock) {
        viewModel.setSelectedPage(position)
      }
    }

    override fun onPageScrollStateChanged(state: Int) {
      viewModel.setIsScrolling(state == ViewPager2.SCROLL_STATE_DRAGGING)
      if (state == ViewPager2.SCROLL_STATE_IDLE) {
        storyPager.isUserInputEnabled = true
      }
    }
  }

  companion object {
    private const val ARGS = "args"

    fun create(storyViewerArgs: StoryViewerArgs): Fragment {
      return StoryViewerFragment().apply {
        arguments = Bundle().apply {
          putParcelable(ARGS, storyViewerArgs)
        }
      }
    }
  }
}
