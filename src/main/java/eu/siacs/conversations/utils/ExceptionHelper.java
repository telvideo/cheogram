package eu.siacs.conversations.utils;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.util.Log;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.XmppActivity;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.ClassNotFoundException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ExceptionHelper {

    private static final String FILENAME = "stacktrace.txt";
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH);

    public static void init(final Context context) {
        if (Thread.getDefaultUncaughtExceptionHandler() instanceof ExceptionHandler) {
            return;
        }
        Thread.setDefaultUncaughtExceptionHandler(new ExceptionHandler(context));
    }

    public static boolean checkForCrash(final XmppActivity activity) {
        try {
            Class.forName("io.sentry.Sentry");
            return false;
        } catch (final ClassNotFoundException e) { }

        try {
            final XmppConnectionService service = activity == null ? null : activity.xmppConnectionService;
            if (service == null) {
                return false;
            }
            final AppSettings appSettings = new AppSettings(activity);
            if (!appSettings.isSendCrashReports() || Config.BUG_REPORTS == null) {
                return false;
            }
            final Account account = AccountUtils.getFirstEnabled(service);
            if (account == null) {
                return false;
            }
            final FileInputStream file = activity.openFileInput(FILENAME);
            final InputStreamReader inputStreamReader = new InputStreamReader(file);
            final BufferedReader stacktrace = new BufferedReader(inputStreamReader);
            final StringBuilder report = new StringBuilder();
            final PackageManager pm = activity.getPackageManager();
            final PackageInfo packageInfo;
            try {
                packageInfo = pm.getPackageInfo(activity.getPackageName(), PackageManager.GET_SIGNATURES);
                final String versionName = packageInfo.versionName;
                final int versionCode = packageInfo.versionCode;
                final int version = versionCode > 10000 ? (versionCode / 100) : versionCode;
                report.append(String.format(Locale.ROOT, "Version: %s(%d)", versionName, version)).append('\n');
                report.append("Last Update: ").append(DATE_FORMAT.format(new Date(packageInfo.lastUpdateTime))).append('\n');
                Signature[] signatures = packageInfo.signatures;
                if (signatures != null && signatures.length >= 1) {
                    report.append("SHA-1: ").append(CryptoHelper.getFingerprintCert(packageInfo.signatures[0].toByteArray())).append('\n');
                }
                report.append('\n');
            } catch (final Exception e) {
                return false;
            }
            String line;
            while ((line = stacktrace.readLine()) != null) {
                report.append(line);
                report.append('\n');
            }
            file.close();
            activity.deleteFile(FILENAME);
            final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);
            builder.setTitle(activity.getString(R.string.crash_report_title, activity.getString(R.string.app_name)));
            builder.setMessage(activity.getString(R.string.crash_report_message, activity.getString(R.string.app_name)));
            builder.setPositiveButton(activity.getText(R.string.send_now), (dialog, which) -> {

                Log.d(Config.LOGTAG, "using account=" + account.getJid().asBareJid() + " to send in stack trace");
                Conversation conversation = service.findOrCreateConversation(account, Config.BUG_REPORTS, false, true);
                Message message = new Message(conversation, report.toString(), Message.ENCRYPTION_NONE);
                service.sendMessage(message);
            });
            builder.setNegativeButton(activity.getText(R.string.send_never), (dialog, which) -> appSettings.setSendCrashReports(false));
            builder.create().show();
            return true;
        } catch (final IOException ignored) {
            return false;
        }
    }

    static void writeToStacktraceFile(Context context, String msg) {
        try {
            OutputStream os = context.openFileOutput(FILENAME, Context.MODE_PRIVATE);
            os.write(msg.getBytes());
            os.flush();
            os.close();
        } catch (IOException ignored) {
        }
    }
}
