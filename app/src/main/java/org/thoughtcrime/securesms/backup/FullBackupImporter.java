package org.thoughtcrime.securesms.backup;


import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.util.Pair;

import androidx.annotation.NonNull;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.greenrobot.eventbus.EventBus;
import org.signal.core.util.SqlUtil;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.backup.BackupProtos.Attachment;
import org.thoughtcrime.securesms.backup.BackupProtos.BackupFrame;
import org.thoughtcrime.securesms.backup.BackupProtos.DatabaseVersion;
import org.thoughtcrime.securesms.backup.BackupProtos.SharedPreference;
import org.thoughtcrime.securesms.backup.BackupProtos.SqlStatement;
import org.thoughtcrime.securesms.backup.BackupProtos.Sticker;
import org.thoughtcrime.securesms.crypto.AttachmentSecret;
import org.thoughtcrime.securesms.crypto.ModernEncryptingPartOutputStream;
import org.thoughtcrime.securesms.database.AttachmentDatabase;
import org.thoughtcrime.securesms.database.EmojiSearchDatabase;
import org.thoughtcrime.securesms.database.KeyValueDatabase;
import org.thoughtcrime.securesms.database.SearchDatabase;
import org.thoughtcrime.securesms.database.StickerDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.keyvalue.KeyValueDataSet;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.profiles.AvatarHelper;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.BackupUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

public class FullBackupImporter extends FullBackupBase {

  @SuppressWarnings("unused")
  private static final String TAG = Log.tag(FullBackupImporter.class);

  private static final String[] TABLES_TO_DROP_FIRST = {
      "distribution_list_member",
      "distribution_list",
      "message_send_log_recipients",
      "msl_recipient",
      "msl_message",
      "reaction",
      "notification_profile_schedule",
      "notification_profile_allowed_members",
      "story_sends"
  };

  public static void importFile(@NonNull Context context, @NonNull AttachmentSecret attachmentSecret,
                                @NonNull SQLiteDatabase db, @NonNull Uri uri, @NonNull String passphrase)
      throws IOException
  {
    try (InputStream is = getInputStream(context, uri)) {
      importFile(context, attachmentSecret, db, is, passphrase);
    }
  }

  public static void importFile(@NonNull Context context, @NonNull AttachmentSecret attachmentSecret,
                                @NonNull SQLiteDatabase db, @NonNull InputStream is, @NonNull String passphrase)
      throws IOException
  {
    int count = 0;

    SQLiteDatabase keyValueDatabase = KeyValueDatabase.getInstance(ApplicationDependencies.getApplication()).getSqlCipherDatabase();

    db.beginTransaction();
    keyValueDatabase.beginTransaction();
    try {
      BackupRecordInputStream inputStream = new BackupRecordInputStream(is, passphrase);

      dropAllTables(db);

      BackupFrame frame;

      while (!(frame = inputStream.readFrame()).getEnd()) {
        if (count % 100 == 0) EventBus.getDefault().post(new BackupEvent(BackupEvent.Type.PROGRESS, count, 0));
        count++;

        if      (frame.hasVersion())    processVersion(db, frame.getVersion());
        else if (frame.hasStatement())  processStatement(db, frame.getStatement());
        else if (frame.hasPreference()) processPreference(context, frame.getPreference());
        else if (frame.hasAttachment()) processAttachment(context, attachmentSecret, db, frame.getAttachment(), inputStream);
        else if (frame.hasSticker())    processSticker(context, attachmentSecret, db, frame.getSticker(), inputStream);
        else if (frame.hasAvatar())     processAvatar(context, db, frame.getAvatar(), inputStream);
        else if (frame.hasKeyValue())   processKeyValue(frame.getKeyValue());
        else                            count--;
      }

      db.setTransactionSuccessful();
      keyValueDatabase.setTransactionSuccessful();
    } finally {
      db.endTransaction();
      keyValueDatabase.endTransaction();
    }

    EventBus.getDefault().post(new BackupEvent(BackupEvent.Type.FINISHED, count, 0));
  }

  private static @NonNull InputStream getInputStream(@NonNull Context context, @NonNull Uri uri) throws IOException{
    if (BackupUtil.isUserSelectionRequired(context) || uri.getScheme().equals("content")) {
      return Objects.requireNonNull(context.getContentResolver().openInputStream(uri));
    } else {
      return new FileInputStream(new File(Objects.requireNonNull(uri.getPath())));
    }
  }

  private static void processVersion(@NonNull SQLiteDatabase db, DatabaseVersion version) throws IOException {
    if (version.getVersion() > db.getVersion()) {
      throw new DatabaseDowngradeException(db.getVersion(), version.getVersion());
    }

    db.setVersion(version.getVersion());
  }

