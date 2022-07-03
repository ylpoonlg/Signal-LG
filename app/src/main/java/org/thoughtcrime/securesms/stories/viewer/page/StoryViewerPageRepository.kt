package org.thoughtcrime.securesms.stories.viewer.page

import android.content.Context
import android.net.Uri
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.util.BreakIteratorCompat
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.conversation.ConversationMessage
import org.thoughtcrime.securesms.database.DatabaseObserver
import org.thoughtcrime.securesms.database.NoSuchMessageException
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.databaseprotos.StoryTextPost
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.jobs.MultiDeviceViewedUpdateJob
import org.thoughtcrime.securesms.jobs.SendViewedReceiptJob
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.stories.Stories
import org.thoughtcrime.securesms.util.Base64

/**
 * Open for testing.
 */
open class StoryViewerPageRepository(context: Context) {

  companion object {
    private val TAG = Log.tag(StoryViewerPageRepository::class.java)
  }

  private val context = context.applicationContext

  private fun getStoryRecords(recipientId: RecipientId, isUnviewedOnly: Boolean): Observable<List<MessageRecord>> {
    return Observable.create { emitter ->
      val recipient = Recipient.resolved(recipientId)

      fun refresh() {
        val stories = if (recipient.isMyStory) {
          SignalDatabase.mms.getAllOutgoingStories(false)
        } else if (isUnviewedOnly) {
          SignalDatabase.mms.getUnreadStories(recipientId, 100)
        } else {
          SignalDatabase.mms.getAllStoriesFor(recipientId)
        }

        val results = mutableListOf<MessageRecord>()

        while (stories.next != null) {
          if (!(recipient.isMyStory && stories.current.recipient.isGroup)) {
            results.add(stories.current)
          }
        }

        emitter.onNext(results)
      }

      val storyObserver = DatabaseObserver.Observer {
        refresh()
      }

      ApplicationDependencies.getDatabaseObserver().registerStoryObserver(recipientId, storyObserver)
      emitter.setCancellable {
        ApplicationDependencies.getDatabaseObserver().unregisterObserver(storyObserver)
      }

      refresh()
    }
  }

  private fun getStoryPostFromRecord(recipientId: RecipientId, record: MessageRecord): Observable<StoryPost> {
    return Observable.create { emitter ->
      fun refresh(record: MessageRecord) {
        val recipient = Recipient.resolved(recipientId)
        val story = StoryPost(
          id = record.id,
          sender = if (record.isOutgoing) Recipient.self() else record.individualRecipient,
          group = if (recipient.isGroup) recipient else null,
          distributionList = if (record.recipient.isDistributionList) record.recipient else null,
          viewCount = record.viewedReceiptCount,
          replyCount = SignalDatabase.mms.getNumberOfStoryReplies(record.id),
          dateInMilliseconds = record.dateSent,
          content = getContent(record as MmsMessageRecord),
          conversationMessage = ConversationMessage.ConversationMessageFactory.createWithUnresolvedData(context, record),
          allowsReplies = record.storyType.isStoryWithReplies,
          hasSelfViewed = if (record.isOutgoing) true else record.viewedReceiptCount > 0
        )

        emitter.onNext(story)
      }

      val recipient = Recipient.resolved(recipientId)

      val messageUpdateObserver = DatabaseObserver.MessageObserver {
        if (it.mms && it.id == record.id) {
          try {
            val messageRecord = SignalDatabase.mms.getMessageRecord(record.id)
            if (messageRecord.isRemoteDelete) {
              emitter.onComplete()
            } else {
              refresh(messageRecord)
            }
          } catch (e: NoSuchMessageException) {
            emitter.onComplete()
          }
        }
      }

      val conversationObserver = DatabaseObserver.Observer {
        try {
          refresh(SignalDatabase.mms.getMessageRecord(record.id))
        } catch (e: NoSuchMessageException) {
          Log.w(TAG, "Message deleted during content refresh.", e)
        }
      }

      ApplicationDependencies.getDatabaseObserver().registerConversationObserver(record.threadId, conversationObserver)
      ApplicationDependencies.getDatabaseObserver().registerMessageUpdateObserver(messageUpdateObserver)

      val messageInsertObserver = DatabaseObserver.MessageObserver {
        refresh(SignalDatabase.mms.getMessageRecord(record.id))
      }

      if (recipient.isGroup) {
        ApplicationDependencies.getDatabaseObserver().registerMessageInsertObserver(record.threadId, messageInsertObserver)
      }

      emitter.setCancellable {
        ApplicationDependencies.getDatabaseObserver().unregisterObserver(conversationObserver)
        ApplicationDependencies.getDatabaseObserver().unregisterObserver(messageUpdateObserver)

        if (recipient.isGroup) {
          ApplicationDependencies.getDatabaseObserver().unregisterObserver(messageInsertObserver)
        }
      }

      refresh(record)
    }
  }

