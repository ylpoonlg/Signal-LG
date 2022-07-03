package org.thoughtcrime.securesms.stories.viewer.page

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.Interpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.animation.PathInterpolatorCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.button.MaterialButton
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import org.signal.core.util.DimensionUnit
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.AvatarImageView
import org.thoughtcrime.securesms.components.segmentedprogressbar.SegmentedProgressBar
import org.thoughtcrime.securesms.components.segmentedprogressbar.SegmentedProgressBarListener
import org.thoughtcrime.securesms.contacts.avatars.FallbackContactPhoto
import org.thoughtcrime.securesms.contacts.avatars.FallbackPhoto20dp
import org.thoughtcrime.securesms.contacts.avatars.GeneratedContactPhoto
import org.thoughtcrime.securesms.conversation.ConversationIntents
import org.thoughtcrime.securesms.conversation.colors.AvatarColor
import org.thoughtcrime.securesms.conversation.mutiselect.forward.MultiselectForwardBottomSheet
import org.thoughtcrime.securesms.conversation.mutiselect.forward.MultiselectForwardFragment
import org.thoughtcrime.securesms.conversation.mutiselect.forward.MultiselectForwardFragmentArgs
import org.thoughtcrime.securesms.database.AttachmentDatabase
import org.thoughtcrime.securesms.database.model.MediaMmsMessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.mediapreview.MediaPreviewFragment
import org.thoughtcrime.securesms.mediapreview.VideoControlsDelegate
import org.thoughtcrime.securesms.mms.GlideApp
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.stories.StoryFirstTimeNavigationView
import org.thoughtcrime.securesms.stories.StorySlateView
import org.thoughtcrime.securesms.stories.StoryVolumeOverlayView
import org.thoughtcrime.securesms.stories.dialogs.StoryContextMenu
import org.thoughtcrime.securesms.stories.viewer.StoryViewerState
import org.thoughtcrime.securesms.stories.viewer.StoryViewerViewModel
import org.thoughtcrime.securesms.stories.viewer.StoryVolumeViewModel
import org.thoughtcrime.securesms.stories.viewer.reply.StoriesSharedElementCrossFaderView
import org.thoughtcrime.securesms.stories.viewer.reply.direct.StoryDirectReplyDialogFragment
import org.thoughtcrime.securesms.stories.viewer.reply.group.StoryGroupReplyBottomSheetDialogFragment
import org.thoughtcrime.securesms.stories.viewer.reply.reaction.OnReactionSentView
import org.thoughtcrime.securesms.stories.viewer.reply.tabs.StoryViewsAndRepliesDialogFragment
import org.thoughtcrime.securesms.stories.viewer.text.StoryTextPostPreviewFragment
import org.thoughtcrime.securesms.stories.viewer.views.StoryViewsBottomSheetDialogFragment
import org.thoughtcrime.securesms.util.AvatarUtil
import org.thoughtcrime.securesms.util.BottomSheetUtil
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.Debouncer
import org.thoughtcrime.securesms.util.LifecycleDisposable
import org.thoughtcrime.securesms.util.ServiceUtil
import org.thoughtcrime.securesms.util.ViewUtil
import org.thoughtcrime.securesms.util.fragments.requireListener
import org.thoughtcrime.securesms.util.views.TouchInterceptingFrameLayout
import org.thoughtcrime.securesms.util.visible
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class StoryViewerPageFragment :
  Fragment(R.layout.stories_viewer_fragment_page),
  MediaPreviewFragment.Events,
  MultiselectForwardBottomSheet.Callback,
  StorySlateView.Callback,
  StoryTextPostPreviewFragment.Callback,
  StoriesSharedElementCrossFaderView.Callback,
  StoryFirstTimeNavigationView.Callback {

  private val activityViewModel: StoryVolumeViewModel by viewModels(ownerProducer = { requireActivity() })

  private lateinit var progressBar: SegmentedProgressBar
  private lateinit var storySlate: StorySlateView
  private lateinit var viewsAndReplies: MaterialButton
  private lateinit var storyCrossfader: StoriesSharedElementCrossFaderView
  private lateinit var blurContainer: ImageView
  private lateinit var storyCaptionContainer: FrameLayout
  private lateinit var storyContentContainer: FrameLayout
  private lateinit var storyFirstTimeNavigationViewStub: StoryFirstNavigationStub

  private lateinit var callback: Callback

  private lateinit var chrome: List<View>
  private var animatorSet: AnimatorSet? = null

  private var volumeInAnimator: Animator? = null
  private var volumeOutAnimator: Animator? = null
  private var volumeDebouncer: Debouncer = Debouncer(3, TimeUnit.SECONDS)

  private val viewModel: StoryViewerPageViewModel by viewModels(
    factoryProducer = {
      StoryViewerPageViewModel.Factory(storyRecipientId, initialStoryId, isUnviewedOnly, StoryViewerPageRepository(requireContext()))
    }
  )

  private val sharedViewModel: StoryViewerViewModel by viewModels(
    ownerProducer = { requireParentFragment() }
  )

  private val videoControlsDelegate = VideoControlsDelegate()

  private val lifecycleDisposable = LifecycleDisposable()
  private val timeoutDisposable = LifecycleDisposable()

  private val storyRecipientId: RecipientId
    get() = requireArguments().getParcelable(ARG_STORY_RECIPIENT_ID)!!

  private val initialStoryId: Long
    get() = requireArguments().getLong(ARG_STORY_ID, -1L)

  private val isFromNotification: Boolean
    get() = requireArguments().getBoolean(ARG_IS_FROM_NOTIFICATION, false)

  private val groupReplyStartPosition: Int
    get() = requireArguments().getInt(ARG_GROUP_REPLY_START_POSITION, -1)

  private val isUnviewedOnly: Boolean
    get() = requireArguments().getBoolean(ARG_IS_UNVIEWED_ONLY, false)

  @SuppressLint("ClickableViewAccessibility")
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    callback = requireListener()

    if (activityViewModel.snapshot.isMuted) {
      videoControlsDelegate.mute()
    } else {
      videoControlsDelegate.unmute()
    }

    val closeView: View = view.findViewById(R.id.close)
    val senderAvatar: AvatarImageView = view.findViewById(R.id.sender_avatar)
    val groupAvatar: AvatarImageView = view.findViewById(R.id.group_avatar)
    val from: TextView = view.findViewById(R.id.from)
    val date: TextView = view.findViewById(R.id.date)
    val moreButton: View = view.findViewById(R.id.more)
    val distributionList: TextView = view.findViewById(R.id.distribution_list)
    val cardWrapper: TouchInterceptingFrameLayout = view.findViewById(R.id.story_content_card_touch_interceptor)
    val card: CardView = view.findViewById(R.id.story_content_card)
    val caption: TextView = view.findViewById(R.id.story_caption)
    val largeCaption: TextView = view.findViewById(R.id.story_large_caption)
    val largeCaptionOverlay: View = view.findViewById(R.id.story_large_caption_overlay)
    val reactionAnimationView: OnReactionSentView = view.findViewById(R.id.on_reaction_sent_view)
    val storyGradientTop: View = view.findViewById(R.id.story_gradient_top)
    val storyGradientBottom: View = view.findViewById(R.id.story_gradient_bottom)
    val storyVolumeOverlayView: StoryVolumeOverlayView = view.findViewById(R.id.story_volume_overlay)

    blurContainer = view.findViewById(R.id.story_blur_container)
    storyContentContainer = view.findViewById(R.id.story_content_container)
    storyCaptionContainer = view.findViewById(R.id.story_caption_container)
    storySlate = view.findViewById(R.id.story_slate)
    progressBar = view.findViewById(R.id.progress)
    viewsAndReplies = view.findViewById(R.id.views_and_replies_bar)
    storyCrossfader = view.findViewById(R.id.story_content_crossfader)
    storyFirstTimeNavigationViewStub = StoryFirstNavigationStub(view.findViewById(R.id.story_first_time_nav_stub))

    storySlate.callback = this
    storyCrossfader.callback = this
    storyFirstTimeNavigationViewStub.setCallback(this)

    chrome = listOf(
      closeView,
      senderAvatar,
      groupAvatar,
      from,
      date,
      moreButton,
      distributionList,
      viewsAndReplies,
      progressBar,
      storyGradientTop,
      storyGradientBottom,
    )

    senderAvatar.setFallbackPhotoProvider(FallbackPhotoProvider())
    groupAvatar.setFallbackPhotoProvider(FallbackPhotoProvider())

    closeView.setOnClickListener {
      requireActivity().onBackPressed()
    }

    val gestureDetector = GestureDetectorCompat(
      requireContext(),
      StoryGestureListener(
        cardWrapper,
        viewModel::goToNextPost,
        viewModel::goToPreviousPost,
        this::startReply,
        sharedViewModel = sharedViewModel
      )
    )

    cardWrapper.setOnInterceptTouchEventListener { storySlate.state == StorySlateView.State.HIDDEN && childFragmentManager.findFragmentById(R.id.story_content_container) !is StoryTextPostPreviewFragment }
    cardWrapper.setOnTouchListener { _, event ->
      val result = gestureDetector.onTouchEvent(event)
      if (event.actionMasked == MotionEvent.ACTION_DOWN) {
        viewModel.setIsUserTouching(true)
      } else if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
        viewModel.setIsUserTouching(false)

        val canCloseFromHorizontalSlide = requireView().translationX > DimensionUnit.DP.toPixels(56f)
        val canCloseFromVerticalSlide = requireView().translationY > DimensionUnit.DP.toPixels(56f) || requireView().translationY < -DimensionUnit.DP.toPixels(56f)
        if ((canCloseFromHorizontalSlide || canCloseFromVerticalSlide) && event.actionMasked == MotionEvent.ACTION_UP) {
          requireActivity().onBackPressed()
        } else {
          requireView().animate()
            .setInterpolator(StoryGestureListener.INTERPOLATOR)
            .setDuration(100)
            .translationX(0f)
            .translationY(0f)
        }
      }

      result
    }

    viewsAndReplies.setOnClickListener {
      startReply()
    }

    moreButton.setOnClickListener(this::displayMoreContextMenu)

    progressBar.listener = object : SegmentedProgressBarListener {
      override fun onPage(oldPageIndex: Int, newPageIndex: Int) {
        if (oldPageIndex != newPageIndex && context != null) {
          viewModel.setSelectedPostIndex(newPageIndex)
        }
      }

      override fun onFinished() {
        viewModel.goToNextPost()
      }

      override fun onRequestSegmentProgressPercentage(): Float? {
        val attachmentUri = if (viewModel.hasPost() && viewModel.getPost().content.isVideo()) {
          viewModel.getPost().content.uri
        } else {
          null
        }

        return if (attachmentUri != null) {
          val playerState = videoControlsDelegate.getPlayerState(attachmentUri)
          if (playerState != null) {
            getVideoPlaybackPosition(playerState) / getVideoPlaybackDuration(playerState)
          } else {
            null
          }
        } else {
          null
        }
      }
    }

    reactionAnimationView.callback = object : OnReactionSentView.Callback {
      override fun onFinished() {
        viewModel.setIsDisplayingReactionAnimation(false)
      }
    }

    sharedViewModel.isScrolling.observe(viewLifecycleOwner) { isScrolling ->
      viewModel.setIsUserScrollingParent(isScrolling)
    }

    lifecycleDisposable += activityViewModel.state.distinctUntilChanged().observeOn(AndroidSchedulers.mainThread()).subscribe { volumeState ->
      if (volumeState.isMuted) {
        videoControlsDelegate.mute()
        return@subscribe
      }

      if (!viewModel.hasPost() || !viewModel.getPost().content.isVideo() || volumeState.level < 0) {
        return@subscribe
      }

      if (!volumeState.isMuted) {
        videoControlsDelegate.unmute()
      }

      val audioManager = ServiceUtil.getAudioManager(requireContext())
      if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != volumeState.level) {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volumeState.level, 0)
        storyVolumeOverlayView.setVolumeLevel(volumeState.level)
        storyVolumeOverlayView.setVideoHaNoAudio(!videoControlsDelegate.hasAudioStream())
        displayStoryVolumeOverlayForTimeout(storyVolumeOverlayView)
      }
    }

    lifecycleDisposable += sharedViewModel.state.distinctUntilChanged().observeOn(AndroidSchedulers.mainThread()).subscribe { parentState ->
      if (parentState.pages.size <= parentState.page) {
        viewModel.setIsSelectedPage(false)
      } else if (storyRecipientId == parentState.pages[parentState.page]) {
        if (progressBar.segmentCount != 0) {
          progressBar.reset()
          progressBar.setPosition(viewModel.getRestartIndex())
          videoControlsDelegate.restart()
        }
        viewModel.setIsFirstPage(parentState.page == 0)
        viewModel.setIsSelectedPage(true)
        when (parentState.crossfadeSource) {
          is StoryViewerState.CrossfadeSource.TextModel -> storyCrossfader.setSourceView(parentState.crossfadeSource.storyTextPostModel)
          is StoryViewerState.CrossfadeSource.ImageUri -> storyCrossfader.setSourceView(parentState.crossfadeSource.imageUri, parentState.crossfadeSource.imageBlur)
        }

        onReadyToAnimate()
      } else {
        viewModel.setIsSelectedPage(false)
      }
    }

    lifecycleDisposable += viewModel.state.observeOn(AndroidSchedulers.mainThread()).subscribe { state ->
      if (!state.isReady) {
        return@subscribe
      }

      if (state.posts.isNotEmpty() && state.selectedPostIndex in state.posts.indices) {
        val post = state.posts[state.selectedPostIndex]

        presentViewsAndReplies(post, state.replyState)
        presentSenderAvatar(senderAvatar, post)
        presentGroupAvatar(groupAvatar, post)
        presentFrom(from, post)
        presentDate(date, post)
        presentDistributionList(distributionList, post)
        presentCaption(caption, largeCaption, largeCaptionOverlay, post)
        presentBlur(blurContainer, post)

        val durations: Map<Int, Long> = state.posts
          .mapIndexed { index, storyPost ->
            index to when {
              storyPost.content.isVideo() -> -1L
              storyPost.content is StoryPost.Content.TextContent -> calculateDurationForText(storyPost.content)
              else -> DEFAULT_DURATION
            }
          }
          .toMap()

        if (progressBar.segmentCount != state.posts.size || progressBar.segmentDurations != durations) {
          progressBar.segmentCount = state.posts.size
          progressBar.segmentDurations = durations
        }

        presentStory(post, state.selectedPostIndex)
        presentSlate(post)

        if (!storyCrossfader.setTargetView(post.conversationMessage.messageRecord as MmsMessageRecord)) {
          onReadyToAnimate()
        }

        viewModel.setAreSegmentsInitialized(true)
      } else if (state.selectedPostIndex >= state.posts.size) {
        callback.onFinishedPosts(storyRecipientId)
      } else if (state.selectedPostIndex < 0) {
        callback.onGoToPreviousStory(storyRecipientId)
      }

      if (state.isDisplayingInitialState && isFromNotification && !sharedViewModel.hasConsumedInitialState) {
        sharedViewModel.consumeInitialState()
        startReply(isFromNotification = true, groupReplyStartPosition = groupReplyStartPosition)
      }
    }

    viewModel.storyViewerPlaybackState.observe(viewLifecycleOwner) { state ->
      if (state.isPaused) {
        pauseProgress()
      } else {
        resumeProgress()
      }

      val wasDisplayingNavigationView = storyFirstTimeNavigationViewStub.isVisible()

      when {
        state.hideChromeImmediate -> {
          hideChromeImmediate()
          storyCaptionContainer.visible = false
          storyFirstTimeNavigationViewStub.hide()
        }
        state.hideChrome -> {
          hideChrome()
          storyCaptionContainer.visible = true
          storyFirstTimeNavigationViewStub.showIfAble(!SignalStore.storyValues().userHasSeenFirstNavView)
        }
        else -> {
          showChrome()
          storyCaptionContainer.visible = true
          storyFirstTimeNavigationViewStub.showIfAble(!SignalStore.storyValues().userHasSeenFirstNavView)
        }
      }

      val isDisplayingNavigationView = storyFirstTimeNavigationViewStub.isVisible()
      if (isDisplayingNavigationView && Build.VERSION.SDK_INT >= 31) {
        hideChromeImmediate()
        storyContentContainer.setRenderEffect(RenderEffect.createBlurEffect(100f, 100f, Shader.TileMode.CLAMP))
      } else if (Build.VERSION.SDK_INT >= 31) {
        storyContentContainer.setRenderEffect(null)
      }

      if (wasDisplayingNavigationView xor isDisplayingNavigationView) {
        viewModel.setIsDisplayingFirstTimeNavigation(storyFirstTimeNavigationViewStub.isVisible())
      }
    }

    timeoutDisposable.bindTo(viewLifecycleOwner)
    lifecycleDisposable.bindTo(viewLifecycleOwner)
    lifecycleDisposable += viewModel.groupDirectReplyObservable.subscribe { opt ->
      if (opt.isPresent) {
        when (val sheet = opt.get()) {
          is StoryViewerDialog.GroupDirectReply -> {
            onStartDirectReply(sheet.storyId, sheet.recipientId)
          }
        }
      }
    }

    adjustConstraintsForScreenDimensions(viewsAndReplies, cardWrapper, card)

    childFragmentManager.setFragmentResultListener(StoryDirectReplyDialogFragment.REQUEST_EMOJI, viewLifecycleOwner) { _, bundle ->
      val emoji = bundle.getString(StoryDirectReplyDialogFragment.REQUEST_EMOJI)
      if (emoji != null) {
        reactionAnimationView.playForEmoji(emoji)
        viewModel.setIsDisplayingReactionAnimation(true)
      }
    }
  }

  override fun onResume() {
    super.onResume()
    viewModel.setIsFragmentResumed(true)
  }

  override fun onPause() {
    super.onPause()
    viewModel.setIsFragmentResumed(false)
  }

  override fun onDestroyView() {
    super.onDestroyView()
    childFragmentManager.fragments.forEach {
      if (it is MediaPreviewFragment) {
        it.cleanUp()
      }
    }

    volumeDebouncer.clear()
  }

  override fun onFinishForwardAction() = Unit

  override fun onDismissForwardSheet() {
    viewModel.setIsDisplayingForwardDialog(false)
  }

  private fun calculateDurationForText(textContent: StoryPost.Content.TextContent): Long {
    val divisionsOf15 = textContent.length / CHARACTERS_PER_SECOND
    return TimeUnit.SECONDS.toMillis(divisionsOf15) + MIN_TEXT_STORY_PLAYBACK
  }

  private fun getVideoPlaybackPosition(playerState: VideoControlsDelegate.PlayerState): Float {
    return if (playerState.isGif) {
      playerState.position.toFloat() + (playerState.duration * playerState.loopCount)
    } else {
      playerState.position.toFloat()
    }
  }

  private fun getVideoPlaybackDuration(playerState: VideoControlsDelegate.PlayerState): Long {
    return if (playerState.isGif) {
      val timeToPlayMinLoops = playerState.duration * MIN_GIF_LOOPS
      max(MIN_GIF_PLAYBACK_DURATION, timeToPlayMinLoops)
    } else {
      min(playerState.duration, MAX_VIDEO_PLAYBACK_DURATION)
    }
  }

  private fun displayStoryVolumeOverlayForTimeout(view: View) {
    if (volumeInAnimator?.isRunning != true) {
      volumeOutAnimator?.cancel()
      volumeInAnimator = ObjectAnimator.ofFloat(view, View.ALPHA, 1f).apply {
        duration = 200
        start()
      }
    }

    volumeDebouncer.publish {
      if (volumeOutAnimator?.isRunning != true) {
        volumeInAnimator?.cancel()
        volumeOutAnimator = ObjectAnimator.ofFloat(view, View.ALPHA, 0f).apply {
          duration = 200
          start()
        }
      }
    }
  }

  private fun hideChromeImmediate() {
    animatorSet?.cancel()
    chrome.map {
      it.alpha = 0f
    }
  }

  private fun hideChrome() {
    animateChrome(0f)
  }

  private fun showChrome() {
    animateChrome(1f)
  }

  private fun animateChrome(alphaTarget: Float) {
    animatorSet?.cancel()
    animatorSet = AnimatorSet().apply {
      duration = 100
      interpolator = StoryGestureListener.INTERPOLATOR
      playTogether(
        chrome.map {
          ObjectAnimator.ofFloat(it, View.ALPHA, alphaTarget)
        }
      )
      start()
    }
  }

  private fun adjustConstraintsForScreenDimensions(
    viewsAndReplies: View,
    cardWrapper: View,
    card: CardView
  ) {
    val constraintSet = ConstraintSet()
    constraintSet.clone(requireView() as ConstraintLayout)

    when (StoryDisplay.getStoryDisplay(resources.displayMetrics.widthPixels.toFloat(), resources.displayMetrics.heightPixels.toFloat())) {
      StoryDisplay.LARGE -> {
        constraintSet.setDimensionRatio(cardWrapper.id, "9:16")
        constraintSet.connect(viewsAndReplies.id, ConstraintSet.TOP, cardWrapper.id, ConstraintSet.BOTTOM)
        constraintSet.connect(viewsAndReplies.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        card.radius = DimensionUnit.DP.toPixels(18f)
      }
      StoryDisplay.MEDIUM -> {
        constraintSet.setDimensionRatio(cardWrapper.id, "9:16")
        constraintSet.clear(viewsAndReplies.id, ConstraintSet.TOP)
        constraintSet.connect(viewsAndReplies.id, ConstraintSet.BOTTOM, cardWrapper.id, ConstraintSet.BOTTOM)
        card.radius = DimensionUnit.DP.toPixels(18f)
      }
      StoryDisplay.SMALL -> {
        constraintSet.setDimensionRatio(cardWrapper.id, null)
        constraintSet.clear(viewsAndReplies.id, ConstraintSet.TOP)
        constraintSet.connect(viewsAndReplies.id, ConstraintSet.BOTTOM, cardWrapper.id, ConstraintSet.BOTTOM)
        card.radius = DimensionUnit.DP.toPixels(0f)
      }
    }

    constraintSet.applyTo(requireView() as ConstraintLayout)
  }

  private fun resumeProgress() {
    if (progressBar.segmentCount != 0 && viewModel.hasPost()) {
      val postUri = viewModel.getPost().content.uri
      if (postUri != null) {
        progressBar.start()
        videoControlsDelegate.resume(postUri)
      }
    }
  }

  private fun pauseProgress() {
    progressBar.pause()
    videoControlsDelegate.pause()
  }

  private fun startReply(isFromNotification: Boolean = false, groupReplyStartPosition: Int = -1) {
    val storyPostId: Long = viewModel.getPost().id
    val replyFragment: DialogFragment = when (viewModel.getSwipeToReplyState()) {
      StoryViewerPageState.ReplyState.NONE -> return
      StoryViewerPageState.ReplyState.SELF -> StoryViewsBottomSheetDialogFragment.create(storyPostId)
      StoryViewerPageState.ReplyState.GROUP -> StoryGroupReplyBottomSheetDialogFragment.create(
        storyPostId,
        viewModel.getPost().group!!.id,
        isFromNotification,
        groupReplyStartPosition
      )
      StoryViewerPageState.ReplyState.PRIVATE -> StoryDirectReplyDialogFragment.create(storyPostId)
      StoryViewerPageState.ReplyState.GROUP_SELF -> StoryViewsAndRepliesDialogFragment.create(
        storyPostId,
        viewModel.getPost().group!!.id,
        if (isFromNotification) StoryViewsAndRepliesDialogFragment.StartPage.REPLIES else getViewsAndRepliesDialogStartPage(),
        isFromNotification,
        groupReplyStartPosition
      )
    }

    if (viewModel.getSwipeToReplyState() == StoryViewerPageState.ReplyState.PRIVATE) {
      viewModel.setIsDisplayingDirectReplyDialog(true)
    } else {
      viewModel.setIsDisplayingViewsAndRepliesDialog(true)
    }

    replyFragment.showNow(childFragmentManager, BottomSheetUtil.STANDARD_BOTTOM_SHEET_FRAGMENT_TAG)
  }

  private fun onStartDirectReply(storyId: Long, recipientId: RecipientId) {
    viewModel.setIsDisplayingDirectReplyDialog(true)
    StoryDirectReplyDialogFragment.create(
      storyId = storyId,
      recipientId = recipientId
    ).show(childFragmentManager, BottomSheetUtil.STANDARD_BOTTOM_SHEET_FRAGMENT_TAG)
  }

  private fun getViewsAndRepliesDialogStartPage(): StoryViewsAndRepliesDialogFragment.StartPage {
    return if (viewModel.getPost().replyCount > 0) {
      StoryViewsAndRepliesDialogFragment.StartPage.REPLIES
    } else {
      StoryViewsAndRepliesDialogFragment.StartPage.VIEWS
    }
  }

  private fun presentStory(post: StoryPost, index: Int) {
    val fragment = childFragmentManager.findFragmentById(R.id.story_content_container)
    if (fragment != null && fragment.requireArguments().getParcelable<Uri>(MediaPreviewFragment.DATA_URI) == post.content.uri) {
      progressBar.setPosition(index)
      return
    }

    if (fragment is MediaPreviewFragment) {
      fragment.cleanUp()
    }

    if (post.content.uri == null) {
      progressBar.setPosition(index)
      progressBar.invalidate()
    } else {
      progressBar.setPosition(index)
      storySlate.moveToState(StorySlateView.State.HIDDEN, post.id)
      viewModel.setIsDisplayingSlate(false)
      childFragmentManager.beginTransaction()
        .replace(R.id.story_content_container, createFragmentForPost(post))
        .commitNow()
    }
  }

  private fun presentSlate(post: StoryPost) {
    storySlate.setBackground((post.conversationMessage.messageRecord as? MediaMmsMessageRecord)?.slideDeck?.thumbnailSlide?.placeholderBlur)

    when (post.content.transferState) {
      AttachmentDatabase.TRANSFER_PROGRESS_DONE -> {
        storySlate.moveToState(StorySlateView.State.HIDDEN, post.id)
        viewModel.setIsDisplayingSlate(false)

        if (post.content.transferState == AttachmentDatabase.TRANSFER_PROGRESS_DONE) {
          viewModel.markViewed(post)
        }
      }
      AttachmentDatabase.TRANSFER_PROGRESS_PENDING -> {
        storySlate.moveToState(StorySlateView.State.LOADING, post.id)
        viewModel.setIsDisplayingSlate(true)
      }
      AttachmentDatabase.TRANSFER_PROGRESS_STARTED -> {
        storySlate.moveToState(StorySlateView.State.LOADING, post.id)
        viewModel.setIsDisplayingSlate(true)
      }
      AttachmentDatabase.TRANSFER_PROGRESS_FAILED -> {
        storySlate.moveToState(StorySlateView.State.NOT_FOUND, post.id)
        viewModel.setIsDisplayingSlate(true)
      }
    }
  }

  override fun onStateChanged(state: StorySlateView.State, postId: Long) {
    if (state == StorySlateView.State.LOADING || state == StorySlateView.State.RETRY) {
      timeoutDisposable.disposables.clear()
      timeoutDisposable += Observable.interval(10, TimeUnit.SECONDS)
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe {
          storySlate.moveToState(StorySlateView.State.ERROR, postId)
        }

      viewModel.forceDownloadSelectedPost()
    } else {
      timeoutDisposable.disposables.clear()
    }

    viewsAndReplies.visible = state == StorySlateView.State.HIDDEN
  }

  private fun presentDistributionList(distributionList: TextView, storyPost: StoryPost) {
    distributionList.text = storyPost.distributionList?.getDisplayName(requireContext())
    distributionList.visible = storyPost.distributionList != null && !storyPost.distributionList.isMyStory
  }

  private fun presentBlur(blur: ImageView, storyPost: StoryPost) {
    val record = storyPost.conversationMessage.messageRecord as? MediaMmsMessageRecord
    val blurHash = record?.slideDeck?.thumbnailSlide?.placeholderBlur

    storyFirstTimeNavigationViewStub.setBlurHash(blurHash)

    if (blurHash == null) {
      GlideApp.with(blur).clear(blur)
    } else {
      GlideApp.with(blur).load(blurHash).into(blur)
    }
  }

  @SuppressLint("SetTextI18n")
  private fun presentCaption(caption: TextView, largeCaption: TextView, largeCaptionOverlay: View, storyPost: StoryPost) {
    val displayBody: String = if (storyPost.content is StoryPost.Content.AttachmentContent) {
      storyPost.content.attachment.caption ?: ""
    } else {
      ""
    }

    caption.text = displayBody
    largeCaption.text = displayBody
    caption.visible = displayBody.isNotEmpty()
    caption.requestLayout()

    caption.doOnNextLayout {
      val maxLines = 5
      if (displayBody.isNotEmpty() && caption.lineCount > maxLines) {
        val lastCharShown = caption.layout.getLineVisibleEnd(maxLines - 1)
        caption.maxLines = maxLines

        val seeMore = (getString(R.string.StoryViewerPageFragment__see_more))

        val seeMoreWidth = caption.paint.measureText(seeMore)
        var offset = seeMore.length
        while (true) {
          val start = lastCharShown - offset
          if (start < 0) {
            break
          }

          val widthOfRemovedChunk = caption.paint.measureText(displayBody.subSequence(start, lastCharShown).toString())
          if (widthOfRemovedChunk > seeMoreWidth) {
            break
          }

          offset += 1
        }

        caption.text = displayBody.substring(0, lastCharShown - offset) + seeMore
      }

      if (caption.text.length == displayBody.length) {
        caption.setOnClickListener(null)
        caption.isClickable = false
      } else {
        caption.setOnClickListener {
          onShowCaptionOverlay(caption, largeCaption, largeCaptionOverlay)
        }
      }
    }
  }

  private fun onShowCaptionOverlay(caption: TextView, largeCaption: TextView, largeCaptionOverlay: View) {
    caption.visible = false
    largeCaption.visible = true
    largeCaptionOverlay.visible = true
    largeCaptionOverlay.setOnClickListener {
      onHideCaptionOverlay(caption, largeCaption, largeCaptionOverlay)
    }
    viewModel.setIsDisplayingCaptionOverlay(true)
  }

  private fun onHideCaptionOverlay(caption: TextView, largeCaption: TextView, largeCaptionOverlay: View) {
    caption.visible = true
    largeCaption.visible = false
    largeCaptionOverlay.visible = false
    largeCaptionOverlay.setOnClickListener(null)
    viewModel.setIsDisplayingCaptionOverlay(false)
  }

  private fun presentFrom(from: TextView, storyPost: StoryPost) {
    val name = if (storyPost.sender.isSelf) {
      getString(R.string.StoryViewerPageFragment__you)
    } else {
      storyPost.sender.getDisplayName(requireContext())
    }

    if (storyPost.group != null) {
      from.text = getString(R.string.StoryViewerPageFragment__s_to_s, name, storyPost.group.getDisplayName(requireContext()))
    } else {
      from.text = name
    }
  }

  private fun presentDate(date: TextView, storyPost: StoryPost) {
    date.text = DateUtils.getBriefRelativeTimeSpanString(context, Locale.getDefault(), storyPost.dateInMilliseconds)
  }

  private fun presentSenderAvatar(senderAvatar: AvatarImageView, post: StoryPost) {
    AvatarUtil.loadIconIntoImageView(post.sender, senderAvatar, DimensionUnit.DP.toPixels(32f).toInt())
  }

  private fun presentGroupAvatar(groupAvatar: AvatarImageView, post: StoryPost) {
    if (post.group != null) {
      groupAvatar.setRecipient(post.group)
      groupAvatar.visible = true
    } else {
      groupAvatar.visible = false
    }
  }

  private fun presentViewsAndReplies(post: StoryPost, replyState: StoryViewerPageState.ReplyState) {
    if (replyState == StoryViewerPageState.ReplyState.NONE) {
      viewsAndReplies.visible = false
      return
    } else {
      viewsAndReplies.visible = true
    }

    val views = resources.getQuantityString(R.plurals.StoryViewerFragment__d_views, post.viewCount, post.viewCount)
    val replies = resources.getQuantityString(R.plurals.StoryViewerFragment__d_replies, post.replyCount, post.replyCount)

    if (Recipient.self() == post.sender) {
      if (post.replyCount == 0) {
        viewsAndReplies.setIconResource(R.drawable.ic_chevron_end_24)
        viewsAndReplies.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_END
        viewsAndReplies.text = views
      } else {
        viewsAndReplies.setIconResource(R.drawable.ic_chevron_end_24)
        viewsAndReplies.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_END
        viewsAndReplies.text = getString(R.string.StoryViewerFragment__s_s, views, replies)
      }
    } else if (post.replyCount > 0) {
      viewsAndReplies.setIconResource(R.drawable.ic_chevron_end_24)
      viewsAndReplies.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_END
      viewsAndReplies.text = replies
    } else if (post.group != null) {
      viewsAndReplies.setIconResource(R.drawable.ic_reply_24_outline)
      viewsAndReplies.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
      viewsAndReplies.setText(R.string.StoryViewerPageFragment__reply_to_group)
    } else {
      viewsAndReplies.setIconResource(R.drawable.ic_reply_24_outline)
      viewsAndReplies.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
      viewsAndReplies.setText(R.string.StoryViewerPageFragment__reply)
    }
  }

  private fun createFragmentForPost(storyPost: StoryPost): Fragment {
    return when (storyPost.content) {
      is StoryPost.Content.AttachmentContent -> MediaPreviewFragment.newInstance(storyPost.content.attachment, false)
      is StoryPost.Content.TextContent -> StoryTextPostPreviewFragment.create(storyPost.content)
    }
  }

  override fun setIsDisplayingLinkPreviewTooltip(isDisplayingLinkPreviewTooltip: Boolean) {
    viewModel.setIsDisplayingLinkPreviewTooltip(isDisplayingLinkPreviewTooltip)
  }

  override fun getVideoControlsDelegate(): VideoControlsDelegate {
    return videoControlsDelegate
  }

  private fun displayMoreContextMenu(anchor: View) {
    viewModel.setIsDisplayingContextMenu(true)
    StoryContextMenu.show(
      context = requireContext(),
      anchorView = anchor,
      storyViewerPageState = viewModel.getStateSnapshot(),
      onDismiss = {
        viewModel.setIsDisplayingContextMenu(false)
      },
      onForward = { storyPost ->
        viewModel.setIsDisplayingForwardDialog(true)
        MultiselectForwardFragmentArgs.create(
          requireContext(),
          storyPost.conversationMessage.multiselectCollection.toSet(),
        ) {
          MultiselectForwardFragment.showBottomSheet(childFragmentManager, it)
        }
      },
      onGoToChat = {
        startActivity(ConversationIntents.createBuilder(requireContext(), storyRecipientId, -1L).build())
      },
      onHide = {
        lifecycleDisposable += viewModel.hideStory().subscribe {
          callback.onStoryHidden(storyRecipientId)
        }
      },
      onShare = {
        StoryContextMenu.share(this, it.conversationMessage.messageRecord as MediaMmsMessageRecord)
      },
      onSave = {
        StoryContextMenu.save(requireContext(), it.conversationMessage.messageRecord)
      },
      onDelete = {
        viewModel.setIsDisplayingDeleteDialog(true)
        lifecycleDisposable += StoryContextMenu.delete(requireContext(), setOf(it.conversationMessage.messageRecord)).subscribe { _ ->
          viewModel.setIsDisplayingDeleteDialog(false)
          viewModel.refresh()
        }
      }
    )
  }

  companion object {
    private val MAX_VIDEO_PLAYBACK_DURATION: Long = TimeUnit.SECONDS.toMillis(30)
    private val MIN_GIF_LOOPS: Long = 3L
    private val MIN_GIF_PLAYBACK_DURATION = TimeUnit.SECONDS.toMillis(5)
    private val MIN_TEXT_STORY_PLAYBACK = TimeUnit.SECONDS.toMillis(3)
    private val CHARACTERS_PER_SECOND = 15L
    private val DEFAULT_DURATION = TimeUnit.SECONDS.toMillis(5)

    private const val ARG_STORY_RECIPIENT_ID = "arg.story.recipient.id"
    private const val ARG_STORY_ID = "arg.story.id"
    private const val ARG_IS_FROM_NOTIFICATION = "is_from_notification"
    private const val ARG_GROUP_REPLY_START_POSITION = "group_reply_start_position"
    private const val ARG_IS_UNVIEWED_ONLY = "is_unviewed_only"

    fun create(recipientId: RecipientId, initialStoryId: Long, isFromNotification: Boolean, groupReplyStartPosition: Int, isUnviewedOnly: Boolean): Fragment {
      return StoryViewerPageFragment().apply {
        arguments = Bundle().apply {
          putParcelable(ARG_STORY_RECIPIENT_ID, recipientId)
          putLong(ARG_STORY_ID, initialStoryId)
          putBoolean(ARG_IS_FROM_NOTIFICATION, isFromNotification)
          putInt(ARG_GROUP_REPLY_START_POSITION, groupReplyStartPosition)
          putBoolean(ARG_IS_UNVIEWED_ONLY, isUnviewedOnly)
        }
      }
    }
  }

  private class StoryGestureListener(
    private val container: View,
    private val onGoToNext: () -> Unit,
    private val onGoToPrevious: () -> Unit,
    private val onReplyToPost: () -> Unit,
    private val viewToTranslate: View = container.parent as View,
    private val sharedViewModel: StoryViewerViewModel
  ) : GestureDetector.SimpleOnGestureListener() {

    companion object {
      private const val BOUNDARY_NEXT = 0.80f
      private const val BOUNDARY_PREV = 1f - BOUNDARY_NEXT

      val INTERPOLATOR: Interpolator = PathInterpolatorCompat.create(0.4f, 0f, 0.2f, 1f)
    }

    private val maxSlide = DimensionUnit.DP.toPixels(56f * 2)

    override fun onDown(e: MotionEvent?): Boolean {
      return true
    }

    override fun onScroll(e1: MotionEvent, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
      val isFirstStory = sharedViewModel.stateSnapshot.page == 0
      val isLastStory = sharedViewModel.stateSnapshot.pages.lastIndex == sharedViewModel.stateSnapshot.page
      val isXMagnitudeGreaterThanYMagnitude = abs(distanceX) > abs(distanceY) || viewToTranslate.translationX > 0f
      val isFirstAndHasYTranslationOrNegativeY = isFirstStory && (viewToTranslate.translationY > 0f || distanceY < 0f)
      val isLastAndHasYTranslationOrNegativeY = isLastStory && (viewToTranslate.translationY < 0f || distanceY > 0f)

      sharedViewModel.setIsChildScrolling(isXMagnitudeGreaterThanYMagnitude || isFirstAndHasYTranslationOrNegativeY || isLastAndHasYTranslationOrNegativeY)
      if (isFirstStory) {
        val delta = max(0f, (e2.rawY - e1.rawY)) / 3f
        val percent = INTERPOLATOR.getInterpolation(delta / maxSlide)
        val distance = maxSlide * percent

        viewToTranslate.animate().cancel()
        viewToTranslate.translationY = distance
      }

      if (isLastStory) {
        val delta = max(0f, (e1.rawY - e2.rawY)) / 3f
        val percent = -INTERPOLATOR.getInterpolation(delta / maxSlide)
        val distance = maxSlide * percent

        viewToTranslate.animate().cancel()
        viewToTranslate.translationY = distance
      }

      val delta = max(0f, (e2.rawX - e1.rawX)) / 3f
      val percent = INTERPOLATOR.getInterpolation(delta / maxSlide)
      val distance = maxSlide * percent

      viewToTranslate.animate().cancel()
      viewToTranslate.translationX = distance

      return true
    }

    override fun onFling(e1: MotionEvent?, e2: MotionEvent?, velocityX: Float, velocityY: Float): Boolean {
      val isSideSwipe = abs(velocityX) > abs(velocityY)
      if (!isSideSwipe) {
        return false
      }

      if (viewToTranslate.translationX != 0f || viewToTranslate.translationY != 0f) {
        return false
      }

      if (ViewUtil.isLtr(container)) {
        if (velocityX < 0) {
          onReplyToPost()
        }
      } else if (velocityX > 0) {
        onReplyToPost()
      }

      return true
    }

    private fun getLeftBoundary(): Float {
      return if (container.layoutDirection == View.LAYOUT_DIRECTION_LTR) {
        BOUNDARY_PREV
      } else {
        BOUNDARY_NEXT
      }
    }

    private fun getRightBoundary(): Float {
      return if (container.layoutDirection == View.LAYOUT_DIRECTION_LTR) {
        BOUNDARY_NEXT
      } else {
        BOUNDARY_PREV
      }
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
      if (e.x < container.measuredWidth * getLeftBoundary()) {
        performLeftAction()
        return true
      } else if (e.x > container.measuredWidth - (container.measuredWidth * getRightBoundary())) {
        performRightAction()
        return true
      }

      return false
    }

    private fun performLeftAction() {
      if (container.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
        onGoToNext()
      } else {
        onGoToPrevious()
      }
    }

    private fun performRightAction() {
      if (container.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
        onGoToPrevious()
      } else {
        onGoToNext()
      }
    }
  }

  private class FallbackPhotoProvider : Recipient.FallbackPhotoProvider() {
    override fun getPhotoForGroup(): FallbackContactPhoto {
      return FallbackPhoto20dp(R.drawable.ic_group_outline_20)
    }

    override fun getPhotoForResolvingRecipient(): FallbackContactPhoto {
      throw UnsupportedOperationException("This provider does not support resolving recipients")
    }

    override fun getPhotoForLocalNumber(): FallbackContactPhoto {
      throw UnsupportedOperationException("This provider does not support local number")
    }

    override fun getPhotoForRecipientWithName(name: String, targetSize: Int): FallbackContactPhoto {
      return FixedSizeGeneratedContactPhoto(name, R.drawable.ic_profile_outline_20)
    }

    override fun getPhotoForRecipientWithoutName(): FallbackContactPhoto {
      return FallbackPhoto20dp(R.drawable.ic_profile_outline_20)
    }
  }

  private class FixedSizeGeneratedContactPhoto(name: String, fallbackResId: Int) : GeneratedContactPhoto(name, fallbackResId) {
    override fun newFallbackDrawable(context: Context, color: AvatarColor, inverted: Boolean): Drawable {
      return FallbackPhoto20dp(fallbackResId).asDrawable(context, color, inverted)
    }
  }

  override fun singleTapOnMedia(): Boolean {
    return false
  }

  override fun onMediaReady() {
    sharedViewModel.setContentIsReady()
  }

  override fun mediaNotAvailable() {
    sharedViewModel.setContentIsReady()
  }

  override fun onReadyToAnimate() {
    sharedViewModel.setCrossfaderIsReady()
  }

  override fun onAnimationStarted() {
    storyContentContainer.alpha = 0f
    blurContainer.alpha = 0f
    viewModel.setIsRunningSharedElementAnimation(true)
  }

  override fun onAnimationFinished() {
    storyContentContainer.alpha = 1f
    blurContainer.alpha = 1f
    viewModel.setIsRunningSharedElementAnimation(false)
  }

  interface Callback {
    fun onGoToPreviousStory(recipientId: RecipientId)
    fun onFinishedPosts(recipientId: RecipientId)
    fun onStoryHidden(recipientId: RecipientId)
  }

  override fun userHasSeenFirstNavigationView(): Boolean {
    return SignalStore.storyValues().userHasSeenFirstNavView
  }

  override fun onGotItClicked() {
    SignalStore.storyValues().userHasSeenFirstNavView = true
    viewModel.setIsDisplayingFirstTimeNavigation(false)
  }
}