  private static void processStatement(@NonNull SQLiteDatabase db, SqlStatement statement) {
    boolean isForSmsFtsSecretTable = statement.getStatement().contains(SearchDatabase.SMS_FTS_TABLE_NAME + "_");
    boolean isForMmsFtsSecretTable = statement.getStatement().contains(SearchDatabase.MMS_FTS_TABLE_NAME + "_");
    boolean isForEmojiSecretTable  = statement.getStatement().contains(EmojiSearchDatabase.TABLE_NAME + "_");
    boolean isForSqliteSecretTable = statement.getStatement().toLowerCase().startsWith("create table sqlite_");

    if (isForSmsFtsSecretTable || isForMmsFtsSecretTable || isForEmojiSecretTable || isForSqliteSecretTable) {
      Log.i(TAG, "Ignoring import for statement: " + statement.getStatement());
      return;
    }

    List<Object> parameters = new LinkedList<>();

    for (SqlStatement.SqlParameter parameter : statement.getParametersList()) {
      if      (parameter.hasStringParamter())   parameters.add(parameter.getStringParamter());
      else if (parameter.hasDoubleParameter())  parameters.add(parameter.getDoubleParameter());
      else if (parameter.hasIntegerParameter()) parameters.add(parameter.getIntegerParameter());
      else if (parameter.hasBlobParameter())    parameters.add(parameter.getBlobParameter().toByteArray());
      else if (parameter.hasNullparameter())    parameters.add(null);
    }

    if (parameters.size() > 0) db.execSQL(statement.getStatement(), parameters.toArray());
    else                       db.execSQL(statement.getStatement());
  }

  private static void processAttachment(@NonNull Context context, @NonNull AttachmentSecret attachmentSecret, @NonNull SQLiteDatabase db, @NonNull Attachment attachment, BackupRecordInputStream inputStream)
      throws IOException
  {
    File                       dataFile = AttachmentDatabase.newFile(context);
    Pair<byte[], OutputStream> output   = ModernEncryptingPartOutputStream.createFor(attachmentSecret, dataFile, false);

    ContentValues contentValues = new ContentValues();

    try {
      inputStream.readAttachmentTo(output.second, attachment.getLength());

      contentValues.put(AttachmentDatabase.DATA, dataFile.getAbsolutePath());
      contentValues.put(AttachmentDatabase.DATA_RANDOM, output.first);
    } catch (BackupRecordInputStream.BadMacException e) {
      Log.w(TAG, "Bad MAC for attachment " + attachment.getAttachmentId() + "! Can't restore it.", e);
      dataFile.delete();
      contentValues.put(AttachmentDatabase.DATA, (String) null);
      contentValues.put(AttachmentDatabase.DATA_RANDOM, (String) null);
    }

    db.update(AttachmentDatabase.TABLE_NAME, contentValues,
              AttachmentDatabase.ROW_ID + " = ? AND " + AttachmentDatabase.UNIQUE_ID + " = ?",
              new String[] {String.valueOf(attachment.getRowId()), String.valueOf(attachment.getAttachmentId())});
  }

  private static void processSticker(@NonNull Context context, @NonNull AttachmentSecret attachmentSecret, @NonNull SQLiteDatabase db, @NonNull Sticker sticker, BackupRecordInputStream inputStream)
      throws IOException
  {
    File stickerDirectory = context.getDir(StickerDatabase.DIRECTORY, Context.MODE_PRIVATE);
    File dataFile         = File.createTempFile("sticker", ".mms", stickerDirectory);

    Pair<byte[], OutputStream> output = ModernEncryptingPartOutputStream.createFor(attachmentSecret, dataFile, false);

    inputStream.readAttachmentTo(output.second, sticker.getLength());

    ContentValues contentValues = new ContentValues();
    contentValues.put(StickerDatabase.FILE_PATH, dataFile.getAbsolutePath());
    contentValues.put(StickerDatabase.FILE_LENGTH, sticker.getLength());
    contentValues.put(StickerDatabase.FILE_RANDOM, output.first);

    db.update(StickerDatabase.TABLE_NAME, contentValues,
              StickerDatabase._ID + " = ?",
              new String[] {String.valueOf(sticker.getRowId())});
  }

