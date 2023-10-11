/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.settings.app.subscription.donate.transfer.details

import org.signal.donations.StripeApi

data class BankTransferDetailsState(
  val name: String = "",
  val iban: String = "",
  val email: String = "",
  val ibanValidity: IBANValidator.Validity = IBANValidator.Validity.POTENTIALLY_VALID
) {
  val canProceed = name.isNotEmpty() && email.isNotEmpty() && ibanValidity == IBANValidator.Validity.COMPLETELY_VALID

  fun asSEPADebitData(): StripeApi.SEPADebitData {
    return StripeApi.SEPADebitData(
      iban = iban,
      name = name,
      email = email
    )
  }
}
