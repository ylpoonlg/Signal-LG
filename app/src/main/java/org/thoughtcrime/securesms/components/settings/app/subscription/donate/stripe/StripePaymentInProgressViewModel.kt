package org.thoughtcrime.securesms.components.settings.app.subscription.donate.stripe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.wallet.PaymentData
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.kotlin.subscribeBy
import org.signal.core.util.logging.Log
import org.signal.donations.GooglePayPaymentSource
import org.signal.donations.PaymentSourceType
import org.signal.donations.StripeApi
import org.signal.donations.StripeIntentAccessor
import org.thoughtcrime.securesms.components.settings.app.subscription.MonthlyDonationRepository
import org.thoughtcrime.securesms.components.settings.app.subscription.OneTimeDonationRepository
import org.thoughtcrime.securesms.components.settings.app.subscription.StripeRepository
import org.thoughtcrime.securesms.components.settings.app.subscription.donate.DonateToSignalType
import org.thoughtcrime.securesms.components.settings.app.subscription.donate.DonationProcessorStage
import org.thoughtcrime.securesms.components.settings.app.subscription.donate.gateway.GatewayRequest
import org.thoughtcrime.securesms.components.settings.app.subscription.errors.DonationError
import org.thoughtcrime.securesms.components.settings.app.subscription.errors.DonationErrorSource
import org.thoughtcrime.securesms.components.settings.app.subscription.errors.toDonationError
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.jobs.MultiDeviceSubscriptionSyncRequestJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.util.rx.RxStore
import org.whispersystems.signalservice.api.util.Preconditions
import org.whispersystems.signalservice.internal.push.DonationProcessor
import org.whispersystems.signalservice.internal.push.exceptions.DonationProcessorError