  private static void processAvatar(@NonNull Context context, @NonNull SQLiteDatabase db, @NonNull BackupProtos.Avatar avatar, @NonNull BackupRecordInputStream inputStream) throws IOException {
    if (avatar.hasRecipientId()) {
      RecipientId recipientId = RecipientId.from(avatar.getRecipientId());
      inputStream.readAttachmentTo(AvatarHelper.getOutputStream(context, recipientId, false), avatar.getLength());
    } else {
      if (avatar.hasName() && SqlUtil.tableExists(db, "recipient_preferences")) {
        Log.w(TAG, "Avatar is missing a recipientId. Clearing signal_profile_avatar (legacy) so it can be fetched later.");
        db.execSQL("UPDATE recipient_preferences SET signal_profile_avatar = NULL WHERE recipient_ids = ?", new String[] { avatar.getName() });
      } else if (avatar.hasName() && SqlUtil.tableExists(db, "recipient")) {
        Log.w(TAG, "Avatar is missing a recipientId. Clearing signal_profile_avatar so it can be fetched later.");
        db.execSQL("UPDATE recipient SET signal_profile_avatar = NULL WHERE phone = ?", new String[] { avatar.getName() });
      } else {
        Log.w(TAG, "Avatar is missing a recipientId. Skipping avatar restore.");
      }

      inputStream.readAttachmentTo(new ByteArrayOutputStream(), avatar.getLength());
    }
  }

  private static void processKeyValue(BackupProtos.KeyValue keyValue) {
    KeyValueDataSet dataSet = new KeyValueDataSet();

    if (keyValue.hasBlobValue()) {
      dataSet.putBlob(keyValue.getKey(), keyValue.getBlobValue().toByteArray());
    } else if (keyValue.hasBooleanValue()) {
      dataSet.putBoolean(keyValue.getKey(), keyValue.getBooleanValue());
    } else if (keyValue.hasFloatValue()) {
      dataSet.putFloat(keyValue.getKey(), keyValue.getFloatValue());
    } else if (keyValue.hasIntegerValue()) {
      dataSet.putInteger(keyValue.getKey(), keyValue.getIntegerValue());
    } else if (keyValue.hasLongValue()) {
      dataSet.putLong(keyValue.getKey(), keyValue.getLongValue());
    } else if (keyValue.hasStringValue()) {
      dataSet.putString(keyValue.getKey(), keyValue.getStringValue());
    } else {
      Log.i(TAG, "Unknown KeyValue backup value, skipping");
      return;
    }

    KeyValueDatabase.getInstance(ApplicationDependencies.getApplication()).writeDataSet(dataSet, Collections.emptyList());
  }

  @SuppressLint("ApplySharedPref")
  private static void processPreference(@NonNull Context context, SharedPreference preference) {
    SharedPreferences preferences = context.getSharedPreferences(preference.getFile(), 0);

    // Identity keys were moved from shared prefs into SignalStore. Need to handle importing backups made before the migration.
    if ("SecureSMS-Preferences".equals(preference.getFile())) {
      if ("pref_identity_public_v3".equals(preference.getKey()) && preference.hasValue()) {
        SignalStore.account().restoreLegacyIdentityPublicKeyFromBackup(preference.getValue());
      } else if ("pref_identity_private_v3".equals(preference.getKey()) && preference.hasValue()) {
        SignalStore.account().restoreLegacyIdentityPrivateKeyFromBackup(preference.getValue());
      }

      return;
    }

    if (preference.hasValue()) {
      preferences.edit().putString(preference.getKey(), preference.getValue()).commit();
    } else if (preference.hasBooleanValue()) {
      preferences.edit().putBoolean(preference.getKey(), preference.getBooleanValue()).commit();
    } else if (preference.hasIsStringSetValue() && preference.getIsStringSetValue()) {
      preferences.edit().putStringSet(preference.getKey(), new HashSet<>(preference.getStringSetValueList())).commit();
    }
  }

  private static void dropAllTables(@NonNull SQLiteDatabase db) {
    for (String name : TABLES_TO_DROP_FIRST) {
      db.execSQL("DROP TABLE IF EXISTS " + name);
    }

    try (Cursor cursor = db.rawQuery("SELECT name, type FROM sqlite_master", null)) {
      while (cursor != null && cursor.moveToNext()) {
        String name = cursor.getString(0);
        String type = cursor.getString(1);

        if ("table".equals(type) && !name.startsWith("sqlite_")) {
          Log.i(TAG, "Dropping table: " + name);
          db.execSQL("DROP TABLE IF EXISTS " + name);
        }
      }
    }
  }

  public static class DatabaseDowngradeException extends IOException {
    DatabaseDowngradeException(int currentVersion, int backupVersion) {
      super("Tried to import a backup with version " + backupVersion + " into a database with version " + currentVersion);
    }
  }
}
