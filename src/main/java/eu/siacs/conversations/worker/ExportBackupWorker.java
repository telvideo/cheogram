package eu.siacs.conversations.worker;

import static eu.siacs.conversations.utils.Compatibility.s;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.common.base.CharMatcher;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.gson.stream.JsonWriter;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.axolotl.SQLiteAxolotlStore;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.receiver.WorkManagerEventReceiver;
import eu.siacs.conversations.utils.BackupFileHeader;
import eu.siacs.conversations.utils.Compatibility;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class ExportBackupWorker extends Worker {

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US);

    public static final String KEYTYPE = "AES";
    public static final String CIPHERMODE = "AES/GCM/NoPadding";
    public static final String PROVIDER = "BC";

    public static final String MIME_TYPE = "application/vnd.conversations.backup";

    private static final int NOTIFICATION_ID = 19;
    private static final int PAGE_SIZE = 50;
    private static final int BACKUP_CREATED_NOTIFICATION_ID = 23;

    private static final int PENDING_INTENT_FLAGS =
            s()
                    ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                    : PendingIntent.FLAG_UPDATE_CURRENT;

    private final boolean recurringBackup;

    public ExportBackupWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        final var inputData = workerParams.getInputData();
        this.recurringBackup = inputData.getBoolean("recurring_backup", false);
    }

    @NonNull
    @Override
    public Result doWork() {
        final List<File> files;
        try {
            files = export();
        } catch (final IOException
                | InvalidKeySpecException
                | InvalidAlgorithmParameterException
                | InvalidKeyException
                | NoSuchPaddingException
                | NoSuchAlgorithmException
                | NoSuchProviderException e) {
            Log.d(Config.LOGTAG, "could not create backup", e);
            return Result.failure();
        } finally {
            getApplicationContext()
                    .getSystemService(NotificationManager.class)
                    .cancel(NOTIFICATION_ID);
        }
        Log.d(Config.LOGTAG, "done creating " + files.size() + " backup files");
        if (files.isEmpty() || recurringBackup) {
            return Result.success();
        }
        notifySuccess(files);
        return Result.success();
    }

    @NonNull
    @Override
    public ForegroundInfo getForegroundInfo() {
        Log.d(Config.LOGTAG, "getForegroundInfo()");
        final NotificationCompat.Builder notification = getNotification();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return new ForegroundInfo(
                    NOTIFICATION_ID,
                    notification.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            return new ForegroundInfo(NOTIFICATION_ID, notification.build());
        }
    }

    private List<File> export()
            throws IOException,
                    InvalidKeySpecException,
                    InvalidAlgorithmParameterException,
                    InvalidKeyException,
                    NoSuchPaddingException,
                    NoSuchAlgorithmException,
                    NoSuchProviderException {
        final Context context = getApplicationContext();
        final var database = DatabaseBackend.getInstance(context);
        final var accounts = database.getAccounts();

        int count = 0;
        final int max = accounts.size();
        final ImmutableList.Builder<File> files = new ImmutableList.Builder<>();
        Log.d(Config.LOGTAG, "starting backup for " + max + " accounts");
        for (final Account account : accounts) {
            if (isStopped()) {
                Log.d(Config.LOGTAG, "ExportBackupWorker has stopped. Returning what we have");
                return files.build();
            }
            final String password = account.getPassword();
            if (Strings.nullToEmpty(password).trim().isEmpty()) {
                Log.d(
                        Config.LOGTAG,
                        String.format(
                                "skipping backup for %s because password is empty. unable to encrypt",
                                account.getJid().asBareJid()));
                count++;
                continue;
            }
            final String filename =
                    String.format(
                            "%s.%s.ceb",
                            account.getJid().asBareJid().toEscapedString(),
                            DATE_FORMAT.format(new Date()));
            final File file = new File(FileBackend.getBackupDirectory(context), filename);
            try {
                export(database, account, password, file, max, count);
            } catch (final WorkStoppedException e) {
                if (file.delete()) {
                    Log.d(
                            Config.LOGTAG,
                            "deleted in progress backup file " + file.getAbsolutePath());
                }
                Log.d(Config.LOGTAG, "ExportBackupWorker has stopped. Returning what we have");
                return files.build();
            }
            files.add(file);
            count++;
        }
        return files.build();
    }

    private void export(
            final DatabaseBackend database,
            final Account account,
            final String password,
            final File file,
            final int max,
            final int count)
            throws IOException,
                    InvalidKeySpecException,
                    InvalidAlgorithmParameterException,
                    InvalidKeyException,
                    NoSuchPaddingException,
                    NoSuchAlgorithmException,
                    NoSuchProviderException,
                    WorkStoppedException {
        final var context = getApplicationContext();
        final SecureRandom secureRandom = new SecureRandom();
        Log.d(
                Config.LOGTAG,
                String.format(
                        "exporting data for account %s (%s)",
                        account.getJid().asBareJid(), account.getUuid()));
        final byte[] IV = new byte[12];
        final byte[] salt = new byte[16];
        secureRandom.nextBytes(IV);
        secureRandom.nextBytes(salt);
        final BackupFileHeader backupFileHeader =
                new BackupFileHeader(
                        context.getString(R.string.app_name),
                        account.getJid(),
                        System.currentTimeMillis(),
                        IV,
                        salt);
        final var notification = getNotification();
        if (!recurringBackup) {
            final var cancel = new Intent(context, WorkManagerEventReceiver.class);
            cancel.setAction(WorkManagerEventReceiver.ACTION_STOP_BACKUP);
            final var cancelPendingIntent =
                    PendingIntent.getBroadcast(context, 197, cancel, PENDING_INTENT_FLAGS);
            notification.addAction(
                    new NotificationCompat.Action.Builder(
                                    R.drawable.ic_cancel_24dp,
                                    context.getString(R.string.cancel),
                                    cancelPendingIntent)
                            .build());
        }
        final Progress progress = new Progress(notification, max, count);
        final File directory = file.getParentFile();
        if (directory != null && directory.mkdirs()) {
            Log.d(Config.LOGTAG, "created backup directory " + directory.getAbsolutePath());
        }
        final FileOutputStream fileOutputStream = new FileOutputStream(file);
        final DataOutputStream dataOutputStream = new DataOutputStream(fileOutputStream);
        backupFileHeader.write(dataOutputStream);
        dataOutputStream.flush();

        final Cipher cipher =
                Compatibility.twentyEight()
                        ? Cipher.getInstance(CIPHERMODE)
                        : Cipher.getInstance(CIPHERMODE, PROVIDER);
        final byte[] key = getKey(password, salt);
        SecretKeySpec keySpec = new SecretKeySpec(key, KEYTYPE);
        IvParameterSpec ivSpec = new IvParameterSpec(IV);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
        CipherOutputStream cipherOutputStream = new CipherOutputStream(fileOutputStream, cipher);

        final GZIPOutputStream gzipOutputStream = new GZIPOutputStream(cipherOutputStream);
        final SQLiteDatabase db = database.getReadableDatabase();
        final var writer = new PrintWriter(gzipOutputStream);
        final String uuid = account.getUuid();
        accountExport(db, uuid, writer);
        simpleExport(db, Conversation.TABLENAME, Conversation.ACCOUNT, uuid, writer);
        messageExport(db, uuid, writer, progress);
        messageExportCheogram(db, uuid, writer, progress);
        for (final String table :
                Arrays.asList(
                        SQLiteAxolotlStore.PREKEY_TABLENAME,
                        SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME,
                        SQLiteAxolotlStore.SESSION_TABLENAME,
                        SQLiteAxolotlStore.IDENTITIES_TABLENAME)) {
            throwIfWorkStopped();
            simpleExport(db, table, SQLiteAxolotlStore.ACCOUNT, uuid, writer);
        }
        writer.flush();
        writer.close();
        mediaScannerScanFile(file);
        Log.d(Config.LOGTAG, "written backup to " + file.getAbsoluteFile());
    }

    private NotificationCompat.Builder getNotification() {
        final var context = getApplicationContext();
        final NotificationCompat.Builder notification =
                new NotificationCompat.Builder(context, "backup");
        notification
                .setContentTitle(context.getString(R.string.notification_create_backup_title))
                .setSmallIcon(R.drawable.ic_archive_24dp)
                .setProgress(1, 0, false);
        notification.setOngoing(true);
        notification.setLocalOnly(true);
        return notification;
    }

    private void throwIfWorkStopped() throws WorkStoppedException {
        if (isStopped()) {
            throw new WorkStoppedException();
        }
    }

    private void mediaScannerScanFile(final File file) {
        final Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        intent.setData(Uri.fromFile(file));
        getApplicationContext().sendBroadcast(intent);
    }

    private void messageExport(SQLiteDatabase db, String uuid, PrintWriter writer, Progress progress) {
        final var notificationManager = getApplicationContext().getSystemService(NotificationManager.class);
        Cursor cursor = db.rawQuery("select messages.* from messages join conversations on conversations.uuid=messages.conversationUuid where conversations.accountUuid=?", new String[]{uuid});
        int size = cursor != null ? cursor.getCount() : 0;
        Log.d(Config.LOGTAG, "exporting " + size + " messages for account " + uuid);
        int i = 0;
        int p = 0;
        while (cursor != null && cursor.moveToNext()) {
            writer.write(cursorToString(Message.TABLENAME, cursor, PAGE_SIZE, false));
            if (i + PAGE_SIZE > size) {
                i = size;
            } else {
                i += PAGE_SIZE;
            }
            final int percentage = i * 100 / size;
            if (p < percentage) {
                p = percentage;
                notificationManager.notify(NOTIFICATION_ID, progress.build(p));
            }
        }
        if (cursor != null) {
            cursor.close();
        }
    }

    private void messageExportCheogram(SQLiteDatabase db, String uuid, PrintWriter writer, Progress progress) {
        final var notificationManager = getApplicationContext().getSystemService(NotificationManager.class);
        Cursor cursor = db.rawQuery("select cmessages.* from messages join cheogram.messages cmessages using (uuid) join conversations on conversations.uuid=messages.conversationUuid where conversations.accountUuid=?", new String[]{uuid});
        int size = cursor != null ? cursor.getCount() : 0;
        Log.d(Config.LOGTAG, "exporting " + size + " cheogram messages for account " + uuid);
        int i = 0;
        int p = 0;
        while (cursor != null && cursor.moveToNext()) {
            writer.write(cursorToString("cheogram." + Message.TABLENAME, cursor, PAGE_SIZE, false));
            if (i + PAGE_SIZE > size) {
                i = size;
            } else {
                i += PAGE_SIZE;
            }
            final int percentage = i * 100 / size;
            if (p < percentage) {
                p = percentage;
                notificationManager.notify(NOTIFICATION_ID, progress.build(p));
            }
        }
        if (cursor != null) {
            cursor.close();
        }

        cursor = db.rawQuery("select webxdc_updates.* from " + Conversation.TABLENAME + " join cheogram.webxdc_updates webxdc_updates on " + Conversation.TABLENAME + ".uuid=webxdc_updates." + Message.CONVERSATION + " where conversations.accountUuid=?", new String[]{uuid});
        size = cursor != null ? cursor.getCount() : 0;
        Log.d(Config.LOGTAG, "exporting " + size + " WebXDC updates for account " + uuid);
        while (cursor != null && cursor.moveToNext()) {
            writer.write(cursorToString("cheogram.webxdc_updates", cursor, PAGE_SIZE, false));
            if (i + PAGE_SIZE > size) {
                i = size;
            } else {
                i += PAGE_SIZE;
            }
            final int percentage = i * 100 / size;
            if (p < percentage) {
                p = percentage;
                notificationManager.notify(NOTIFICATION_ID, progress.build(p));
            }
        }
        if (cursor != null) {
            cursor.close();
        }
    }

    private static void accountExport(final SQLiteDatabase db, final String uuid, final PrintWriter writer) {
        final StringBuilder builder = new StringBuilder();
        final Cursor accountCursor = db.query(Account.TABLENAME, null, Account.UUID + "=?", new String[]{uuid}, null, null, null);
        while (accountCursor != null && accountCursor.moveToNext()) {
            builder.append("INSERT INTO ").append(Account.TABLENAME).append("(");
            for (int i = 0; i < accountCursor.getColumnCount(); ++i) {
                if (i != 0) {
                    builder.append(',');
                }
                builder.append(accountCursor.getColumnName(i));
            }
            builder.append(") VALUES(");
            for (int i = 0; i < accountCursor.getColumnCount(); ++i) {
                if (i != 0) {
                    builder.append(',');
                }
                final String value = accountCursor.getString(i);
                if (value == null || Account.ROSTERVERSION.equals(accountCursor.getColumnName(i))) {
                    builder.append("NULL");
                } else if (Account.OPTIONS.equals(accountCursor.getColumnName(i)) && value.matches("\\d+")) {
                    int intValue = Integer.parseInt(value);
                    intValue |= 1 << Account.OPTION_DISABLED;
                    builder.append(intValue);
                } else {
                    appendEscapedSQLString(builder, value);
                }
            }
            builder.append(")");
            builder.append(';');
            builder.append('\n');
        }
        if (accountCursor != null) {
            accountCursor.close();
        }
        writer.append(builder.toString());
    }

    private static void simpleExport(SQLiteDatabase db, String table, String column, String uuid, PrintWriter writer) {
        final Cursor cursor = db.query(table, null, column + "=?", new String[]{uuid}, null, null, null);
        while (cursor != null && cursor.moveToNext()) {
            writer.write(cursorToString(table, cursor, PAGE_SIZE));
        }
        if (cursor != null) {
            cursor.close();
        }
    }

    private static String cursorToString(final String table, final Cursor cursor, final int max) {
        return cursorToString(table, cursor, max, false);
    }

    private static String cursorToString(final String table, final Cursor cursor, int max, boolean ignore) {
        final boolean identities = SQLiteAxolotlStore.IDENTITIES_TABLENAME.equals(table);
        StringBuilder builder = new StringBuilder();
        builder.append("INSERT ");
        if (ignore) {
            builder.append("OR IGNORE ");
        }
        builder.append("INTO ").append(table).append("(");
        int skipColumn = -1;
        for (int i = 0; i < cursor.getColumnCount(); ++i) {
            final String name = cursor.getColumnName(i);
            if (identities && SQLiteAxolotlStore.TRUSTED.equals(name)) {
                skipColumn = i;
                continue;
            }
            if (i != 0) {
                builder.append(',');
            }
            builder.append(name);
        }
        builder.append(") VALUES");
        for (int i = 0; i < max; ++i) {
            if (i != 0) {
                builder.append(',');
            }
            appendValues(cursor, builder, skipColumn);
            if (i < max - 1 && !cursor.moveToNext()) {
                break;
            }
        }
        builder.append(';');
        builder.append('\n');
        return builder.toString();
    }

    private static void appendValues(final Cursor cursor, final StringBuilder builder, final int skipColumn) {
        builder.append("(");
        for (int i = 0; i < cursor.getColumnCount(); ++i) {
            if (i == skipColumn) {
                continue;
            }
            if (i != 0) {
                builder.append(',');
            }
            final String value = cursor.getString(i);
            if (value == null) {
                builder.append("NULL");
            } else if (value.matches("[0-9]+")) {
                builder.append(value);
            } else {
                appendEscapedSQLString(builder, value);
            }
        }
        builder.append(")");

    }

    private static void appendEscapedSQLString(final StringBuilder sb, final String sqlString) {
        DatabaseUtils.appendEscapedSQLString(sb, CharMatcher.is('\u0000').removeFrom(sqlString));
    }

    public static byte[] getKey(final String password, final byte[] salt)
            throws InvalidKeySpecException {
        final SecretKeyFactory factory;
        try {
            factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return factory.generateSecret(new PBEKeySpec(password.toCharArray(), salt, 1024, 128))
                .getEncoded();
    }

    private void notifySuccess(final List<File> files) {
        final var context = getApplicationContext();
        final String path = FileBackend.getBackupDirectory(context).getAbsolutePath();

        final var openFolderIntent = getOpenFolderIntent(path);

        final Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        final ArrayList<Uri> uris = new ArrayList<>();
        for (final File file : files) {
            uris.add(FileBackend.getUriForFile(context, file));
        }
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setType(MIME_TYPE);
        final Intent chooser =
                Intent.createChooser(intent, context.getString(R.string.share_backup_files));
        final var shareFilesIntent =
                PendingIntent.getActivity(context, 190, chooser, PENDING_INTENT_FLAGS);

        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(context, "backup");
        mBuilder.setContentTitle(context.getString(R.string.notification_backup_created_title))
                .setContentText(
                        context.getString(R.string.notification_backup_created_subtitle, path))
                .setStyle(
                        new NotificationCompat.BigTextStyle()
                                .bigText(
                                        context.getString(
                                                R.string.notification_backup_created_subtitle,
                                                FileBackend.getBackupDirectory(context)
                                                        .getAbsolutePath())))
                .setAutoCancel(true)
                .setSmallIcon(R.drawable.ic_archive_24dp);

        if (openFolderIntent.isPresent()) {
            mBuilder.setContentIntent(openFolderIntent.get());
        } else {
            Log.w(Config.LOGTAG, "no app can display folders");
        }

        mBuilder.addAction(
                R.drawable.ic_share_24dp,
                context.getString(R.string.share_backup_files),
                shareFilesIntent);
        final var notificationManager = context.getSystemService(NotificationManager.class);
        notificationManager.notify(BACKUP_CREATED_NOTIFICATION_ID, mBuilder.build());
    }

    private Optional<PendingIntent> getOpenFolderIntent(final String path) {
        final var context = getApplicationContext();
        for (final Intent intent : getPossibleFileOpenIntents(context, path)) {
            if (intent.resolveActivityInfo(context.getPackageManager(), 0) != null) {
                return Optional.of(
                        PendingIntent.getActivity(context, 189, intent, PENDING_INTENT_FLAGS));
            }
        }
        return Optional.absent();
    }

    private static List<Intent> getPossibleFileOpenIntents(
            final Context context, final String path) {

        // http://www.openintents.org/action/android-intent-action-view/file-directory
        // do not use 'vnd.android.document/directory' since this will trigger system file manager
        final Intent openIntent = new Intent(Intent.ACTION_VIEW);
        openIntent.addCategory(Intent.CATEGORY_DEFAULT);
        if (Compatibility.runsAndTargetsTwentyFour(context)) {
            openIntent.setType("resource/folder");
        } else {
            openIntent.setDataAndType(Uri.parse("file://" + path), "resource/folder");
        }
        openIntent.putExtra("org.openintents.extra.ABSOLUTE_PATH", path);

        final Intent amazeIntent = new Intent(Intent.ACTION_VIEW);
        amazeIntent.setDataAndType(Uri.parse("com.amaze.filemanager:" + path), "resource/folder");

        // will open a file manager at root and user can navigate themselves
        final Intent systemFallBack = new Intent(Intent.ACTION_VIEW);
        systemFallBack.addCategory(Intent.CATEGORY_DEFAULT);
        systemFallBack.setData(
                Uri.parse("content://com.android.externalstorage.documents/root/primary"));

        return Arrays.asList(openIntent, amazeIntent, systemFallBack);
    }

    private static class Progress {
        private final NotificationCompat.Builder notification;
        private final int max;
        private final int count;

        private Progress(
                final NotificationCompat.Builder notification, final int max, final int count) {
            this.notification = notification;
            this.max = max;
            this.count = count;
        }

        private Notification build(int percentage) {
            notification.setProgress(max * 100, count * 100 + percentage, false);
            return notification.build();
        }
    }

    private static class WorkStoppedException extends Exception {}
}
