package org.thoughtcrime.securesms.components.settings.app.changenumber

import androidx.annotation.WorkerThread
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.util.logging.Log
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.util.KeyHelper
import org.signal.libsignal.protocol.util.Medium
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil
import org.thoughtcrime.securesms.crypto.PreKeyUtil
import org.thoughtcrime.securesms.database.IdentityDatabase
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.databaseprotos.PendingChangeNumberMetadata
import org.thoughtcrime.securesms.database.model.toProtoByteString
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.keyvalue.CertificateType
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.pin.KbsRepository
import org.thoughtcrime.securesms.pin.KeyBackupSystemWrongPinException
import org.thoughtcrime.securesms.pin.TokenData
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.registration.VerifyAccountRepository
import org.thoughtcrime.securesms.storage.StorageSyncHelper
import org.whispersystems.signalservice.api.KbsPinData
import org.whispersystems.signalservice.api.KeyBackupSystemNoDataException
import org.whispersystems.signalservice.api.SignalServiceAccountManager
import org.whispersystems.signalservice.api.SignalServiceMessageSender
import org.whispersystems.signalservice.api.account.ChangePhoneNumberRequest
import org.whispersystems.signalservice.api.push.PNI
import org.whispersystems.signalservice.api.push.ServiceId
import org.whispersystems.signalservice.api.push.ServiceIdType
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.api.push.SignedPreKeyEntity
import org.whispersystems.signalservice.internal.ServiceResponse
import org.whispersystems.signalservice.internal.push.OutgoingPushMessage
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.SyncMessage
import org.whispersystems.signalservice.internal.push.VerifyAccountResponse
import org.whispersystems.signalservice.internal.push.WhoAmIResponse
import org.whispersystems.signalservice.internal.push.exceptions.MismatchedDevicesException
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

private val TAG: String = Log.tag(ChangeNumberRepository::class.java)

