package org.thoughtcrime.securesms.messagedetails;

import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;

import org.signal.core.util.concurrent.SignalExecutors;
import org.thoughtcrime.securesms.database.DatabaseObserver;
import org.thoughtcrime.securesms.database.MessageDatabase;
import org.thoughtcrime.securesms.database.NoSuchMessageException;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.model.MessageId;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;

final class MessageRecordLiveData extends LiveData<MessageRecord> {

  private final DatabaseObserver.Observer observer;
  private final MessageId                 messageId;

  MessageRecordLiveData(MessageId messageId) {
    this.messageId = messageId;
    this.observer  = this::retrieveMessageRecordActual;
  }

  @Override
  protected void onActive() {
    SignalExecutors.BOUNDED_IO.execute(this::retrieveMessageRecordActual);
  }

  @Override
  protected void onInactive() {
    ApplicationDependencies.getDatabaseObserver().unregisterObserver(observer);
  }

  @WorkerThread
  private synchronized void retrieveMessageRecordActual() {
    retrieve(messageId.isMms() ? SignalDatabase.mms() : SignalDatabase.sms());
  }

  @WorkerThread
  private synchronized void retrieve(MessageDatabase messageDatabase) {
    try {
      final MessageRecord record = messageDatabase.getMessageRecord(messageId.getId());
      postValue(record);
      ApplicationDependencies.getDatabaseObserver().registerVerboseConversationObserver(record.getThreadId(), observer);
    } catch (NoSuchMessageException ignored) {
      postValue(null);
    }
  }
}
