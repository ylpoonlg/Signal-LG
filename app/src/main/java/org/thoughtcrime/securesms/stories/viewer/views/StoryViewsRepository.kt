package org.thoughtcrime.securesms.stories.viewer.views

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.DatabaseObserver
import org.thoughtcrime.securesms.database.GroupReceiptDatabase
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.TextSecurePreferences

class StoryViewsRepository {

  companion object {
    private val TAG = Log.tag(StoryViewsRepository::class.java)
  }

  fun isReadReceiptsEnabled(): Boolean = TextSecurePreferences.isReadReceiptsEnabled(ApplicationDependencies.getApplication())

  fun getStoryRecipient(storyId: Long): Single<Recipient> {
    return Single.fromCallable {
      val record = SignalDatabase.mms.getMessageRecord(storyId)

      record.recipient
    }.subscribeOn(Schedulers.io())
  }

  fun getViews(storyId: Long): Observable<List<StoryViewItemData>> {
    return Observable.create<List<StoryViewItemData>> { emitter ->
      fun refresh() {
        emitter.onNext(
          SignalDatabase.groupReceipts.getGroupReceiptInfo(storyId).filter {
            it.status == GroupReceiptDatabase.STATUS_VIEWED
          }.map {
            StoryViewItemData(
              recipient = Recipient.resolved(it.recipientId),
              timeViewedInMillis = it.timestamp
            )
          }
        )
      }

      val observer = DatabaseObserver.MessageObserver { refresh() }

      ApplicationDependencies.getDatabaseObserver().registerMessageUpdateObserver(observer)
      emitter.setCancellable {
        ApplicationDependencies.getDatabaseObserver().unregisterObserver(observer)
      }

      refresh()
    }.subscribeOn(Schedulers.io())
  }

  fun removeUserFromStory(user: Recipient, story: Recipient): Completable {
    return Completable.fromAction {
      val distributionListRecord = SignalDatabase.distributionLists.getList(story.requireDistributionListId())!!
      if (user.id in distributionListRecord.members) {
        SignalDatabase.distributionLists.excludeFromStory(user.id, distributionListRecord)
      } else {
        Log.w(TAG, "User is no longer in the distribution list.")
      }
    }
  }
}