class ChangeNumberRepository(
  private val accountManager: SignalServiceAccountManager = ApplicationDependencies.getSignalServiceAccountManager(),
  private val messageSender: SignalServiceMessageSender = ApplicationDependencies.getSignalServiceMessageSender()
) {

  fun ensureDecryptionsDrained(): Completable {
    return Completable.create { emitter ->
      ApplicationDependencies
        .getIncomingMessageObserver()
        .addDecryptionDrainedListener {
          emitter.onComplete()
        }
    }.subscribeOn(Schedulers.io())
  }

  fun changeNumber(code: String, newE164: String): Single<ServiceResponse<VerifyAccountResponse>> {
    return Single.fromCallable {
      var completed = false
      var attempts = 0
      lateinit var changeNumberResponse: ServiceResponse<VerifyAccountResponse>

      while (!completed && attempts < 5) {
        val (request: ChangePhoneNumberRequest, metadata: PendingChangeNumberMetadata) = createChangeNumberRequest(code, newE164, null)
        SignalStore.misc().setPendingChangeNumberMetadata(metadata)

        changeNumberResponse = accountManager.changeNumber(request)

        val possibleError: Throwable? = changeNumberResponse.applicationError.orElse(null)
        if (possibleError is MismatchedDevicesException) {
          messageSender.handleChangeNumberMismatchDevices(possibleError.mismatchedDevices)
          attempts++
        } else {
          completed = true
        }
      }

      changeNumberResponse
    }.subscribeOn(Schedulers.io())
      .onErrorReturn { t -> ServiceResponse.forExecutionError(t) }
  }

  fun changeNumber(
    code: String,
    newE164: String,
    pin: String,
    tokenData: TokenData
  ): Single<ServiceResponse<VerifyAccountRepository.VerifyAccountWithRegistrationLockResponse>> {
    return Single.fromCallable {
      val kbsData: KbsPinData
      val registrationLock: String

      try {
        kbsData = KbsRepository.restoreMasterKey(pin, tokenData.enclave, tokenData.basicAuth, tokenData.tokenResponse)!!
        registrationLock = kbsData.masterKey.deriveRegistrationLock()
      } catch (e: KeyBackupSystemWrongPinException) {
        return@fromCallable ServiceResponse.forExecutionError(e)
      } catch (e: KeyBackupSystemNoDataException) {
        return@fromCallable ServiceResponse.forExecutionError(e)
      } catch (e: IOException) {
        return@fromCallable ServiceResponse.forExecutionError(e)
      }

      var completed = false
      var attempts = 0
      lateinit var changeNumberResponse: ServiceResponse<VerifyAccountResponse>

      while (!completed && attempts < 5) {
        val (request: ChangePhoneNumberRequest, metadata: PendingChangeNumberMetadata) = createChangeNumberRequest(code, newE164, registrationLock)
        SignalStore.misc().setPendingChangeNumberMetadata(metadata)

        changeNumberResponse = accountManager.changeNumber(request)

        val possibleError: Throwable? = changeNumberResponse.applicationError.orElse(null)
        if (possibleError is MismatchedDevicesException) {
          messageSender.handleChangeNumberMismatchDevices(possibleError.mismatchedDevices)
          attempts++
        } else {
          completed = true
        }
      }

      VerifyAccountRepository.VerifyAccountWithRegistrationLockResponse.from(changeNumberResponse, kbsData)
    }.subscribeOn(Schedulers.io())
      .onErrorReturn { t -> ServiceResponse.forExecutionError(t) }
  }

  @Suppress("UsePropertyAccessSyntax")
  fun whoAmI(): Single<WhoAmIResponse> {
    return Single.fromCallable { ApplicationDependencies.getSignalServiceAccountManager().getWhoAmI() }
      .subscribeOn(Schedulers.io())
  }

  @WorkerThread
  fun changeLocalNumber(e164: String, pni: PNI): Single<Unit> {
    val oldStorageId: ByteArray? = Recipient.self().storageServiceId
    SignalDatabase.recipients.updateSelfPhone(e164, pni)
    val newStorageId: ByteArray? = Recipient.self().storageServiceId

    if (MessageDigest.isEqual(oldStorageId, newStorageId)) {
      Log.w(TAG, "Self storage id was not rotated, attempting to rotate again")
      SignalDatabase.recipients.rotateStorageId(Recipient.self().id)
      StorageSyncHelper.scheduleSyncForDataChange()
      val secondAttemptStorageId: ByteArray? = Recipient.self().storageServiceId
      if (MessageDigest.isEqual(oldStorageId, secondAttemptStorageId)) {
        Log.w(TAG, "Second attempt also failed to rotate storage id")
      }
    }

    ApplicationDependencies.getRecipientCache().clear()

    SignalStore.account().setE164(e164)
    SignalStore.account().setPni(pni)

    ApplicationDependencies.getGroupsV2Authorization().clear()

    val metadata: PendingChangeNumberMetadata? = SignalStore.misc().pendingChangeNumberMetadata
    if (metadata == null) {
      Log.w(TAG, "No change number metadata, this shouldn't happen")
      throw AssertionError("No change number metadata")
    }

    val originalPni = ServiceId.fromByteString(metadata.previousPni)

    if (originalPni == pni) {
      Log.i(TAG, "No change has occurred, PNI is unchanged: $pni")
    } else {
      val pniIdentityKeyPair = IdentityKeyPair(metadata.pniIdentityKeyPair.toByteArray())
      val pniRegistrationId = metadata.pniRegistrationId
      val pniSignedPreyKeyId = metadata.pniSignedPreKeyId

      val pniProtocolStore = ApplicationDependencies.getProtocolStore().pni()
      val pniMetadataStore = SignalStore.account().pniPreKeys

      SignalStore.account().pniRegistrationId = pniRegistrationId
      SignalStore.account().setPniIdentityKeyAfterChangeNumber(pniIdentityKeyPair)

      val signedPreKey = pniProtocolStore.loadSignedPreKey(pniSignedPreyKeyId)
      val oneTimePreKeys = PreKeyUtil.generateAndStoreOneTimePreKeys(pniProtocolStore, pniMetadataStore)

      pniMetadataStore.activeSignedPreKeyId = signedPreKey.id
      accountManager.setPreKeys(ServiceIdType.PNI, pniProtocolStore.identityKeyPair.publicKey, signedPreKey, oneTimePreKeys)
      pniMetadataStore.isSignedPreKeyRegistered = true

      pniProtocolStore.identities().saveIdentityWithoutSideEffects(
        Recipient.self().id,
        pniProtocolStore.identityKeyPair.publicKey,
        IdentityDatabase.VerifiedStatus.VERIFIED,
        true,
        System.currentTimeMillis(),
        true
      )
    }

    Recipient.self().live().refresh()
    StorageSyncHelper.scheduleSyncForDataChange()

    ApplicationDependencies.closeConnections()
    ApplicationDependencies.getIncomingMessageObserver()

    return rotateCertificates()
  }

  @Suppress("UsePropertyAccessSyntax")
  private fun rotateCertificates(): Single<Unit> {
    val certificateTypes = SignalStore.phoneNumberPrivacy().allCertificateTypes

    Log.i(TAG, "Rotating these certificates $certificateTypes")

    return Single.fromCallable {
      for (certificateType in certificateTypes) {
        val certificate: ByteArray? = when (certificateType) {
          CertificateType.UUID_AND_E164 -> accountManager.getSenderCertificate()
          CertificateType.UUID_ONLY -> accountManager.getSenderCertificateForPhoneNumberPrivacy()
          else -> throw AssertionError()
        }

        Log.i(TAG, "Successfully got $certificateType certificate")

        SignalStore.certificateValues().setUnidentifiedAccessCertificate(certificateType, certificate)
      }
    }.subscribeOn(Schedulers.io())
  }

  @Suppress("UsePropertyAccessSyntax")
  @WorkerThread
  private fun createChangeNumberRequest(
    code: String,
    newE164: String,
    registrationLock: String?
  ): ChangeNumberRequestData {
    val selfIdentifier: String = SignalStore.account().requireAci().toString()
    val aciProtocolStore: SignalProtocolStore = ApplicationDependencies.getProtocolStore().aci()

    val pniIdentity: IdentityKeyPair = IdentityKeyUtil.generateIdentityKeyPair()
    val deviceMessages = mutableListOf<OutgoingPushMessage>()
    val devicePniSignedPreKeys = mutableMapOf<Int, SignedPreKeyEntity>()
    val pniRegistrationIds = mutableMapOf<Int, Int>()
    val primaryDeviceId: Int = SignalServiceAddress.DEFAULT_DEVICE_ID

    val devices: List<Int> = listOf(primaryDeviceId) + aciProtocolStore.getSubDeviceSessions(selfIdentifier)

    devices
      .filter { it == primaryDeviceId || aciProtocolStore.containsSession(SignalProtocolAddress(selfIdentifier, it)) }
      .forEach { deviceId ->
        // Signed Prekeys
        val signedPreKeyRecord = if (deviceId == primaryDeviceId) {
          PreKeyUtil.generateAndStoreSignedPreKey(ApplicationDependencies.getProtocolStore().pni(), SignalStore.account().pniPreKeys, pniIdentity.privateKey)
        } else {
          PreKeyUtil.generateSignedPreKey(SecureRandom().nextInt(Medium.MAX_VALUE), pniIdentity.privateKey)
        }
        devicePniSignedPreKeys[deviceId] = SignedPreKeyEntity(signedPreKeyRecord.id, signedPreKeyRecord.keyPair.publicKey, signedPreKeyRecord.signature)

        // Registration Ids
        var pniRegistrationId = -1
        while (pniRegistrationId < 0 || pniRegistrationIds.values.contains(pniRegistrationId)) {
          pniRegistrationId = KeyHelper.generateRegistrationId(false)
        }
        pniRegistrationIds[deviceId] = pniRegistrationId

        // Device Messages
        if (deviceId != primaryDeviceId) {
          val pniChangeNumber = SyncMessage.PniChangeNumber.newBuilder()
            .setIdentityKeyPair(pniIdentity.serialize().toProtoByteString())
            .setSignedPreKey(signedPreKeyRecord.serialize().toProtoByteString())
            .setRegistrationId(pniRegistrationId)
            .build()

          deviceMessages += messageSender.getEncryptedSyncPniChangeNumberMessage(deviceId, pniChangeNumber)
        }
      }

    val request = ChangePhoneNumberRequest(
      newE164,
      code,
      registrationLock,
      pniIdentity.publicKey,
      deviceMessages,
      devicePniSignedPreKeys.mapKeys { it.key.toString() },
      pniRegistrationIds.mapKeys { it.key.toString() }
    )

    val metadata = PendingChangeNumberMetadata.newBuilder()
      .setPreviousPni(SignalStore.account().pni!!.toByteString())
      .setPniIdentityKeyPair(pniIdentity.serialize().toProtoByteString())
      .setPniRegistrationId(pniRegistrationIds[primaryDeviceId]!!)
      .setPniSignedPreKeyId(devicePniSignedPreKeys[primaryDeviceId]!!.keyId)
      .build()

    return ChangeNumberRequestData(request, metadata)
  }

  data class ChangeNumberRequestData(val changeNumberRequest: ChangePhoneNumberRequest, val pendingChangeNumberMetadata: PendingChangeNumberMetadata)
}
