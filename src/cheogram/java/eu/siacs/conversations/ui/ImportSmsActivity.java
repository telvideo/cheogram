package eu.siacs.conversations.ui;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.provider.BaseColumns;
import android.provider.Telephony;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.lang.Thread;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityImportSmsBinding;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.MimeUtils;
import eu.siacs.conversations.utils.PhoneNumberUtilWrapper;
import eu.siacs.conversations.utils.ThemeHelper;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.stanzas.IqPacket;
import io.michaelrocks.libphonenumber.android.NumberParseException;

// Credits: implementation inspiration drawn from:
// - SMS I/E Android app source https://github.com/tmo1/sms-ie
// - Stack Overflow discussion:
//   https://stackoverflow.com/questions/3012287/how-to-read-mms-data-in-android/6446831#6446831

/*
 * Commentary:
 *
 * This activity imports SMS/MMS message from the phone's message history into Cheogram's
 * message history.  It attempts to translate group chats to the JID format used by the
 * Cheogram PSTN gateway.
 *
 * Messages are deduplicated.  So running an import more than once should only import new
 * phone messages.
 *
 * The UI consists of a start button, a progress report, and a phone number to be filtered
 * out of the group chat JID.
 *
 * The start button should be self explanatory.  The progress report consists of three
 * counters and a progress bar.  The three counters display the number messages
 * successfully imported, the number of duplicates detected (skipped), and the number of
 * errors detected during the import.  If errors occur, the user should be encouraged to
 * provide a logcat for forensic analysis.
 *
 * The filtered phone number (labeled "MMS -> Group Chat Filter" in the UI) is used to
 * remove a phone number from MMS recipient phone number lists.  The number is filtered in
 * order to generate a JID formatted link as a PSTN gateway group chat JID.  Specifically,
 * the current user's phone number is not included in PSTN group chat JIDs.  The number is
 * initialized by asking the PSTN gateway for the current account's phone number.  The
 * field is editable, allowing the user to import the phone message data store inherited
 * from a different phone number.  Editing the field to a spurious phone number will
 * suppress filtering.
 *
 * The import process runs as a background thread.  It starts with iterating over the
 * phone messages by conversation (threads in Android Telephony parlance).  Group threads
 * have no SMS messages.  One to one threads can consist of both SMS and MMS messages.
 * So, for each thread, SMS and MMS messages are processed in parallel, building a
 * conversation in ascending date order.  This preserves message order by arrival date
 * when the conversation is opened in ConversationsActivity.
 */


/*
 * UI
 */

public class ImportSmsActivity extends XmppActivity {
    public static class CounterViewModel extends ViewModel {
        private final AtomicInteger counter = new AtomicInteger(0);
        private final MutableLiveData<Integer> value =
                new MutableLiveData<>(0);

        public LiveData<Integer> getValue() {
            return value;
        }

        public void increment() {
            value.postValue(counter.incrementAndGet());
        }

        public void reset() {
            counter.set(0);
            value.postValue(0);
        }
    }
    // ViewModelProvider goes out of its way to provide only one instance of a ViewModel
    // class per ViewModelStoreOwner (e.g. this Activity).  So we have a choice: manage
    // all three counters in a single class, or provide separate classes for each counter.
    // We choose the latter.
    public static class ImportedCounterViewModel extends CounterViewModel { }
    public static class SkippedCounterViewModel extends CounterViewModel { }
    public static class ErrorsCounterViewModel extends CounterViewModel { }

    public interface onPhoneNumberRetrieved {
        void updatePhoneNumber(String phoneNumber);
    }

    enum Direction {
        MESSAGE_SENT,
        MESSAGE_RECEIVED
    }
    // Definition of PDU_HEADERS_FROM copied from in AOSP:
    // frameworks/base/telephony/common/com/google/android/mms/pdu/PduHeaders.java
    private static final int PDU_HEADERS_FROM = 0x89;
    private static final String CHEOGRAM_ADDRESS = "cheogram.com";
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private Context activity;
    private final AtomicBoolean stopImport = new AtomicBoolean(false); // Set by onStop to terminate import loop
    private Account account = null;
    private ActivityImportSmsBinding binding;
    private Jid jid;
    private Thread importThread;
    private TextView doneNotice;
    private Button startButton;
    private TextView phoneNumber;
    private ImportedCounterViewModel imported;
    private SkippedCounterViewModel skipped;
    private ErrorsCounterViewModel errors;
    private ContentResolver cr;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activity = this;
        cr = getContentResolver();
        setTheme(ThemeHelper.find(this));
        binding = DataBindingUtil.setContentView(this, R.layout.activity_import_sms);
        setSupportActionBar(binding.toolbar);
        startButton = binding.startButton;
        startButton.setOnClickListener(view -> {
                if (!startImport()) {
                    Toast.makeText(this, R.string.sms_import_already_running, Toast.LENGTH_LONG).show();
                }
            });
        doneNotice = binding.doneNotice;
        phoneNumber = binding.phoneNumber;
        imported = new ViewModelProvider(this).get(ImportedCounterViewModel.class);
        skipped = new ViewModelProvider(this).get(SkippedCounterViewModel.class);
        errors = new ViewModelProvider(this).get(ErrorsCounterViewModel.class);