  fun forceDownload(post: StoryPost): Completable {
    return Stories.enqueueAttachmentsFromStoryForDownload(post.conversationMessage.messageRecord as MmsMessageRecord, true)
  }

  fun getStoryPostsFor(recipientId: RecipientId, isUnviewedOnly: Boolean): Observable<List<StoryPost>> {
    return getStoryRecords(recipientId, isUnviewedOnly)
      .switchMap { records ->
        val posts = records.map { getStoryPostFromRecord(recipientId, it) }
        if (posts.isEmpty()) {
          Observable.just(emptyList())
        } else {
          Observable.combineLatest(posts) { it.toList() as List<StoryPost> }
        }
      }.observeOn(Schedulers.io())
  }

  fun hideStory(recipientId: RecipientId): Completable {
    return Completable.fromAction {
      SignalDatabase.recipients.setHideStory(recipientId, true)
    }.subscribeOn(Schedulers.io())
  }

  fun markViewed(storyPost: StoryPost) {
    if (!storyPost.conversationMessage.messageRecord.isOutgoing) {
      SignalExecutors.BOUNDED.execute {
        val markedMessageInfo = SignalDatabase.mms.setIncomingMessageViewed(storyPost.id)
        if (markedMessageInfo != null) {
          ApplicationDependencies.getDatabaseObserver().notifyConversationListListeners()
          ApplicationDependencies.getJobManager().add(
            SendViewedReceiptJob(
              markedMessageInfo.threadId,
              storyPost.sender.id,
              markedMessageInfo.syncMessageId.timetamp,
              MessageId(storyPost.id, true)
            )
          )
          MultiDeviceViewedUpdateJob.enqueue(listOf(markedMessageInfo.syncMessageId))

          val recipientId = storyPost.group?.id ?: storyPost.sender.id
          SignalDatabase.recipients.updateLastStoryViewTimestamp(recipientId)
          Stories.enqueueNextStoriesForDownload(recipientId, true)
        }
      }
    }
  }

  private fun getContent(record: MmsMessageRecord): StoryPost.Content {
    return if (record.storyType.isTextStory || record.slideDeck.asAttachments().isEmpty()) {
      StoryPost.Content.TextContent(
        uri = Uri.parse("story_text_post://${record.id}"),
        recordId = record.id,
        hasBody = canParseToTextStory(record.body),
        length = getTextStoryLength(record.body)
      )
    } else {
      StoryPost.Content.AttachmentContent(
        attachment = record.slideDeck.asAttachments().first()
      )
    }
  }

  private fun getTextStoryLength(body: String): Int {
    return if (canParseToTextStory(body)) {
      val breakIteratorCompat = BreakIteratorCompat.getInstance()
      breakIteratorCompat.setText(StoryTextPost.parseFrom(Base64.decode(body)).body)
      breakIteratorCompat.countBreaks()
    } else {
      0
    }
  }

  private fun canParseToTextStory(body: String): Boolean {
    return if (body.isNotEmpty()) {
      try {
        StoryTextPost.parseFrom(Base64.decode(body))
        return true
      } catch (e: Exception) {
        false
      }
    } else {
      false
    }
  }
}
