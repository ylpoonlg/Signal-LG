package org.thoughtcrime.securesms.video.exo;


import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import org.signal.core.util.logging.Log;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.thoughtcrime.securesms.attachments.DatabaseAttachment;
import org.thoughtcrime.securesms.database.AttachmentTable;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.mms.PartUriParser;
import org.thoughtcrime.securesms.util.Base64;
import org.thoughtcrime.securesms.util.FeatureFlags;
import org.whispersystems.signalservice.api.crypto.AttachmentCipherInputStream;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@OptIn(markerClass = UnstableApi.class)
class PartDataSource implements DataSource {

  private final           String           TAG = Log.tag(PartDataSource.class);
  private final @Nullable TransferListener listener;

  private Uri         uri;
  private InputStream inputStream;

  PartDataSource(@Nullable TransferListener listener) {
    this.listener = listener;
  }

  @Override
  public void addTransferListener(@NonNull TransferListener transferListener) {
  }

  @Override
  public long open(DataSpec dataSpec) throws IOException {
    this.uri = dataSpec.uri;

    AttachmentTable    attachmentDatabase = SignalDatabase.attachments();
    PartUriParser      partUri            = new PartUriParser(uri);
    DatabaseAttachment attachment         = attachmentDatabase.getAttachment(partUri.getPartId());

    if (attachment == null) throw new IOException("Attachment not found");

    final boolean hasIncrementalDigest = attachment.getIncrementalDigest() != null;
    final boolean inProgress           = attachment.isInProgress();
    final String  attachmentKey        = attachment.getKey();
    final boolean hasData              = attachment.hasData();
    if (inProgress && !hasData && hasIncrementalDigest && attachmentKey != null && FeatureFlags.instantVideoPlayback()) {
      final byte[] decode       = Base64.decode(attachmentKey);
      final File   transferFile = attachmentDatabase.getOrCreateTransferFile(attachment.getAttachmentId());
      try {
        this.inputStream = AttachmentCipherInputStream.createForAttachment(transferFile, attachment.getSize(), decode, attachment.getDigest(), attachment.getIncrementalDigest(), attachment.getIncrementalMacChunkSize());

        long skipped = 0;
        while (skipped < dataSpec.position) {
          skipped += this.inputStream.read();
        }

        Log.d(TAG, "Successfully loaded partial attachment file.");

      } catch (InvalidMessageException e) {
        throw new IOException("Error decrypting attachment stream!", e);
      }
    } else if (!inProgress || hasData) {
      this.inputStream = attachmentDatabase.getAttachmentStream(partUri.getPartId(), dataSpec.position);

      Log.d(TAG, "Successfully loaded completed attachment file.");
    } else {
      throw new IOException("Ineligible " + attachment.getAttachmentId().toString()
                            + "\nTransfer state: " + attachment.getTransferState()
                            + "\nIncremental Digest Present: " + hasIncrementalDigest
                            + "\nAttachment Key Non-Empty: " + (attachmentKey != null && !attachmentKey.isEmpty()));
    }

    if (listener != null) {
      listener.onTransferStart(this, dataSpec, false);
    }

    if (attachment.getSize() - dataSpec.position <= 0) throw new EOFException("No more data");

    return attachment.getSize() - dataSpec.position;
  }

  @Override
  public int read(@NonNull byte[] buffer, int offset, int readLength) throws IOException {
    int read = inputStream.read(buffer, offset, readLength);

    if (read > 0 && listener != null) {
      listener.onBytesTransferred(this, null, false, read);
    }

    return read;
  }

  @Override
  public Uri getUri() {
    return uri;
  }

  @Override
  public @NonNull Map<String, List<String>> getResponseHeaders() {
    return Collections.emptyMap();
  }

  @Override
  public void close() throws IOException {
    if (inputStream != null) inputStream.close();
  }
}
