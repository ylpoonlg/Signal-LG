package org.thoughtcrime.securesms.components

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.view.Surface
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.Guideline
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.util.ServiceUtil
import org.thoughtcrime.securesms.util.ViewUtil

/**
 * A specialized [ConstraintLayout] that sets guidelines based on the window insets provided
 * by the system. For improved backwards-compatibility we must use [ViewCompat] for configuring
 * the inset change callbacks.
 *
 * In portrait mode these are how the guidelines will be configured:
 *
 * - [R.id.status_bar_guideline] is set to the bottom of the status bar
 * - [R.id.navigation_bar_guideline] is set to the top of the navigation bar
 * - [R.id.parent_start_guideline] is set to the start of the parent
 * - [R.id.parent_end_guideline] is set to the end of the parent
 * - [R.id.keyboard_guideline] will be set to the top of the keyboard and will
 *   change as the keyboard is shown or hidden
 *
 * In landscape, the spirit of the guidelines are maintained but their names may not
 * correlated exactly to the inset they are providing.
 *
 * These guidelines will only be updated if present in your layout, you can use
 * `<include layout="@layout/system_ui_guidelines" />` to quickly include them.
 */
@Suppress("LeakingThis")
open class InsetAwareConstraintLayout @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

  companion object {
    private val keyboardType = WindowInsetsCompat.Type.ime()
    private val windowTypes = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
  }

  private val statusBarGuideline: Guideline? by lazy { findViewById(R.id.status_bar_guideline) }
  private val navigationBarGuideline: Guideline? by lazy { findViewById(R.id.navigation_bar_guideline) }
  private val parentStartGuideline: Guideline? by lazy { findViewById(R.id.parent_start_guideline) }
  private val parentEndGuideline: Guideline? by lazy { findViewById(R.id.parent_end_guideline) }
  private val keyboardGuideline: Guideline? by lazy { findViewById(R.id.keyboard_guideline) }

  private val keyboardAnimator = KeyboardInsetAnimator()
  private val displayMetrics = DisplayMetrics()
  private var overridingKeyboard: Boolean = false

  init {
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, windowInsetsCompat ->
      applyInsets(windowInsets = windowInsetsCompat.getInsets(windowTypes), keyboardInsets = windowInsetsCompat.getInsets(keyboardType))
      windowInsetsCompat
    }

    if (attrs != null) {
      context.withStyledAttributes(attrs, R.styleable.InsetAwareConstraintLayout) {
        if (getBoolean(R.styleable.InsetAwareConstraintLayout_animateKeyboardChanges, false)) {
          ViewCompat.setWindowInsetsAnimationCallback(this@InsetAwareConstraintLayout, keyboardAnimator)
        }
      }
    }
  }

  private fun applyInsets(windowInsets: Insets, keyboardInsets: Insets) {
    val isLtr = ViewUtil.isLtr(this)

    statusBarGuideline?.setGuidelineBegin(windowInsets.top)
    navigationBarGuideline?.setGuidelineEnd(windowInsets.bottom)
    parentStartGuideline?.setGuidelineBegin(if (isLtr) windowInsets.left else windowInsets.right)
    parentEndGuideline?.setGuidelineEnd(if (isLtr) windowInsets.right else windowInsets.left)

    if (keyboardInsets.bottom > 0) {
      setKeyboardHeight(keyboardInsets.bottom)
      if (!keyboardAnimator.animating) {
        keyboardGuideline?.setGuidelineEnd(keyboardInsets.bottom)
      } else {
        keyboardAnimator.endingGuidelineEnd = keyboardInsets.bottom
      }
    } else if (!overridingKeyboard) {
      if (!keyboardAnimator.animating) {
        keyboardGuideline?.setGuidelineEnd(windowInsets.bottom)
      } else {
        keyboardAnimator.endingGuidelineEnd = windowInsets.bottom
      }
    }
  }

  protected fun overrideKeyboardGuidelineWithPreviousHeight() {
    overridingKeyboard = true
    keyboardGuideline?.setGuidelineEnd(getKeyboardHeight())
  }

  protected fun clearKeyboardGuidelineOverride() {
    overridingKeyboard = false
  }

  protected fun resetKeyboardGuideline() {
    clearKeyboardGuidelineOverride()
    keyboardGuideline?.setGuidelineEnd(navigationBarGuideline.guidelineEnd)
  }

  private fun getKeyboardHeight(): Int {
    val height = if (isLandscape()) {
      SignalStore.misc().keyboardLandscapeHeight
    } else {
      SignalStore.misc().keyboardPortraitHeight
    }

    return if (height <= 0) {
      resources.getDimensionPixelSize(R.dimen.default_custom_keyboard_size)
    } else {
      height
    }
  }

  private fun setKeyboardHeight(height: Int) {
    if (isLandscape()) {
      SignalStore.misc().keyboardLandscapeHeight = height
    } else {
      SignalStore.misc().keyboardPortraitHeight = height
    }
  }

  private fun isLandscape(): Boolean {
    val rotation = getDeviceRotation()
    return rotation == Surface.ROTATION_90
  }

  @Suppress("DEPRECATION")
  private fun getDeviceRotation(): Int {
    if (isInEditMode) {
      return Surface.ROTATION_0
    }

    if (Build.VERSION.SDK_INT >= 30) {
      context.display?.getRealMetrics(displayMetrics)
    } else {
      ServiceUtil.getWindowManager(context).defaultDisplay.getRealMetrics(displayMetrics)
    }

    return if (displayMetrics.widthPixels > displayMetrics.heightPixels) Surface.ROTATION_90 else Surface.ROTATION_0
  }

  private val Guideline?.guidelineEnd: Int
    get() = if (this == null) 0 else (layoutParams as LayoutParams).guideEnd

  /**
   * Adjusts the [keyboardGuideline] to move with the IME keyboard opening or closing.
   */
  private inner class KeyboardInsetAnimator : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {

    var animating = false
      private set

    private var startingGuidelineEnd: Int = 0
    var endingGuidelineEnd: Int = 0
      set(value) {
        field = value
        growing = value > startingGuidelineEnd
      }
    private var growing: Boolean = false

    override fun onPrepare(animation: WindowInsetsAnimationCompat) {
      if (overridingKeyboard) {
        return
      }

      animating = true
      startingGuidelineEnd = keyboardGuideline.guidelineEnd
    }

    override fun onProgress(insets: WindowInsetsCompat, runningAnimations: MutableList<WindowInsetsAnimationCompat>): WindowInsetsCompat {
      if (overridingKeyboard) {
        return insets
      }

      val imeAnimation = runningAnimations.find { it.typeMask and WindowInsetsCompat.Type.ime() != 0 }
      if (imeAnimation == null) {
        return insets
      }

      val estimatedKeyboardHeight: Int = if (growing) {
        endingGuidelineEnd * imeAnimation.interpolatedFraction
      } else {
        startingGuidelineEnd * (1f - imeAnimation.interpolatedFraction)
      }.toInt()

      if (growing) {
        keyboardGuideline?.setGuidelineEnd(estimatedKeyboardHeight.coerceAtLeast(startingGuidelineEnd))
      } else {
        keyboardGuideline?.setGuidelineEnd(estimatedKeyboardHeight.coerceAtLeast(endingGuidelineEnd))
      }

      return insets
    }

    override fun onEnd(animation: WindowInsetsAnimationCompat) {
      if (overridingKeyboard) {
        return
      }

      keyboardGuideline?.setGuidelineEnd(endingGuidelineEnd)
      animating = false
    }
  }
}
