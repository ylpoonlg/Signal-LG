/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import org.signal.core.util.concurrent.LifecycleDisposable
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.databinding.PromptBatterySaverBottomSheetBinding
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.util.BottomSheetUtil
import org.thoughtcrime.securesms.util.PowerManagerCompat

@RequiresApi(23)
class PromptBatterySaverDialogFragment : FixedRoundedCornerBottomSheetDialogFragment() {

  companion object {

    @JvmStatic
    fun show(fragmentManager: FragmentManager) {
      if (fragmentManager.findFragmentByTag(BottomSheetUtil.STANDARD_BOTTOM_SHEET_FRAGMENT_TAG) == null) {
        PromptBatterySaverDialogFragment().apply {
          arguments = bundleOf()
        }.show(fragmentManager, BottomSheetUtil.STANDARD_BOTTOM_SHEET_FRAGMENT_TAG)
        SignalStore.uiHints().lastBatterySaverPrompt = System.currentTimeMillis()
      }
    }
  }

  override val peekHeightPercentage: Float = 0.66f
  override val themeResId: Int = R.style.Widget_Signal_FixedRoundedCorners_Messages

  private val binding by ViewBinderDelegate(PromptBatterySaverBottomSheetBinding::bind)

  private val disposables: LifecycleDisposable = LifecycleDisposable()

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    return inflater.inflate(R.layout.prompt_battery_saver_bottom_sheet, container, false)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    disposables.bindTo(viewLifecycleOwner)

    binding.continueButton.setOnClickListener {
      PowerManagerCompat.requestIgnoreBatteryOptimizations(requireContext())
    }
    binding.dismissButton.setOnClickListener {
      SignalStore.uiHints().markDismissedBatterySaverPrompt()
      dismiss()
    }
  }
}