        final Observer<Integer> importedObserver = value -> {
            binding.importedCount.setText(NumberFormat.getInstance().format(value));
            updateProgress();
        };
        final Observer<Integer> skippedObserver = value -> {
            binding.skippedCount.setText(NumberFormat.getInstance().format(value));
            updateProgress();
        };
        final Observer<Integer> errorsObserver = value -> {
            binding.errorsCount.setText(NumberFormat.getInstance().format(value));
            updateProgress();
        };
        imported.getValue().observe(this, importedObserver);
        skipped.getValue().observe(this, skippedObserver);
        errors.getValue().observe(this, errorsObserver);
    }

    private void updateProgress() {
        int progress = 0;
        Integer i = imported.getValue().getValue();
        Integer s = skipped.getValue().getValue();
        Integer e = errors.getValue().getValue();
        if (i != null) {
            progress += i;
        }
        if (s != null) {
            progress += s;
        }
        if (e != null) {
            progress += e;
        }
        binding.progressBar.setProgress(progress);
    }

    @Override
    public void onStart() {
        super.onStart();
        startButton.setEnabled(false);
        doneNotice.setVisibility(View.GONE);
        jid = Jid.ofEscaped(getIntent().getStringExtra(EXTRA_ACCOUNT));
        if (xmppConnectionServiceBound) {
            connectionBound();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (importThread != null) {
            stopImport.set(true);
            try {
                importThread.join();
            } catch (InterruptedException ex) {
                Log.i(Config.LOGTAG, "Import interrupted.");
            }
            stopImport.set(false);
        }
    }
    @Override
    protected void refreshUiReal() {
        // It appears we need not do anything here.  We extend XmppActivity instead of
        // ActionBarActivity to lookup Account by JID during onStart().  But we run as a
        // background thread.  All processing is local, so this UI should not be affected
        // by connection changes.
    }

    @Override
    protected void onBackendConnected() {
        if (xmppConnectionServiceBound && account == null) {
            connectionBound();
        }
    }

    private void connectionBound() {
        account = xmppConnectionService.findAccountByJid(jid);
        lookupPhoneNumber(value -> {
            startButton.setEnabled(true);
            phoneNumber.setText(value);
        });
    }

    /*
     * Phone number lookup
     */

    private Contact pstnGatewayContact() {
        for (Contact contact : account.getRoster().getContacts()) {
            if (contact.getPresences().anyIdentity("gateway", "pstn")) {
                return contact;
            }
        }
        return null;
    }

    private static String extractPhoneNumber(Element command) {
        if (command.getAttribute("status").equals("completed")) {
            for (Element elt : command.getChildren()) {
                if (elt.getName().equals("x") &&
                    elt.getNamespace().equals(Namespace.DATA)) {
                    for (Element child : elt.getChildren()) {
                        if (child.getName().equals("field") &&
                            child.getAttribute("var").equals("tel")) {
                            return child.findChildContent("value");
                        }
                    }
                }
            }
        }
        return null;
    }

    private void lookupPhoneNumber(onPhoneNumberRetrieved callback) {
        final Contact contact = pstnGatewayContact();
        if (contact == null) {
            Log.w(Config.LOGTAG, "No PSTN gateway found.");
            return;
        }
        final IqPacket packet = new IqPacket(IqPacket.TYPE.SET);
        final Element element = packet.addChild("command", Namespace.COMMANDS)
            .setAttribute("node", "info")
            .setAttribute("action", "execute");
        packet.setTo(contact.getJid());
        packet.addChild(element);

        xmppConnectionService.sendIqPacket(account, packet, (a, response) -> {
            if (response.getType() == IqPacket.TYPE.RESULT) {
                Element command = response.findChild("command", Namespace.COMMANDS);
                if (response.getType() == IqPacket.TYPE.RESULT && command != null) {
                    String phone = extractPhoneNumber(command);
                    if (phone == null) {
                        Log.w(Config.LOGTAG, "Unrecognized phone number query response: " + response);
                    } else {
                        runOnUiThread(() -> callback.updatePhoneNumber(phone));
                    }
                }
            }
        });
    }

    /*
     * Importer: start import background thread
     */

    private boolean startImport() {
        if (!running.compareAndSet(false, true)) {
            return false;
        } else {
            imported.reset();
            skipped.reset();
            errors.reset();
            startButton.setEnabled(false);
            doneNotice.setVisibility(View.GONE);
            importThread = new Thread(() -> {
                    importConversations();
                    importThread = null;
                    running.set(false);
                    runOnUiThread(() -> {
                            startButton.setEnabled(true);
                            doneNotice.setVisibility(View.VISIBLE);
                        });
            });
            importThread.setName(getClass().getSimpleName());
            importThread.start();
        }
        return true;
    }

    private int messageCount(Uri uri) {
        final String[] projection = new String[] {
                BaseColumns._ID
        };
        Cursor cursor = cr.query(uri, projection, null, null, null);
        cursor.moveToFirst();
        final int count = cursor.getCount();
        cursor.close();
        return count;
    }

    private void importConversations() {
        final Uri uri = Telephony.MmsSms.CONTENT_CONVERSATIONS_URI
            .buildUpon()
            .build();
        final String[] projection = new String[] {
            "thread_id"
        };
        runOnUiThread(() -> binding.
                      progressBar.
                      setMax(messageCount(Telephony.Sms.CONTENT_URI) +
                             messageCount(Telephony.Mms.CONTENT_URI)));

        final Cursor cursor = cr.query(uri, projection, null, null, "date ASC");
        cursor.moveToFirst();

        final int _thread_id = cursor.getColumnIndexOrThrow("thread_id");
        for (cursor.moveToFirst(); !stopImport.get() && !cursor.isAfterLast(); cursor.moveToNext()) {
            importConversation(cursor.getString(_thread_id));
        }
        cursor.close();
    }

    private void importConversation(String threadId) {
        final SmsImporter smsImporter = new SmsImporter(threadId);
        final MmsImporter mmsImporter = new MmsImporter(threadId);
        Conversation conversation;
        conversation = smsImporter.getConversation();
        if (conversation != null) {
            smsImporter.importMergedMessages(conversation, mmsImporter);
        } else {
            conversation = mmsImporter.getConversation();
            if (conversation != null) {
                mmsImporter.importMessages(conversation);
            } else {
                smsImporter.close();
                mmsImporter.close();
                throw new IllegalStateException("Thread: " + threadId + " has no messages.");
            }
        }
        smsImporter.close();
        mmsImporter.close();
    }

    /*
     * Utility functions
     */

    private String normalizePhoneNumber(String input)
        throws IllegalArgumentException, NumberParseException {
        try {
            // TODO: Generalize ;phone-context to support international short codes.
            if (input.length() < 7 && input.matches("^[0-9]+$")) {
                return input + ";phone-context=ca-us.phone-context.soprani.ca";
            }
            if (input.endsWith("voice.google.com")) {
                // it appears that google voice numbers for 1-1 chats are of the form
                // "<gv>.<contact>.<convo>.voice.google.com" where <gv> is the
                // subscriber's google voice number, <contact> is the correspondent's
                // phone number, and <convo> is some randomized string linked to the
                // conversation between the two.
                //
                // TBD: it is not clear if the format changes for group chats.
                // TODO: what other phone number formats need support?
                final String[] numbers = input.split("\\.", 3);
                if (numbers.length != 3) {
                    throw new IllegalArgumentException("Unrecognized google voice number format:" + input);
                }
                return PhoneNumberUtilWrapper.normalize(this, numbers[1]);
            }
            return PhoneNumberUtilWrapper.normalize(this, input);
        } catch (IllegalArgumentException e) {
            Log.e(Config.LOGTAG, "Unable to normalize phone number: \"" + input + "\"");
            Log.e(Config.LOGTAG, e.getMessage());
            throw e;
        } catch (NumberParseException e) {
            Log.e(Config.LOGTAG, "Unable to parse phone number: \"" + input + "\"");
            Log.e(Config.LOGTAG, e.getMessage());
            throw e;
        }
    }

    private static Jid phoneNumberToJid(String input) {
        return Jid.ofLocalAndDomain(input, CHEOGRAM_ADDRESS);
    }

    private static Jid phoneNumberToJid(List<String> input) {
        return phoneNumberToJid(String.join(",", input));
    }

    private String messageIdToString(Message message) {
	return message.getAvatarName() + " " +
	    UIHelper.readableTimeDifferenceFull(activity, message.getMergedTimeSent());
    }

    private Message createMessage(Conversation conversation, String body,
                                  Direction direction, Long date, Long dateSent,
                                  String serverMsgId) {
        final Message message = new Message(conversation, body,
                                            Message.ENCRYPTION_NONE,
                                            direction == Direction.MESSAGE_RECEIVED
                                            ? Message.STATUS_RECEIVED : Message.STATUS_SEND);
        message.setServerMsgId(serverMsgId);
        message.setTime(dateSent == 0 ? date : dateSent);
        message.setTimeReceived(date);
        return message;
    }

    private boolean commitMessage(Conversation conversation, Message message, boolean read) {
        if (read) {
            message.markRead();
        } else {
            message.markUnread();
        }
        if (conversation.hasDuplicateMessage(message)) {
            return false;
        }
        conversation.add(message);
        xmppConnectionService.databaseBackend.createMessage(message);
        return true;
    }

    private Contact findContactByJid(Jid contactJid) {
        final String cjid = contactJid.toString();
        for (Contact contact : account.getRoster().getContacts()) {
            if (cjid.equals(contact.getJid().toString())) {
                return contact;
            }
        }
        return null;
    }

    private Contact findContactByDisplayName(String displayName) {
        for (Contact contact : account.getRoster().getContacts()) {
            if (displayName.equals(contact.getDisplayName())) {
                return contact;
            }
        }
        return null;
    }

    /*
     * Inner classes for SMS/MMS specific processing.
     *
     * There are two importers, one for each message type: SmsImporter and MmsImporter.
     * They derived from a common abstract base class: PstnMessageImporter.
     */

    private abstract class PstnMessageImporter {
        protected Cursor cursor;
        protected String threadId;
        protected Conversation conversation;

        abstract Conversation findOrCreateConversation();
        // SMS dates are reported in milliseconds, MMS dates are reported in seconds, the
        // importer's getDate() returns milliseconds.
        abstract Long getDate();
        // importMessage() returns true if the message was imported, false if it was
        // skipped as a duplicate.
        abstract boolean importMessage(Conversation conversation)
            throws IllegalArgumentException, NumberParseException;

        public PstnMessageImporter(String threadId) {
            this.threadId = threadId;
        }

        public void close() {
            if (conversation != null) {
                conversation.trim();
                conversation = null;
            }
            cursor.close();
        }

        private void importOneMessage(Conversation conversation) {
            try {
                if (importMessage(conversation)) {
                    imported.increment();
                } else {
                    skipped.increment();
                }
            } catch (Throwable throwable) {
                Log.e(Config.LOGTAG, "Import exception: " + throwable.getMessage());
                Log.e(Config.LOGTAG, Log.getStackTraceString(throwable));
                errors.increment();
            }
            cursor.moveToNext();
        }

        public void importMessages(Conversation conversation) {
            while (!stopImport.get() && !cursor.isAfterLast()) {
                importOneMessage(conversation);
            }
        }

        public void importMergedMessages(Conversation conversation, PstnMessageImporter other) {
            // interleave SMS/MMS in received order
            while (!stopImport.get() && !cursor.isAfterLast()) {
                Long thisDate = getDate();
                if (thisDate == null) {
                    other.importMessages(conversation);
                }
                Long otherDate = other.getDate();
                PstnMessageImporter importer = this;
                if (thisDate == null) {
                    importer = other;
                } else if (otherDate != null && otherDate < thisDate) {
                    importer = other;
                }
                importer.importOneMessage(conversation);
            }
        }

        public Conversation getConversation() {
            if (conversation != null || cursor.isAfterLast()) {
                return null;
            }
            // in order to prevent importing duplicate messages
            // (Conversation.hasDuplicateMessage()), attempt to vacuum up all messages
            // associated with a conversation before importing a PSTN thread.
            conversation = findOrCreateConversation();
            List<Message> history = xmppConnectionService
                .databaseBackend
                .getMessages(conversation, 1024 * 1024 * 1024); // large enough?
            conversation.clearMessages();
            conversation.addAll(0, history);
            return conversation;
        }
    }

    /*
     * In order to present conversations in ascending order of arrival, we group imported
     * messages by Android Telephony threads.
     *
     * The (sparse) documentation for `Telephony.MmsSms` along with tribal knowledge from
     * https://stackoverflow.com/questions/3012287/how-to-read-mms-data-in-android/6446831#6446831
     * suggest the messages can be retrieved from the `ContentProvider` at URI
     * `content://mms-sms/conversations/xxx`.  After pouring over
     * src/com/android/providers/telephony/MmsSmsProvider.java, this seems plausible.
     *
     * But implementing on such a poorly documented interface is fraught with peril.  And
     * experimenting with it suggests the interface is brittle and inconsistently
     * implemented across Android versions.
     *
     * So we use `Telephony.MmsSms.CONTENT_CONVERSATIONS_URI`(`content://mms-sms/`) only
     * to get a list of threads (conversations) and draw messages associated with each
     * `thread_id` from `Telephony.Sms.CONTENT_URI` and `Telephony.Mms.CONTENT_URI`.
     *
     * One-to-one threads can contain both `Telephony.Sms` and `Telephony.Mms` messages.
     * The former for simple texts, the latter for messages with image/file attachments.
     * To associate these messages with a `Conversation`, we translate the correspondent's
     * phone number(s) to a Cheogram gateway JID.
     *
     * Group texts consist only of `Telephony.Mms` messages.  These messages have an
     * associated recipient list which contains the phone number of all participants.  In
     * order to generate a Cheogram gateway `JID` from the `MMS` thread, we remove the
     * account's phone number from the recipient list, then sort and concatenate the rest
     * of the recipient's phone numbers.
     */

    private class SmsImporter extends PstnMessageImporter {
        private int _id;
        private int _address;
        private int _date;
        private int _body;
        private int _type;
        private int _read;
        private int _dateSent;

        public SmsImporter(String threadId) {
            super(threadId);
            final String[] projection = new String[] {
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.DATE,
                Telephony.Sms.BODY,
                Telephony.Sms.TYPE,
                Telephony.Sms.READ,
                Telephony.Sms.DATE_SENT
            };
            final String selection = Telephony.Sms.THREAD_ID + "=?";
            final String[] selectionArgs = new String[] {
                threadId
            };

            cursor = cr.query(Telephony.Sms.CONTENT_URI, projection, selection, selectionArgs, "date ASC");
            if (cursor.moveToFirst()) {
                _id = cursor.getColumnIndexOrThrow(Telephony.Sms._ID);
                _address = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS);
                _date = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE);
                _body = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY);
                _type = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE);
                _read = cursor.getColumnIndexOrThrow(Telephony.Sms.READ);
                _dateSent = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT);
            }
        }

        protected Long getDate() {
            return cursor.isAfterLast() ? null : cursor.getLong(_date);
        }

        Conversation findOrCreateConversation() {
            Jid contactJid = phoneNumberToJid(cursor.getString(_address));
            // not sure how universal this is.  it looks like the phone number for SMS
            // messages imported from Signal are not reliably attributable to the actual
            // sender.  when the phone number is not the sender's, the sender's name is
            // prepended to the body separated by a hyphen.
            //
            // try looking up the contact in the roster.  if that fails examine the body
            // and, if possible, attempt to find and substitute a roster contact with a
            // matching name.
            if (findContactByJid(contactJid) == null) {
                String body = cursor.getString(_body);
                String [] splits = body.split(" - ", 2);
                if (splits.length == 2) {
                    Contact contact = findContactByDisplayName(splits[0]);
                    if (contact != null) {
                        contactJid = contact.getJid();
                    }
                }
            }
            return xmppConnectionService.findOrCreateConversation(account, contactJid, false, false);
        }

        private Direction messageDirection(int telephonyType) {
            Direction direction;
            switch (telephonyType) {
            case Telephony.TextBasedSmsColumns.MESSAGE_TYPE_INBOX:
                direction = Direction.MESSAGE_RECEIVED;
                break;
            case Telephony.TextBasedSmsColumns.MESSAGE_TYPE_SENT:
                direction = Direction.MESSAGE_SENT;
                break;
            default:
                throw new IllegalStateException("Invalid type: " + telephonyType);
            }
            return direction;
        }

        public boolean importMessage(Conversation conversation)
            throws IllegalArgumentException {
            final Long date = cursor.getLong(_date);
            final Long dateSent = cursor.getLong(_dateSent);
            final boolean read = !cursor.getString(_read).equals("0");
            Message message = createMessage(conversation, cursor.getString(_body),
                                            messageDirection(cursor.getInt(_type)),
                                            date, dateSent, "SMS" + cursor.getString(_id));
            return commitMessage(conversation, message, read);
        }
    }

    /*
     * Helper class for extracting MMS sender and recipient addresses.
     */
    private class MmsAddresses {
        private final Jid sender;
        private final Jid contactJid;

        public MmsAddresses(String msgId) throws IllegalArgumentException, NumberParseException {
            final Uri uri = Telephony.Mms.CONTENT_URI
                .buildUpon()
                .appendPath(msgId)
                .appendPath("addr")
                .build();
            final String [] projection = {
                Telephony.Mms.Addr.ADDRESS,
                Telephony.Mms.Addr.TYPE
            };
            final Cursor cursor = cr.query(uri, projection, null, null, null);
            if (cursor == null || !cursor.moveToFirst()) {
                throw new IllegalArgumentException("No MmsAddresses for message ID " + msgId);
            }
            final int address = cursor.getColumnIndex(Telephony.Mms.Addr.ADDRESS);
            final int type = cursor.getColumnIndex(Telephony.Mms.Addr.TYPE);
            final List<String> participants = new ArrayList<>();
            final List<String> senders = new ArrayList<>();
            final String phone = normalizePhoneNumber(phoneNumber.getText().toString());

            for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                final String addr = normalizePhoneNumber(cursor.getString(address));
                if (!phone.equals(addr)) {
                    if (cursor.getInt(type) == PDU_HEADERS_FROM) {
                        senders.add(addr);
                    } else {
                        participants.add(addr);
                    }
                }
            }
            cursor.close();
            if (senders.size() == 0) {
                if (participants.isEmpty()) {
                    throw new IllegalArgumentException("No addresses found for MMS _id " + msgId);
                }
                this.sender = null;
            } else if (senders.size() > 1) {
                throw new IllegalArgumentException("Multiple senders found for MMS _id " + msgId
                                                   + ": " + String.join(",", senders));
            } else {
                this.sender = phoneNumberToJid(senders.get(0));
            }

            if (participants.isEmpty()) {
                contactJid = null;
            } else {
                if (senders.size() == 1) {
                    participants.add(senders.get(0));
                }

                participants.sort(Comparator.naturalOrder());
                contactJid = participants.isEmpty() ? null : phoneNumberToJid(participants);
            }
        }

        @Nullable
        public Jid sender() {
            return sender;
        }

        @Nullable
        public Jid contactJid() {
            return contactJid;
        }
    }

    /*
     * Helper class for gathering a list of MMS message attachments.
     */
    private class MmsAttachments {
        private class Part {
            String id;
            String type;
            String value;

            public Part(String id, String type, String value) {
                this.id = id;
                this.type = type;
                this.value = value;
            }
            String getId() { return id; }
            String getType() {return type; }
            String getValue() { return value; }
        }
        List<Part> parts;
        String body;

        public MmsAttachments(String msgId) throws IllegalArgumentException {
            // build the URI because the constant Telephony.Mms.Part.CONTENT_URI requires
            // API 29.
            final Uri uri = Telephony.Mms.CONTENT_URI
                .buildUpon()
                .appendPath("part")
                .build();
            final String [] projection = {
                Telephony.Mms.Part._ID,
                Telephony.Mms.Part.CONTENT_TYPE,
                Telephony.Mms.Part.TEXT,
                Telephony.Mms.Part._DATA
            };
            final String selection = Telephony.Mms.Part.MSG_ID + "=?";
            final String[] selectionArgs = new String[] {
                msgId
            };

            final Cursor cursor = cr.query(uri,
                                           projection,
                                           selection,
                                           selectionArgs,
                                           Telephony.Mms.Part.SEQ + " ASC");
            final int _id = cursor.getColumnIndexOrThrow(Telephony.Mms.Part._ID);
            final int _type = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_TYPE);
            final int _text = cursor.getColumnIndexOrThrow(Telephony.Mms.Part.TEXT);
            final int _data = cursor.getColumnIndexOrThrow(Telephony.Mms.Part._DATA);
            final List<Part> parts = new ArrayList<>();
            final StringBuilder sb = new StringBuilder();

            for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                final String type = cursor.getString(_type);
                final String value = cursor.getString(_data);
                // Mime type          | action
                //--------------------|--------------------
                // "text/plain"       | concatenate as body
                // "application/smil" | ignore
                // others             | treat as attachment
                if ("text/plain".equals(type)) {
                    sb.append(cursor.getString(_text));
                } else if (! "application/smil".equals(type))  {
                    parts.add(new Part(cursor.getString(_id), type, value));
                }
            }
            cursor.close();
            this.body = sb.toString();
            this.parts = parts;
        }

        public List<Part> getParts() { return parts; }
        public String getBody() { return body; }
    }

    /*
     * MMS message importer.
     */
    private class MmsImporter extends PstnMessageImporter {
        private int _id;
        private int _date;
        private int _dateSent;
        private int _messageBox;
        private int _read;

        public MmsImporter(String threadId) {
            super(threadId);
            final String[] projection = new String[] {
                Telephony.Mms._ID,
                Telephony.Mms.DATE,
                Telephony.Mms.DATE_SENT,
                Telephony.Mms.MESSAGE_BOX,
                Telephony.Mms.READ,
                Telephony.Mms.TEXT_ONLY
            };
            final String selection = Telephony.Mms.THREAD_ID + "=?";
            final String[] selectionArgs = new String [] {
                threadId
            };

            cursor = cr.query(Telephony.Mms.CONTENT_URI, projection, selection, selectionArgs, "date ASC");
            if (cursor.moveToFirst()) {
                _id = cursor.getColumnIndexOrThrow(Telephony.Mms._ID);
                _date = cursor.getColumnIndexOrThrow(Telephony.Mms.DATE);
                _dateSent = cursor.getColumnIndexOrThrow(Telephony.Mms.DATE_SENT);
                _messageBox = cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX);
                _read = cursor.getColumnIndexOrThrow(Telephony.Mms.READ);
            }
        }

        private Long getDate(int index) {
            return cursor.isAfterLast() ? null : cursor.getLong(index) * 1000;
        }

        protected Long getDate() {
            return getDate(_date);
        }

        Conversation findOrCreateConversation() {
            try {
                final MmsAddresses addresses = new MmsAddresses(cursor.getString(_id));
                Jid contactJid = addresses.contactJid();
                if (contactJid == null) {
                    return xmppConnectionService.findOrCreateConversation(account, addresses.sender(), false, false);
                }
                return xmppConnectionService
                    .findOrCreateConversation(account, addresses.contactJid(), false, false);

            } catch (NumberParseException e) {
                Log.e(Config.LOGTAG, "Cannot create conversation for thread " + threadId);
                Log.e(Config.LOGTAG, e.getMessage());
                return null;
            }
        }

        private Direction messageDirection(int telephonyType) {
            Direction direction;
            switch (telephonyType) {
            case Telephony.BaseMmsColumns.MESSAGE_BOX_INBOX:
                direction = Direction.MESSAGE_RECEIVED;
                break;
            case Telephony.BaseMmsColumns.MESSAGE_BOX_OUTBOX:
            case Telephony.BaseMmsColumns.MESSAGE_BOX_DRAFTS:
            case Telephony.BaseMmsColumns.MESSAGE_BOX_SENT:
            case Telephony.BaseMmsColumns.MESSAGE_BOX_FAILED:
                direction = Direction.MESSAGE_SENT;
                break;
            default:
                throw new IllegalStateException("Invalid type: " + telephonyType);
            }
            return direction;
        }

        private void attachFile(Message message, String id, String mimeType)
            throws IOException, XmppConnectionService.BlockedMediaException {
            final Uri uri = Telephony.Mms.CONTENT_URI
                .buildUpon()
                .appendPath("part")
                .appendPath(id)
                .build();

            try (InputStream in = cr.openInputStream(uri)) {
                int index = mimeType.indexOf("/");
                String extension = index < 0 ? mimeType : mimeType.substring(index + 1);
                if (extension.isEmpty()) {
                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    retriever.setDataSource(activity, uri);
                    String mt = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
                    extension = MimeUtils.guessExtensionFromMimeType(mt);
                }
                if (extension.isEmpty()) {
                    Log.w(Config.LOGTAG,
                            "Unable to determine mimetype for " + uri.toString() +
                                    " for message " + id + " it thread " + threadId);
                }
                xmppConnectionService
                        .getFileBackend()
                        .setupRelativeFilePath(message, in, extension);
            } catch (Exception e) {
                Log.e(Config.LOGTAG, "Exception processing message" + messageIdToString(message));
                throw e;
            }

            File destination = new File(message.getRelativeFilePath());
            if (destination.exists()) {
                return;
            }
            File parent = destination.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Log.w(Config.LOGTAG, "Unable to create parent directory: " + parent);
            }
            if (!destination.createNewFile()) {
                Log.w(Config.LOGTAG, "Unable to create destination file: " + destination);
            }
            try (InputStream is = cr.openInputStream(uri)) {
                try (FileOutputStream os = new FileOutputStream(destination)) {
                    final byte[] buffer = new byte[4096];
                    int len;
                    while ((len = is.read(buffer)) > 0) {
                        os.write(buffer, 0, len);
                    }
                } catch (IOException e) {
                    Log.e(Config.LOGTAG, "I/O error copying MMS part ID " + id +
			  " for message " + messageIdToString(message));
                    throw e;
                }
            }
        }

        public Message.FileParams makeFileParams(String name, long size, int width,
                                                 int height, long duration) {
            final Element reference = new Element("reference");
            reference.setAttribute("xmlns", "urn:xmpp:reference:0");
            reference.setAttribute("uri", "file://" + name);
            final Element mediaSharing = new Element("media-sharing");
            mediaSharing.setAttribute("xmlns", "urn:xmpp:sims:1");
            reference.addChild(mediaSharing);
            final Element file = new Element("file");
            file.setAttribute("xmlns", "urn:xmpp:jingle:apps:file-transfer:5");
            mediaSharing.addChild(file);
            if (size > 0) {
                final Element sizeElement = new Element("size");
                sizeElement.setAttribute("xmlns", "urn:xmpp:jingle:apps:file-transfer:5");
                sizeElement.setContent(Long.toString(size));
                file.addChild(sizeElement);
            }
            if (width > 0) {
                final Element widthElement = new Element("width");
                widthElement.setAttribute("xmlns", "https://schema.org/");
                widthElement.setContent(Integer.toString(width));
                file.addChild(widthElement);
            }
            if (height > 0) {
                final Element heightElement = new Element("height");
                heightElement.setAttribute("xmlns", "https://schema.org/");
                heightElement.setContent(Integer.toString(height));
                file.addChild(heightElement);
            }
            if (duration > 0) {
                final Element durationElement = new Element("duration");
                durationElement.setAttribute("xmlns", "https://schema.org/");
                durationElement.setContent("PT" + duration / 1000 + "S");
                file.addChild(durationElement);
            }
            final Element sources = new Element("sources");
            sources.setAttribute("xmlns", "urn:xmpp:sims:1");
            mediaSharing.addChild(sources);
            final Element ref = new Element("reference");
            ref.setAttribute("xmlns",  "urn:xmpp:reference:0");
            ref.setAttribute("uri", "file://" + name);
            sources.addChild(ref);
            return new Message.FileParams(reference);
        }

        public void attachFileMetadata(Message message, String mimeType, String source) {
            message.setType(mimeType.startsWith("image/")
                            ? Message.TYPE_IMAGE : Message.TYPE_FILE);
            try {
                final String fileName = message.getRelativeFilePath();
                final File file = new File(fileName);
                long size = file.length();
                if (mimeType.startsWith("image/")) {
                    final BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(fileName, options);
                    int width = options.outWidth;
                    int height = options.outHeight;
                    message.setFileParams(makeFileParams(source, size, width, height, 0));
                } else if (mimeType.startsWith("video/")) {
                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    retriever.setDataSource(activity, Uri.fromFile(file));
                    String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    long durationMilli = Long.parseLong(duration);
                    message.setFileParams(makeFileParams(fileName, size, 0, 0, durationMilli));
                }
            } catch (Exception e) {
                Log.e(Config.LOGTAG, "Exception: " + e.getMessage());
                Log.e(Config.LOGTAG, "Attaching " + message.getRelativeFilePath() +
		      " for message " + messageIdToString(message));
                throw e;
            }
        }

        /*
         *  MMS messages represent either messages in a group conversation (with or without
         *  files or media), or messages in a one to one conversation that have attached
         *  files or media.
         *
         *  Messages in a group conversation are tagged with the sender's phone number.
         *  Messages in a one to one conversation are not.
         *
         *  The text associated with the message (if any) is treated as the message body.
         *  It is associated with the first attachment if attachments exist.
         */

        public boolean importMessage(Conversation conversation)
            throws IllegalArgumentException, NumberParseException {
            final String id = cursor.getString(_id);
            final int messageBox = cursor.getInt(_messageBox);
            final Long date = getDate(_date);
            final Long dateSent = getDate(_dateSent);
            final MmsAddresses addresses = new MmsAddresses(id);
            final MmsAttachments attachments = new MmsAttachments(id);
            final boolean read = !cursor.getString(_read).equals("0");
            final boolean isGroup = addresses.contactJid() != null && addresses.sender() != null;
            final String bodyAttribution = isGroup ? "<xmpp:" + addresses.sender() + "> " : "";
            final String body = bodyAttribution + attachments.getBody();
            boolean result = false;
            boolean attachment = false;
            Message message;
            boolean attachmentError = false;
            for (MmsAttachments.Part part : attachments.getParts()) {
                message = createMessage(conversation, body,
                                        messageDirection(messageBox), date, dateSent,
                                        "MMS" + cursor.getString(_id) + "-" + part.getId());
                attachment = true;
                try {
                    attachFile(message, part.getId(), part.getType());
                    attachFileMetadata(message, part.getType(), part.getValue());
                } catch (Exception e) {
                    Log.e(Config.LOGTAG, "Exception: " + e.getMessage());
                    attachmentError = true;
                }
                result |= commitMessage(conversation, message, read);
            }
            if (!attachment) {
                // if we have not encountered a file attachment, then this is a text only
                // message in a group chat.  note: result is still false at this point.
                message = createMessage(conversation,
                                        body,
                                        messageDirection(messageBox),
                                        date, dateSent,
                                        "MMS" + cursor.getString(_id));
                result = commitMessage(conversation, message, read);
            }
            if (attachmentError) {
                throw new IllegalArgumentException("Error processing MMS message " + id);
            }
            return result;
        }
    }
}