class StripePaymentInProgressViewModel(
  private val stripeRepository: StripeRepository,
  private val monthlyDonationRepository: MonthlyDonationRepository,
  private val oneTimeDonationRepository: OneTimeDonationRepository
) : ViewModel() {

  companion object {
    private val TAG = Log.tag(StripePaymentInProgressViewModel::class.java)
  }

  private val store = RxStore(DonationProcessorStage.INIT)
  val state: Flowable<DonationProcessorStage> = store.stateFlowable.observeOn(AndroidSchedulers.mainThread())

  private val disposables = CompositeDisposable()
  private var stripePaymentData: StripePaymentData? = null

  override fun onCleared() {
    disposables.clear()
    store.dispose()
    clearPaymentInformation()
  }

  fun onBeginNewAction() {
    Preconditions.checkState(!store.state.isInProgress)

    Log.d(TAG, "Beginning a new action. Ensuring cleared state.", true)
    disposables.clear()
  }

  fun onEndAction() {
    Preconditions.checkState(store.state.isTerminal)

    Log.d(TAG, "Ending current state. Clearing state and setting stage to INIT", true)
    store.update { DonationProcessorStage.INIT }
    disposables.clear()
  }

  fun processNewDonation(request: GatewayRequest, nextActionHandler: (StripeApi.Secure3DSAction) -> Single<StripeIntentAccessor>) {
    Log.d(TAG, "Proceeding with donation...", true)

    val errorSource = when (request.donateToSignalType) {
      DonateToSignalType.ONE_TIME -> DonationErrorSource.BOOST
      DonateToSignalType.MONTHLY -> DonationErrorSource.SUBSCRIPTION
      DonateToSignalType.GIFT -> DonationErrorSource.GIFT
    }

    val paymentSourceProvider: PaymentSourceProvider = resolvePaymentSourceProvider(errorSource)

    return when (request.donateToSignalType) {
      DonateToSignalType.MONTHLY -> proceedMonthly(request, paymentSourceProvider, nextActionHandler)
      DonateToSignalType.ONE_TIME -> proceedOneTime(request, paymentSourceProvider, nextActionHandler)
      DonateToSignalType.GIFT -> proceedOneTime(request, paymentSourceProvider, nextActionHandler)
    }
  }

  private fun resolvePaymentSourceProvider(errorSource: DonationErrorSource): PaymentSourceProvider {
    return when (val data = stripePaymentData) {
      is StripePaymentData.GooglePay -> PaymentSourceProvider(
        PaymentSourceType.Stripe.GooglePay,
        Single.just<StripeApi.PaymentSource>(GooglePayPaymentSource(data.paymentData)).doAfterTerminate { clearPaymentInformation() }
      )
      is StripePaymentData.CreditCard -> PaymentSourceProvider(
        PaymentSourceType.Stripe.CreditCard,
        stripeRepository.createCreditCardPaymentSource(errorSource, data.cardData).doAfterTerminate { clearPaymentInformation() }
      )
      is StripePaymentData.SEPADebit -> PaymentSourceProvider(
        PaymentSourceType.Stripe.SEPADebit,
        stripeRepository.createSEPADebitPaymentSource(data.sepaDebitData).doAfterTerminate { clearPaymentInformation() }
      )
      else -> error("This should never happen.")
    }
  }

  fun providePaymentData(paymentData: PaymentData) {
    requireNoPaymentInformation()
    this.stripePaymentData = StripePaymentData.GooglePay(paymentData)
  }

  fun provideCardData(cardData: StripeApi.CardData) {
    requireNoPaymentInformation()
    this.stripePaymentData = StripePaymentData.CreditCard(cardData)
  }

  fun provideSEPADebitData(bankData: StripeApi.SEPADebitData) {
    requireNoPaymentInformation()
    this.stripePaymentData = StripePaymentData.SEPADebit(bankData)
  }

  private fun requireNoPaymentInformation() {
    require(stripePaymentData == null)
  }

  private fun clearPaymentInformation() {
    Log.d(TAG, "Cleared payment information.", true)
    stripePaymentData = null
  }

  private fun proceedMonthly(request: GatewayRequest, paymentSourceProvider: PaymentSourceProvider, nextActionHandler: (StripeApi.Secure3DSAction) -> Single<StripeIntentAccessor>) {
    val ensureSubscriberId: Completable = monthlyDonationRepository.ensureSubscriberId()
    val createAndConfirmSetupIntent: Single<StripeApi.Secure3DSAction> = paymentSourceProvider.paymentSource.flatMap {
      stripeRepository.createAndConfirmSetupIntent(it, paymentSourceProvider.paymentSourceType as PaymentSourceType.Stripe)
    }

    val setLevel: Completable = monthlyDonationRepository.setSubscriptionLevel(request.level.toString(), request.uiSessionKey)

    Log.d(TAG, "Starting subscription payment pipeline...", true)
    store.update { DonationProcessorStage.PAYMENT_PIPELINE }

    val setup: Completable = ensureSubscriberId
      .andThen(monthlyDonationRepository.cancelActiveSubscriptionIfNecessary())
      .andThen(createAndConfirmSetupIntent)
      .flatMap { secure3DSAction ->
        nextActionHandler(secure3DSAction)
          .flatMap { secure3DSResult -> stripeRepository.getStatusAndPaymentMethodId(secure3DSResult) }
          .map { (_, paymentMethod) -> paymentMethod ?: secure3DSAction.paymentMethodId!! }
      }
      .flatMapCompletable { stripeRepository.setDefaultPaymentMethod(it, paymentSourceProvider.paymentSourceType) }
      .onErrorResumeNext {
        when {
          it is DonationError -> Completable.error(it)
          it is DonationProcessorError -> Completable.error(it.toDonationError(DonationErrorSource.SUBSCRIPTION, paymentSourceProvider.paymentSourceType))
          else -> Completable.error(DonationError.getPaymentSetupError(DonationErrorSource.SUBSCRIPTION, it, paymentSourceProvider.paymentSourceType))
        }
      }

    disposables += setup.andThen(setLevel).subscribeBy(
      onError = { throwable ->
        Log.w(TAG, "Failure in subscription payment pipeline...", throwable, true)
        store.update { DonationProcessorStage.FAILED }

        val donationError: DonationError = if (throwable is DonationError) {
          throwable
        } else {
          DonationError.genericBadgeRedemptionFailure(DonationErrorSource.SUBSCRIPTION)
        }
        DonationError.routeDonationError(ApplicationDependencies.getApplication(), donationError)
      },
      onComplete = {
        Log.d(TAG, "Finished subscription payment pipeline...", true)
        store.update { DonationProcessorStage.COMPLETE }
      }
    )
  }

  private fun proceedOneTime(
    request: GatewayRequest,
    paymentSourceProvider: PaymentSourceProvider,
    nextActionHandler: (StripeApi.Secure3DSAction) -> Single<StripeIntentAccessor>
  ) {
    Log.w(TAG, "Beginning one-time payment pipeline...", true)

    val amount = request.fiat
    val verifyUser = if (request.donateToSignalType == DonateToSignalType.GIFT) {
      OneTimeDonationRepository.verifyRecipientIsAllowedToReceiveAGift(request.recipientId)
    } else {
      Completable.complete()
    }

    val continuePayment: Single<StripeIntentAccessor> = verifyUser.andThen(stripeRepository.continuePayment(amount, request.recipientId, request.level, paymentSourceProvider.paymentSourceType))
    val intentAndSource: Single<Pair<StripeIntentAccessor, StripeApi.PaymentSource>> = Single.zip(continuePayment, paymentSourceProvider.paymentSource, ::Pair)

    disposables += intentAndSource.flatMapCompletable { (paymentIntent, paymentSource) ->
      stripeRepository.confirmPayment(paymentSource, paymentIntent, request.recipientId)
        .flatMap { nextActionHandler(it) }
        .flatMap { stripeRepository.getStatusAndPaymentMethodId(it) }
        .flatMapCompletable {
          oneTimeDonationRepository.waitForOneTimeRedemption(
            price = amount,
            paymentIntentId = paymentIntent.intentId,
            badgeRecipient = request.recipientId,
            additionalMessage = request.additionalMessage,
            badgeLevel = request.level,
            donationProcessor = DonationProcessor.STRIPE,
            uiSessionKey = request.uiSessionKey
          )
        }
    }.subscribeBy(
      onError = { throwable ->
        Log.w(TAG, "Failure in one-time payment pipeline...", throwable, true)
        store.update { DonationProcessorStage.FAILED }

        val donationError: DonationError = when (throwable) {
          is DonationError -> throwable
          is DonationProcessorError -> throwable.toDonationError(request.donateToSignalType.toErrorSource(), paymentSourceProvider.paymentSourceType)
          else -> DonationError.genericBadgeRedemptionFailure(request.donateToSignalType.toErrorSource())
        }
        DonationError.routeDonationError(ApplicationDependencies.getApplication(), donationError)
      },
      onComplete = {
        Log.w(TAG, "Completed one-time payment pipeline...", true)
        store.update { DonationProcessorStage.COMPLETE }
      }
    )
  }

  fun cancelSubscription() {
    Log.d(TAG, "Beginning cancellation...", true)

    store.update { DonationProcessorStage.CANCELLING }
    disposables += monthlyDonationRepository.cancelActiveSubscription().subscribeBy(
      onComplete = {
        Log.d(TAG, "Cancellation succeeded", true)
        SignalStore.donationsValues().updateLocalStateForManualCancellation()
        MultiDeviceSubscriptionSyncRequestJob.enqueue()
        stripeRepository.scheduleSyncForAccountRecordChange()
        store.update { DonationProcessorStage.COMPLETE }
      },
      onError = { throwable ->
        Log.w(TAG, "Cancellation failed", throwable, true)
        store.update { DonationProcessorStage.FAILED }
      }
    )
  }

  fun updateSubscription(request: GatewayRequest) {
    Log.d(TAG, "Beginning subscription update...", true)

    store.update { DonationProcessorStage.PAYMENT_PIPELINE }
    disposables += monthlyDonationRepository.cancelActiveSubscriptionIfNecessary().andThen(monthlyDonationRepository.setSubscriptionLevel(request.level.toString(), request.uiSessionKey))
      .subscribeBy(
        onComplete = {
          Log.w(TAG, "Completed subscription update", true)
          store.update { DonationProcessorStage.COMPLETE }
        },
        onError = { throwable ->
          Log.w(TAG, "Failed to update subscription", throwable, true)
          val donationError: DonationError = when (throwable) {
            is DonationError -> throwable
            is DonationProcessorError -> throwable.toDonationError(DonationErrorSource.SUBSCRIPTION, PaymentSourceType.Stripe.GooglePay)
            else -> DonationError.genericBadgeRedemptionFailure(DonationErrorSource.SUBSCRIPTION)
          }
          DonationError.routeDonationError(ApplicationDependencies.getApplication(), donationError)

          store.update { DonationProcessorStage.FAILED }
        }
      )
  }

  private data class PaymentSourceProvider(
    val paymentSourceType: PaymentSourceType,
    val paymentSource: Single<StripeApi.PaymentSource>
  )

  private sealed interface StripePaymentData {
    class GooglePay(val paymentData: PaymentData) : StripePaymentData
    class CreditCard(val cardData: StripeApi.CardData) : StripePaymentData
    class SEPADebit(val sepaDebitData: StripeApi.SEPADebitData) : StripePaymentData
  }

  class Factory(
    private val stripeRepository: StripeRepository,
    private val monthlyDonationRepository: MonthlyDonationRepository = MonthlyDonationRepository(ApplicationDependencies.getDonationsService()),
    private val oneTimeDonationRepository: OneTimeDonationRepository = OneTimeDonationRepository(ApplicationDependencies.getDonationsService())
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return modelClass.cast(StripePaymentInProgressViewModel(stripeRepository, monthlyDonationRepository, oneTimeDonationRepository)) as T
    }
  }
}
