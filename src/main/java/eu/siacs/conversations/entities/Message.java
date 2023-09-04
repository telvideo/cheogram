package eu.siacs.conversations.entities;

import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.graphics.Color;
import android.os.Build;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.text.style.ClickableSpan;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;
import android.view.View;

import com.cheogram.android.BobTransfer;
import com.cheogram.android.GetThumbnailForCid;
import com.cheogram.android.InlineImageSpan;
import com.cheogram.android.SpannedToXHTML;

import com.google.common.io.ByteSource;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Longs;

import org.json.JSONException;

import java.lang.ref.WeakReference;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CopyOnWriteArraySet;

import io.ipfs.cid.Cid;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.crypto.axolotl.FingerprintStatus;
import eu.siacs.conversations.http.URL;
import eu.siacs.conversations.services.AvatarService;
import eu.siacs.conversations.ui.util.MyLinkify;
import eu.siacs.conversations.ui.util.PresenceSelector;
import eu.siacs.conversations.ui.util.QuoteHelper;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.Emoticons;
import eu.siacs.conversations.utils.GeoHelper;
import eu.siacs.conversations.utils.MessageUtils;
import eu.siacs.conversations.utils.MimeUtils;
import eu.siacs.conversations.utils.StringUtils;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xml.Tag;
import eu.siacs.conversations.xml.XmlReader;

public class Message extends AbstractEntity implements AvatarService.Avatarable {

    public static final String TABLENAME = "messages";

    public static final int STATUS_RECEIVED = 0;
    public static final int STATUS_UNSEND = 1;
    public static final int STATUS_SEND = 2;
    public static final int STATUS_SEND_FAILED = 3;
    public static final int STATUS_WAITING = 5;
    public static final int STATUS_OFFERED = 6;
    public static final int STATUS_SEND_RECEIVED = 7;
    public static final int STATUS_SEND_DISPLAYED = 8;

    public static final int ENCRYPTION_NONE = 0;
    public static final int ENCRYPTION_PGP = 1;
    public static final int ENCRYPTION_OTR = 2;
    public static final int ENCRYPTION_DECRYPTED = 3;
    public static final int ENCRYPTION_DECRYPTION_FAILED = 4;
    public static final int ENCRYPTION_AXOLOTL = 5;
    public static final int ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE = 6;
    public static final int ENCRYPTION_AXOLOTL_FAILED = 7;

    public static final int TYPE_TEXT = 0;
    public static final int TYPE_IMAGE = 1;
    public static final int TYPE_FILE = 2;
    public static final int TYPE_STATUS = 3;
    public static final int TYPE_PRIVATE = 4;
    public static final int TYPE_PRIVATE_FILE = 5;
    public static final int TYPE_RTP_SESSION = 6;

    public static final String CONVERSATION = "conversationUuid";
    public static final String COUNTERPART = "counterpart";
    public static final String TRUE_COUNTERPART = "trueCounterpart";
    public static final String BODY = "body";
    public static final String BODY_LANGUAGE = "bodyLanguage";
    public static final String TIME_SENT = "timeSent";
    public static final String ENCRYPTION = "encryption";
    public static final String STATUS = "status";
    public static final String TYPE = "type";
    public static final String CARBON = "carbon";
    public static final String OOB = "oob";
    public static final String EDITED = "edited";
    public static final String REMOTE_MSG_ID = "remoteMsgId";
    public static final String SERVER_MSG_ID = "serverMsgId";
    public static final String RELATIVE_FILE_PATH = "relativeFilePath";
    public static final String FINGERPRINT = "axolotl_fingerprint";
    public static final String READ = "read";
    public static final String ERROR_MESSAGE = "errorMsg";
    public static final String READ_BY_MARKERS = "readByMarkers";
    public static final String MARKABLE = "markable";
    public static final String DELETED = "deleted";
    public static final String ME_COMMAND = "/me ";

    public static final String ERROR_MESSAGE_CANCELLED = "eu.siacs.conversations.cancelled";


    public boolean markable = false;
    protected String conversationUuid;
    protected Jid counterpart;
    protected Jid trueCounterpart;
    protected String body;
    protected String subject;
    protected String encryptedBody;
    protected long timeSent;
    protected long timeReceived;
    protected int encryption;
    protected int status;
    protected int type;
    protected boolean deleted = false;
    protected boolean carbon = false;
    private boolean oob = false;
    protected List<Element> payloads = new ArrayList<>();
    protected List<Edit> edits = new ArrayList<>();
    protected String relativeFilePath;
    protected boolean read = true;
    protected String remoteMsgId = null;
    private String bodyLanguage = null;
    protected String serverMsgId = null;
    private final Conversational conversation;
    protected Transferable transferable = null;
    private Message mNextMessage = null;
    private Message mPreviousMessage = null;
    private String axolotlFingerprint = null;
    private String errorMessage = null;
    private Set<ReadByMarker> readByMarkers = new CopyOnWriteArraySet<>();

    private Boolean isGeoUri = null;
    private Boolean isEmojisOnly = null;
    private Boolean treatAsDownloadable = null;
    private FileParams fileParams = null;
    private List<MucOptions.User> counterparts;
    private WeakReference<MucOptions.User> user;

    protected Message(Conversational conversation) {
        this.conversation = conversation;
    }

    public Message(Conversational conversation, String body, int encryption) {
        this(conversation, body, encryption, STATUS_UNSEND);
    }

    public Message(Conversational conversation, String body, int encryption, int status) {
        this(conversation, java.util.UUID.randomUUID().toString(),
                conversation.getUuid(),
                conversation.getJid() == null ? null : conversation.getJid().asBareJid(),
                null,
                body,
                System.currentTimeMillis(),
                encryption,
                status,
                TYPE_TEXT,
                false,
                null,
                null,
                null,
                null,
                true,
                null,
                false,
                null,
                null,
                false,
                false,
                null,
                System.currentTimeMillis(),
                null,
                null,
                null);
    }

    public Message(Conversation conversation, int status, int type, final String remoteMsgId) {
        this(conversation, java.util.UUID.randomUUID().toString(),
                conversation.getUuid(),
                conversation.getJid() == null ? null : conversation.getJid().asBareJid(),
                null,
                null,
                System.currentTimeMillis(),
                Message.ENCRYPTION_NONE,
                status,
                type,
                false,
                remoteMsgId,
                null,
                null,
                null,
                true,
                null,
                false,
                null,
                null,
                false,
                false,
                null,
                System.currentTimeMillis(),
                null,
                null,
                null);
    }

    protected Message(final Conversational conversation, final String uuid, final String conversationUUid, final Jid counterpart,
                      final Jid trueCounterpart, final String body, final long timeSent,
                      final int encryption, final int status, final int type, final boolean carbon,
                      final String remoteMsgId, final String relativeFilePath,
                      final String serverMsgId, final String fingerprint, final boolean read,
                      final String edited, final boolean oob, final String errorMessage, final Set<ReadByMarker> readByMarkers,
                      final boolean markable, final boolean deleted, final String bodyLanguage, final long timeReceived, final String subject, final String fileParams, final List<Element> payloads) {
        this.conversation = conversation;
        this.uuid = uuid;
        this.conversationUuid = conversationUUid;
        this.counterpart = counterpart;
        this.trueCounterpart = trueCounterpart;
        this.body = body == null ? "" : body;
        this.timeSent = timeSent;
        this.encryption = encryption;
        this.status = status;
        this.type = type;
        this.carbon = carbon;
        this.remoteMsgId = remoteMsgId;
        this.relativeFilePath = relativeFilePath;
        this.serverMsgId = serverMsgId;
        this.axolotlFingerprint = fingerprint;
        this.read = read;
        this.edits = Edit.fromJson(edited);
        this.oob = oob;
        this.errorMessage = errorMessage;
        this.readByMarkers = readByMarkers == null ? new CopyOnWriteArraySet<>() : readByMarkers;
        this.markable = markable;
        this.deleted = deleted;
        this.bodyLanguage = bodyLanguage;
        this.timeReceived = timeReceived;
        this.subject = subject;
        if (payloads != null) this.payloads = payloads;
        if (fileParams != null && getSims().isEmpty()) this.fileParams = new FileParams(fileParams);
    }

    public static Message fromCursor(Cursor cursor, Conversation conversation) throws IOException {
        String payloadsStr = cursor.getString(cursor.getColumnIndex("payloads"));
        List<Element> payloads = new ArrayList<>();
        if (payloadsStr != null) {
            final XmlReader xmlReader = new XmlReader();
            xmlReader.setInputStream(ByteSource.wrap(payloadsStr.getBytes()).openStream());
            Tag tag;
            while ((tag = xmlReader.readTag()) != null) {
                payloads.add(xmlReader.readElement(tag));
            }
        }

        return new Message(conversation,
                cursor.getString(cursor.getColumnIndex(UUID)),
                cursor.getString(cursor.getColumnIndex(CONVERSATION)),
                fromString(cursor.getString(cursor.getColumnIndex(COUNTERPART))),
                fromString(cursor.getString(cursor.getColumnIndex(TRUE_COUNTERPART))),
                cursor.getString(cursor.getColumnIndex(BODY)),
                cursor.getLong(cursor.getColumnIndex(TIME_SENT)),
                cursor.getInt(cursor.getColumnIndex(ENCRYPTION)),
                cursor.getInt(cursor.getColumnIndex(STATUS)),
                cursor.getInt(cursor.getColumnIndex(TYPE)),
                cursor.getInt(cursor.getColumnIndex(CARBON)) > 0,
                cursor.getString(cursor.getColumnIndex(REMOTE_MSG_ID)),
                cursor.getString(cursor.getColumnIndex(RELATIVE_FILE_PATH)),
                cursor.getString(cursor.getColumnIndex(SERVER_MSG_ID)),
                cursor.getString(cursor.getColumnIndex(FINGERPRINT)),
                cursor.getInt(cursor.getColumnIndex(READ)) > 0,
                cursor.getString(cursor.getColumnIndex(EDITED)),
                cursor.getInt(cursor.getColumnIndex(OOB)) > 0,
                cursor.getString(cursor.getColumnIndex(ERROR_MESSAGE)),
                ReadByMarker.fromJsonString(cursor.getString(cursor.getColumnIndex(READ_BY_MARKERS))),
                cursor.getInt(cursor.getColumnIndex(MARKABLE)) > 0,
                cursor.getInt(cursor.getColumnIndex(DELETED)) > 0,
                cursor.getString(cursor.getColumnIndex(BODY_LANGUAGE)),
                cursor.getLong(cursor.getColumnIndex(cursor.isNull(cursor.getColumnIndex("timeReceived")) ? TIME_SENT : "timeReceived")),
                cursor.getString(cursor.getColumnIndex("subject")),
                cursor.getString(cursor.getColumnIndex("fileParams")),
                payloads
        );
    }

    private static Jid fromString(String value) {
        try {
            if (value != null) {
                return Jid.of(value);
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        return null;
    }

    public static Message createStatusMessage(Conversation conversation, String body) {
        final Message message = new Message(conversation);
        message.setType(Message.TYPE_STATUS);
        message.setStatus(Message.STATUS_RECEIVED);
        message.body = body;
        return message;
    }

    public static Message createLoadMoreMessage(Conversation conversation) {
        final Message message = new Message(conversation);
        message.setType(Message.TYPE_STATUS);
        message.body = "LOAD_MORE";
        return message;
    }

    public ContentValues getCheogramContentValues() {
        ContentValues values = new ContentValues();
        values.put(UUID, uuid);
        values.put("subject", subject);
        values.put("fileParams", fileParams == null ? null : fileParams.toString());
        if (fileParams != null && !fileParams.isEmpty()) {
            List<Element> sims = getSims();
            if (sims.isEmpty()) {
                addPayload(fileParams.toSims());
            } else {
                sims.get(0).replaceChildren(fileParams.toSims().getChildren());
            }
        }
        values.put("payloads", payloads.size() < 1 ? null : payloads.stream().map(Object::toString).collect(Collectors.joining()));
        return values;
    }

    @Override
    public ContentValues getContentValues() {
        ContentValues values = new ContentValues();
        values.put(UUID, uuid);
        values.put(CONVERSATION, conversationUuid);
        if (counterpart == null) {
            values.putNull(COUNTERPART);
        } else {
            values.put(COUNTERPART, counterpart.toString());
        }
        if (trueCounterpart == null) {
            values.putNull(TRUE_COUNTERPART);
        } else {
            values.put(TRUE_COUNTERPART, trueCounterpart.toString());
        }
        values.put(BODY, body.length() > Config.MAX_STORAGE_MESSAGE_CHARS ? body.substring(0, Config.MAX_STORAGE_MESSAGE_CHARS) : body);
        values.put(TIME_SENT, timeSent);
        values.put(ENCRYPTION, encryption);
        values.put(STATUS, status);
        values.put(TYPE, type);
        values.put(CARBON, carbon ? 1 : 0);
        values.put(REMOTE_MSG_ID, remoteMsgId);
        values.put(RELATIVE_FILE_PATH, relativeFilePath);
        values.put(SERVER_MSG_ID, serverMsgId);
        values.put(FINGERPRINT, axolotlFingerprint);
        values.put(READ, read ? 1 : 0);
        try {
            values.put(EDITED, Edit.toJson(edits));
        } catch (JSONException e) {
            Log.e(Config.LOGTAG, "error persisting json for edits", e);
        }
        values.put(OOB, oob ? 1 : 0);
        values.put(ERROR_MESSAGE, errorMessage);
        values.put(READ_BY_MARKERS, ReadByMarker.toJson(readByMarkers).toString());
        values.put(MARKABLE, markable ? 1 : 0);
        values.put(DELETED, deleted ? 1 : 0);
        values.put(BODY_LANGUAGE, bodyLanguage);
        return values;
    }

    public String replyId() {
        if (conversation.getMode() == Conversation.MODE_MULTI) return getServerMsgId();
        final String remote = getRemoteMsgId();
        if (remote == null && getStatus() > STATUS_RECEIVED) return getUuid();
        return remote;
    }

    public Message reply() {
        Message m = new Message(conversation, QuoteHelper.quote(MessageUtils.prepareQuote(this)) + "\n", ENCRYPTION_NONE);
        m.setThread(getThread());
        final String replyId = replyId();
        if (replyId == null) return m;

        m.addPayload(
            new Element("reply", "urn:xmpp:reply:0")
                .setAttribute("to", getCounterpart())
                .setAttribute("id", replyId())
        );
        final Element fallback = new Element("fallback", "urn:xmpp:fallback:0").setAttribute("for", "urn:xmpp:reply:0");
        fallback.addChild("body", "urn:xmpp:fallback:0")
                .setAttribute("start", "0")
                .setAttribute("end", "" + m.body.codePointCount(0, m.body.length()));
        m.addPayload(fallback);
        return m;
    }

    public Message react(String emoji) {
        Set<String> emojis = new HashSet<>();
        if (conversation instanceof Conversation) emojis = ((Conversation) conversation).findReactionsTo(replyId(), null);
        emojis.add(emoji);
        final Message m = reply();
        m.appendBody(emoji);
        final Element fallback = new Element("fallback", "urn:xmpp:fallback:0").setAttribute("for", "urn:xmpp:reactions:0");
        fallback.addChild("body", "urn:xmpp:fallback:0");
        m.addPayload(fallback);
        final Element reactions = new Element("reactions", "urn:xmpp:reactions:0").setAttribute("id", replyId());
        for (String oneEmoji : emojis) {
            reactions.addChild("reaction", "urn:xmpp:reactions:0").setContent(oneEmoji);
        }
        m.addPayload(reactions);
        return m;
    }

    public void setReactions(Element reactions) {
        if (this.payloads != null) {
            this.payloads.remove(getReactions());
        }
        addPayload(reactions);
    }

    public Element getReactions() {
        if (this.payloads == null) return null;

        for (Element el : this.payloads) {
            if (el.getName().equals("reactions") && el.getNamespace().equals("urn:xmpp:reactions:0")) {
                return el;
            }
        }

        return null;
    }

    public Element getReply() {
        if (this.payloads == null) return null;

        for (Element el : this.payloads) {
            if (el.getName().equals("reply") && el.getNamespace().equals("urn:xmpp:reply:0")) {
                return el;
            }
        }

        return null;
    }

    public boolean isAttention() {
        if (this.payloads == null) return false;

        for (Element el : this.payloads) {
            if (el.getName().equals("attention") && el.getNamespace().equals("urn:xmpp:attention:0")) {
                return true;
            }
        }

        return false;
    }

    public String getConversationUuid() {
        return conversationUuid;
    }

    public Conversational getConversation() {
        return this.conversation;
    }

    public Jid getCounterpart() {
        return counterpart;
    }

    public void setCounterpart(final Jid counterpart) {
        this.counterpart = counterpart;
    }

    public Contact getContact() {
        if (this.conversation.getMode() == Conversation.MODE_SINGLE) {
            if (this.trueCounterpart != null) {
                return this.conversation.getAccount().getRoster()
                           .getContact(this.trueCounterpart);
            }

            return this.conversation.getContact();
        } else {
            if (this.trueCounterpart == null) {
                return null;
            } else {
                return this.conversation.getAccount().getRoster()
                        .getContactFromContactList(this.trueCounterpart);
            }
        }
    }

    public String getQuoteableBody() {
        return this.body;
    }

    public String getBody() {
        StringBuilder body = new StringBuilder(this.body);

        List<Element> fallbacks = getFallbacks("http://jabber.org/protocol/address", Namespace.OOB);
        List<Pair<Integer, Integer>> spans = new ArrayList<>();
        for (Element fallback : fallbacks) {
            for (Element span : fallback.getChildren()) {
                if (!span.getName().equals("body") && !span.getNamespace().equals("urn:xmpp:fallback:0")) continue;
                if (span.getAttribute("start") == null || span.getAttribute("end") == null) return "";
                spans.add(new Pair(parseInt(span.getAttribute("start")), parseInt(span.getAttribute("end"))));
            }
        }
        // Do them in reverse order so that span deletions don't affect the indexes of other spans
        spans.sort((x, y) -> y.first.compareTo(x.first));
        try {
            for (Pair<Integer, Integer> span : spans) {
                body.delete(body.offsetByCodePoints(0, span.first.intValue()), body.offsetByCodePoints(0, span.second.intValue()));
            }
        } catch (final IndexOutOfBoundsException e) { spans.clear(); }

        if (spans.isEmpty() && getOob() != null) {
            return body.toString().replace(getOob().toString(), "");
        } else if (spans.isEmpty() && isGeoUri()) {
            return "";
        } else {
            return body.toString();
        }
    }

    public synchronized void clearFallbacks(String... includeFor) {
        this.payloads.removeAll(getFallbacks(includeFor));
    }

    public synchronized Element getOrMakeHtml() {
        Element html = getHtml();
        if (html != null) return html;
        html = new Element("html", "http://jabber.org/protocol/xhtml-im");
        Element body = html.addChild("body", "http://www.w3.org/1999/xhtml");
        SpannedToXHTML.append(body, new SpannableStringBuilder(getBody()));
        addPayload(html);
        return body;
    }

    public synchronized void setBody(Spanned span) {
        setBody(span.toString());
        final Element body = getOrMakeHtml();
        body.clearChildren();
        SpannedToXHTML.append(body, span);
        if (body.getContent().equals(span.toString())) {
            this.payloads.remove(getHtml(true));
        }
    }

    public synchronized void setHtml(Element html) {
        final Element oldHtml = getHtml(true);
        if (oldHtml != null) this.payloads.remove(oldHtml);
        if (html != null) addPayload(html);
    }

    public synchronized void setBody(String body) {
        this.body = body;
        this.isGeoUri = null;
        this.isEmojisOnly = null;
        this.treatAsDownloadable = null;
    }

    public synchronized void appendBody(Spanned append) {
        final Element body = getOrMakeHtml();
        SpannedToXHTML.append(body, append);
        if (body.getContent().equals(this.body + append.toString())) {
            this.payloads.remove(getHtml());
        }
        appendBody(append.toString());
    }

    public synchronized void appendBody(String append) {
        this.body += append;
        this.isGeoUri = null;
        this.isEmojisOnly = null;
        this.treatAsDownloadable = null;
    }

    public String getSubject() {
        return subject;
    }

    public synchronized void setSubject(String subject) {
        this.subject = subject;
    }

    public Element getThread() {
        if (this.payloads == null) return null;

        for (Element el : this.payloads) {
            if (el.getName().equals("thread") && el.getNamespace().equals("jabber:client")) {
                return el;
            }
        }

        return null;
    }

    public void setThread(Element thread) {
        payloads.removeIf(el -> el.getName().equals("thread") && el.getNamespace().equals("jabber:client"));
        addPayload(thread);
    }

    public void setMucUser(MucOptions.User user) {
        this.user = new WeakReference<>(user);
    }

    public boolean sameMucUser(Message otherMessage) {
        final MucOptions.User thisUser = this.user == null ? null : this.user.get();
        final MucOptions.User otherUser = otherMessage.user == null ? null : otherMessage.user.get();
        return thisUser != null && thisUser == otherUser;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean setErrorMessage(String message) {
        boolean changed = (message != null && !message.equals(errorMessage))
                || (message == null && errorMessage != null);
        this.errorMessage = message;
        return changed;
    }

    public long getTimeReceived() {
        return timeReceived;
    }

    public long getTimeSent() {
        return timeSent;
    }

    public int getEncryption() {
        return encryption;
    }

    public void setEncryption(int encryption) {
        this.encryption = encryption;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getRelativeFilePath() {
        return this.relativeFilePath;
    }

    public void setRelativeFilePath(String path) {
        this.relativeFilePath = path;
    }

    public String getRemoteMsgId() {
        return this.remoteMsgId;
    }

    public void setRemoteMsgId(String id) {
        this.remoteMsgId = id;
    }

    public String getServerMsgId() {
        return this.serverMsgId;
    }

    public void setServerMsgId(String id) {
        this.serverMsgId = id;
    }

    public boolean isRead() {
        return this.read;
    }

    public boolean isDeleted() {
        return this.deleted;
    }

    public Element getModerated() {
        if (this.payloads == null) return null;

        for (Element el : this.payloads) {
            if (el.getName().equals("moderated") && el.getNamespace().equals("urn:xmpp:message-moderate:0")) {
                return el;
            }
        }

        return null;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public void markRead() {
        this.read = true;
    }

    public void markUnread() {
        this.read = false;
    }

    public void setTime(long time) {
        this.timeSent = time;
    }

    public void setTimeReceived(long time) {
        this.timeReceived = time;
    }

    public String getEncryptedBody() {
        return this.encryptedBody;
    }

    public void setEncryptedBody(String body) {
        this.encryptedBody = body;
    }

    public int getType() {
        return this.type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public boolean isCarbon() {
        return carbon;
    }

    public void setCarbon(boolean carbon) {
        this.carbon = carbon;
    }

    public void putEdited(String edited, String serverMsgId) {
        final Edit edit = new Edit(edited, serverMsgId);
        if (this.edits.size() < 128 && !this.edits.contains(edit)) {
            this.edits.add(edit);
        }
    }

    boolean remoteMsgIdMatchInEdit(String id) {
        for (Edit edit : this.edits) {
            if (id.equals(edit.getEditedId())) {
                return true;
            }
        }
        return false;
    }

    public String getBodyLanguage() {
        return this.bodyLanguage;
    }

    public void setBodyLanguage(String language) {
        this.bodyLanguage = language;
    }

    public boolean edited() {
        return this.edits.size() > 0;
    }

    public void setTrueCounterpart(Jid trueCounterpart) {
        this.trueCounterpart = trueCounterpart;
    }

    public Jid getTrueCounterpart() {
        return this.trueCounterpart;
    }

    public Transferable getTransferable() {
        return this.transferable;
    }

    public synchronized void setTransferable(Transferable transferable) {
        this.transferable = transferable;
    }

    public boolean addReadByMarker(ReadByMarker readByMarker) {
        if (readByMarker.getRealJid() != null) {
            if (readByMarker.getRealJid().asBareJid().equals(trueCounterpart)) {
                return false;
            }
        } else if (readByMarker.getFullJid() != null) {
            if (readByMarker.getFullJid().equals(counterpart)) {
                return false;
            }
        }
        if (this.readByMarkers.add(readByMarker)) {
            if (readByMarker.getRealJid() != null && readByMarker.getFullJid() != null) {
                Iterator<ReadByMarker> iterator = this.readByMarkers.iterator();
                while (iterator.hasNext()) {
                    ReadByMarker marker = iterator.next();
                    if (marker.getRealJid() == null && readByMarker.getFullJid().equals(marker.getFullJid())) {
                        iterator.remove();
                    }
                }
            }
            return true;
        } else {
            return false;
        }
    }

    public Set<ReadByMarker> getReadByMarkers() {
        return ImmutableSet.copyOf(this.readByMarkers);
    }

    boolean similar(Message message) {
        if (!isPrivateMessage() && this.serverMsgId != null && message.getServerMsgId() != null) {
            return this.serverMsgId.equals(message.getServerMsgId()) || Edit.wasPreviouslyEditedServerMsgId(edits, message.getServerMsgId());
        } else if (Edit.wasPreviouslyEditedServerMsgId(edits, message.getServerMsgId())) {
            return true;
        } else if (this.body == null || this.counterpart == null) {
            return false;
        } else {
            String body, otherBody;
            if (this.hasFileOnRemoteHost() && (this.body == null || "".equals(this.body))) {
                body = getFileParams().url;
                otherBody = message.body == null ? null : message.body.trim();
            } else {
                body = this.body;
                otherBody = message.body;
            }
            final boolean matchingCounterpart = this.counterpart.equals(message.getCounterpart());
            if (message.getRemoteMsgId() != null) {
                final boolean hasUuid = CryptoHelper.UUID_PATTERN.matcher(message.getRemoteMsgId()).matches();
                if (hasUuid && matchingCounterpart && Edit.wasPreviouslyEditedRemoteMsgId(edits, message.getRemoteMsgId())) {
                    return true;
                }
                return (message.getRemoteMsgId().equals(this.remoteMsgId) || message.getRemoteMsgId().equals(this.uuid))
                        && matchingCounterpart
                        && (body.equals(otherBody) || (message.getEncryption() == Message.ENCRYPTION_PGP && hasUuid));
            } else {
                return this.remoteMsgId == null
                        && matchingCounterpart
                        && body.equals(otherBody)
                        && Math.abs(this.getTimeSent() - message.getTimeSent()) < Config.MESSAGE_MERGE_WINDOW * 1000;
            }
        }
    }

    public Message next() {
        if (this.conversation instanceof Conversation) {
            final Conversation conversation = (Conversation) this.conversation;
            synchronized (conversation.messages) {
                if (this.mNextMessage == null) {
                    int index = conversation.messages.indexOf(this);
                    if (index < 0 || index >= conversation.messages.size() - 1) {
                        this.mNextMessage = null;
                    } else {
                        this.mNextMessage = conversation.messages.get(index + 1);
                    }
                }
                return this.mNextMessage;
            }
        } else {
            throw new AssertionError("Calling next should be disabled for stubs");
        }
    }

    public Message prev() {
        if (this.conversation instanceof Conversation) {
            final Conversation conversation = (Conversation) this.conversation;
            synchronized (conversation.messages) {
                if (this.mPreviousMessage == null) {
                    int index = conversation.messages.indexOf(this);
                    if (index <= 0 || index > conversation.messages.size()) {
                        this.mPreviousMessage = null;
                    } else {
                        this.mPreviousMessage = conversation.messages.get(index - 1);
                    }
                }
            }
            return this.mPreviousMessage;
        } else {
            throw new AssertionError("Calling prev should be disabled for stubs");
        }
    }

    public boolean isLastCorrectableMessage() {
        Message next = next();
        while (next != null) {
            if (next.isEditable()) {
                return false;
            }
            next = next.next();
        }
        return isEditable();
    }

    public boolean isEditable() {
        return status != STATUS_RECEIVED && !isCarbon() && type != Message.TYPE_RTP_SESSION;
    }

    public boolean mergeable(final Message message) {
        return message != null &&
                (message.getType() == Message.TYPE_TEXT &&
                        this.getTransferable() == null &&
                        message.getTransferable() == null &&
                        message.getEncryption() != Message.ENCRYPTION_PGP &&
                        message.getEncryption() != Message.ENCRYPTION_DECRYPTION_FAILED &&
                        this.getType() == message.getType() &&
                        this.getSubject() != null &&
                        isStatusMergeable(this.getStatus(), message.getStatus()) &&
                        isEncryptionMergeable(this.getEncryption(),message.getEncryption()) &&
                        this.getCounterpart() != null &&
                        this.getCounterpart().equals(message.getCounterpart()) &&
                        this.edited() == message.edited() &&
                        (message.getTimeSent() - this.getTimeSent()) <= (Config.MESSAGE_MERGE_WINDOW * 1000) &&
                        this.getBody().length() + message.getBody().length() <= Config.MAX_DISPLAY_MESSAGE_CHARS &&
                        !message.isGeoUri() &&
                        !this.isGeoUri() &&
                        !message.isOOb() &&
                        !this.isOOb() &&
                        !message.treatAsDownloadable() &&
                        !this.treatAsDownloadable() &&
                        !message.hasMeCommand() &&
                        !this.hasMeCommand() &&
                        !this.bodyIsOnlyEmojis() &&
                        !message.bodyIsOnlyEmojis() &&
                        ((this.axolotlFingerprint == null && message.axolotlFingerprint == null) || this.axolotlFingerprint.equals(message.getFingerprint())) &&
                        UIHelper.sameDay(message.getTimeSent(), this.getTimeSent()) &&
                        this.getReadByMarkers().equals(message.getReadByMarkers()) &&
                        !this.conversation.getJid().asBareJid().equals(Config.BUG_REPORTS)
                );
    }

    private static boolean isStatusMergeable(int a, int b) {
        return a == b || (
                (a == Message.STATUS_SEND_RECEIVED && b == Message.STATUS_UNSEND)
                        || (a == Message.STATUS_SEND_RECEIVED && b == Message.STATUS_SEND)
                        || (a == Message.STATUS_SEND_RECEIVED && b == Message.STATUS_WAITING)
                        || (a == Message.STATUS_SEND && b == Message.STATUS_UNSEND)
                        || (a == Message.STATUS_SEND && b == Message.STATUS_WAITING)
        );
    }

    private static boolean isEncryptionMergeable(final int a, final int b) {
        return a == b
                && Arrays.asList(ENCRYPTION_NONE, ENCRYPTION_DECRYPTED, ENCRYPTION_AXOLOTL)
                        .contains(a);
    }

    public void setCounterparts(List<MucOptions.User> counterparts) {
        this.counterparts = counterparts;
    }

    public List<MucOptions.User> getCounterparts() {
        return this.counterparts;
    }

    @Override
    public int getAvatarBackgroundColor() {
        if (type == Message.TYPE_STATUS && getCounterparts() != null && getCounterparts().size() > 1) {
            return Color.TRANSPARENT;
        } else {
            return UIHelper.getColorForName(UIHelper.getMessageDisplayName(this));
        }
    }

    @Override
    public String getAvatarName() {
        return UIHelper.getMessageDisplayName(this);
    }

    public boolean isOOb() {
        return oob || getFileParams().url != null;
    }

    public static class MergeSeparator {
    }

    public SpannableStringBuilder getSpannableBody(GetThumbnailForCid thumbnailer, Drawable fallbackImg) {
        final Element html = getHtml();
        if (html == null || Build.VERSION.SDK_INT < 24) {
            return new SpannableStringBuilder(MessageUtils.filterLtrRtl(getBody()).trim());
        } else {
            SpannableStringBuilder spannable = new SpannableStringBuilder(Html.fromHtml(
                MessageUtils.filterLtrRtl(html.toString()).trim(),
                Html.FROM_HTML_MODE_COMPACT,
                (source) -> {
                   try {
                       if (thumbnailer == null || source == null) return fallbackImg;
                       Cid cid = BobTransfer.cid(new URI(source));
                       if (cid == null) return fallbackImg;
                       Drawable thumbnail = thumbnailer.getThumbnail(cid);
                       if (thumbnail == null) return fallbackImg;
                       return thumbnail;
                   } catch (final URISyntaxException e) {
                       return fallbackImg;
                   }
                },
                (opening, tag, output, xmlReader) -> {}
            ));

            // Make images clickable and long-clickable with BetterLinkMovementMethod
            ImageSpan[] imageSpans = spannable.getSpans(0, spannable.length(), ImageSpan.class);
            for (ImageSpan span : imageSpans) {
                final int start = spannable.getSpanStart(span);
                final int end = spannable.getSpanEnd(span);

                ClickableSpan click_span = new ClickableSpan() {
                    @Override
                    public void onClick(View widget) { }
                };

                spannable.removeSpan(span);
                spannable.setSpan(new InlineImageSpan(span.getDrawable(), span.getSource()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                spannable.setSpan(click_span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            // https://stackoverflow.com/a/10187511/8611
            int i = spannable.length();
            while(--i >= 0 && Character.isWhitespace(spannable.charAt(i))) { }
            return (SpannableStringBuilder) spannable.subSequence(0, i+1);
        }
    }

    public SpannableStringBuilder getMergedBody() {
        return getMergedBody(null, null);
    }

    public SpannableStringBuilder getMergedBody(GetThumbnailForCid thumbnailer, Drawable fallbackImg) {
        SpannableStringBuilder body = getSpannableBody(thumbnailer, fallbackImg);
        Message current = this;
        while (current.mergeable(current.next())) {
            current = current.next();
            if (current == null) {
                break;
            }
            body.append("\n\n");
            body.setSpan(new MergeSeparator(), body.length() - 2, body.length(),
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            body.append(current.getSpannableBody(thumbnailer, fallbackImg));
        }
        return body;
    }

    public boolean hasMeCommand() {
        return this.body.trim().startsWith(ME_COMMAND);
    }

    public int getMergedStatus() {
        int status = this.status;
        Message current = this;
        while (current.mergeable(current.next())) {
            current = current.next();
            if (current == null) {
                break;
            }
            status = current.status;
        }
        return status;
    }

    public long getMergedTimeSent() {
        long time = this.timeSent;
        Message current = this;
        while (current.mergeable(current.next())) {
            current = current.next();
            if (current == null) {
                break;
            }
            time = current.timeSent;
        }
        return time;
    }

    public boolean wasMergedIntoPrevious() {
        Message prev = this.prev();
        return prev != null && prev.mergeable(this);
    }

    public boolean trusted() {
        Contact contact = this.getContact();
        return status > STATUS_RECEIVED || (contact != null && (contact.showInContactList() || contact.isSelf()));
    }

    public boolean fixCounterpart() {
        final Presences presences = conversation.getContact().getPresences();
        if (counterpart != null && presences.has(Strings.nullToEmpty(counterpart.getResource()))) {
            return true;
        } else if (presences.size() >= 1) {
            counterpart = PresenceSelector.getNextCounterpart(getContact(), presences.toResourceArray()[0]);
            return true;
        } else {
            counterpart = null;
            return false;
        }
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getEditedId() {
        if (edits.size() > 0) {
            return edits.get(edits.size() - 1).getEditedId();
        } else {
            throw new IllegalStateException("Attempting to store unedited message");
        }
    }

    public String getEditedIdWireFormat() {
        if (edits.size() > 0) {
            return edits.get(Config.USE_LMC_VERSION_1_1 ? 0 : edits.size() - 1).getEditedId();
        } else {
            throw new IllegalStateException("Attempting to store unedited message");
        }
    }

    public List<URI> getLinks() {
        SpannableStringBuilder text = new SpannableStringBuilder(
            getBody().replaceAll("^>.*", "") // Remove quotes
        );
        return MyLinkify.extractLinks(text).stream().map((url) -> {
            try {
                return new URI(url);
            } catch (final URISyntaxException e) {
                return null;
            }
        }).filter(x -> x != null).collect(Collectors.toList());
    }

    public URI getOob() {
        final String url = getFileParams().url;
        try {
            return url == null ? null : new URI(url);
        } catch (final URISyntaxException e) {
            return null;
        }
    }

    public void clearPayloads() {
        this.payloads.clear();
    }

    public void addPayload(Element el) {
        if (el == null) return;

        this.payloads.add(el);
    }

    public List<Element> getPayloads() {
       return new ArrayList<>(this.payloads);
    }

    public List<Element> getFallbacks(String... includeFor) {
        List<Element> fallbacks = new ArrayList<>();

        if (this.payloads == null) return fallbacks;

        for (Element el : this.payloads) {
            if (el.getName().equals("fallback") && el.getNamespace().equals("urn:xmpp:fallback:0")) {
                final String fallbackFor = el.getAttribute("for");
                if (fallbackFor == null) continue;
                for (String includeOne : includeFor) {
                    if (fallbackFor.equals(includeOne)) {
                        fallbacks.add(el);
                        break;
                    }
                }
            }
        }

        return fallbacks;
    }

    public Element getHtml() {
        return getHtml(false);
    }

    public Element getHtml(boolean root) {
        if (this.payloads == null) return null;

        for (Element el : this.payloads) {
            if (el.getName().equals("html") && el.getNamespace().equals("http://jabber.org/protocol/xhtml-im")) {
                return root ? el : el.getChildren().get(0);
            }
        }

        return null;
   }

    public List<Element> getCommands() {
        if (this.payloads == null) return null;

        for (Element el : this.payloads) {
            if (el.getName().equals("query") && el.getNamespace().equals("http://jabber.org/protocol/disco#items") && el.getAttribute("node").equals("http://jabber.org/protocol/commands")) {
                return el.getChildren();
            }
        }

        return null;
    }

    public String getMimeType() {
        String extension;
        if (relativeFilePath != null) {
            extension = MimeUtils.extractRelevantExtension(relativeFilePath);
        } else {
            final String url = URL.tryParse(getOob() == null ? body.split("\n")[0] : getOob().toString());
            if (url == null) {
                return null;
            }
            extension = MimeUtils.extractRelevantExtension(url);
        }
        return MimeUtils.guessMimeTypeFromExtension(extension);
    }

    public synchronized boolean treatAsDownloadable() {
        if (treatAsDownloadable == null) {
            treatAsDownloadable = MessageUtils.treatAsDownloadable(this.body, isOOb());
        }
        return treatAsDownloadable;
    }

    public synchronized boolean hasCustomEmoji() {
        if (getHtml() != null) {
            SpannableStringBuilder spannable = getSpannableBody(null, null);
            ImageSpan[] imageSpans = spannable.getSpans(0, spannable.length(), ImageSpan.class);
            return imageSpans.length > 0;
        }

        return false;
    }

    public synchronized boolean bodyIsOnlyEmojis() {
        if (isEmojisOnly == null) {
            isEmojisOnly = Emoticons.isOnlyEmoji(getBody().replaceAll("\\s", ""));
            if (isEmojisOnly) return true;

            if (getHtml() != null) {
                SpannableStringBuilder spannable = getSpannableBody(null, null);
                ImageSpan[] imageSpans = spannable.getSpans(0, spannable.length(), ImageSpan.class);
                for (ImageSpan span : imageSpans) {
                    final int start = spannable.getSpanStart(span);
                    final int end = spannable.getSpanEnd(span);
                    spannable.delete(start, end);
                }
                final String after = spannable.toString().replaceAll("\\s", "");
                isEmojisOnly = after.length() == 0 || Emoticons.isOnlyEmoji(after);
            }
        }
        return isEmojisOnly;
    }

    public synchronized boolean isGeoUri() {
        if (isGeoUri == null) {
            isGeoUri = GeoHelper.GEO_URI.matcher(body).matches();
        }
        return isGeoUri;
    }

    protected List<Element> getSims() {
        return payloads.stream().filter(el ->
            el.getName().equals("reference") && el.getNamespace().equals("urn:xmpp:reference:0") &&
            el.findChild("media-sharing", "urn:xmpp:sims:1") != null
        ).collect(Collectors.toList());
    }

    public synchronized void resetFileParams() {
        this.fileParams = null;
    }

    public synchronized void setFileParams(FileParams fileParams) {
        if (fileParams != null && this.fileParams != null && this.fileParams.sims != null && fileParams.sims == null) {
            fileParams.sims = this.fileParams.sims;
        }
        this.fileParams = fileParams;
        if (fileParams != null && getSims().isEmpty()) {
            addPayload(fileParams.toSims());
        }
    }

    public synchronized FileParams getFileParams() {
        if (fileParams == null) {
            List<Element> sims = getSims();
            fileParams = sims.isEmpty() ? new FileParams(oob ? this.body : "") : new FileParams(sims.get(0));
            if (this.transferable != null) {
                fileParams.size = this.transferable.getFileSize();
            }
        }

        return fileParams;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void untie() {
        this.mNextMessage = null;
        this.mPreviousMessage = null;
    }

    public boolean isPrivateMessage() {
        return type == TYPE_PRIVATE || type == TYPE_PRIVATE_FILE;
    }

    public boolean isFileOrImage() {
        return type == TYPE_FILE || type == TYPE_IMAGE || type == TYPE_PRIVATE_FILE;
    }


    public boolean isTypeText() {
        return type == TYPE_TEXT || type == TYPE_PRIVATE;
    }

    public boolean hasFileOnRemoteHost() {
        return isFileOrImage() && getFileParams().url != null;
    }

    public boolean needsUploading() {
        return isFileOrImage() && getFileParams().url == null;
    }

    public static class FileParams {
        public String url;
        public Long size = null;
        public int width = 0;
        public int height = 0;
        public int runtime = 0;
        public Element sims = null;

        public FileParams() { }

        public FileParams(Element el) {
            if (el.getName().equals("x") && el.getNamespace().equals(Namespace.OOB)) {
                this.url = el.findChildContent("url", Namespace.OOB);
            }
            if (el.getName().equals("reference") && el.getNamespace().equals("urn:xmpp:reference:0")) {
                sims = el;
                final String refUri = el.getAttribute("uri");
                if (refUri != null) url = refUri;
                final Element mediaSharing = el.findChild("media-sharing", "urn:xmpp:sims:1");
                if (mediaSharing != null) {
                    Element file = mediaSharing.findChild("file", "urn:xmpp:jingle:apps:file-transfer:5");
                    if (file == null) file = mediaSharing.findChild("file", "urn:xmpp:jingle:apps:file-transfer:4");
                    if (file == null) file = mediaSharing.findChild("file", "urn:xmpp:jingle:apps:file-transfer:3");
                    if (file != null) {
                        try {
                            String sizeS = file.findChildContent("size", file.getNamespace());
                            if (sizeS != null) size = new Long(sizeS);
                            String widthS = file.findChildContent("width", "https://schema.org/");
                            if (widthS != null) width = parseInt(widthS);
                            String heightS = file.findChildContent("height", "https://schema.org/");
                            if (heightS != null) height = parseInt(heightS);
                            String durationS = file.findChildContent("duration", "https://schema.org/");
                            if (durationS != null) runtime = (int)(Duration.parse(durationS).toMillis() / 1000L);
                        } catch (final NumberFormatException e) {
                            Log.w(Config.LOGTAG, "Trouble parsing as number: " + e);
                        }
                    }

                    final Element sources = mediaSharing.findChild("sources", "urn:xmpp:sims:1");
                    if (sources != null) {
                        final Element ref = sources.findChild("reference", "urn:xmpp:reference:0");
                        if (ref != null) url = ref.getAttribute("uri");
                    }
                }
            }
        }

        public FileParams(String ser) {
            final String[] parts = ser == null ? new String[0] : ser.split("\\|");
            switch (parts.length) {
                case 1:
                    try {
                        this.size = Long.parseLong(parts[0]);
                    } catch (final NumberFormatException e) {
                        this.url = URL.tryParse(parts[0]);
                    }
                    break;
                case 5:
                    this.runtime = parseInt(parts[4]);
                case 4:
                    this.width = parseInt(parts[2]);
                    this.height = parseInt(parts[3]);
                case 2:
                    this.url = URL.tryParse(parts[0]);
                    this.size = Longs.tryParse(parts[1]);
                    break;
                case 3:
                    this.size = Longs.tryParse(parts[0]);
                    this.width = parseInt(parts[1]);
                    this.height = parseInt(parts[2]);
                    break;
            }
        }

        public boolean isEmpty() {
            return StringUtils.nullOnEmpty(toString()) == null && StringUtils.nullOnEmpty(toSims().getContent()) == null;
        }

        public long getSize() {
            return size == null ? 0 : size;
        }

        public String getName() {
            Element file = getFileElement();
            if (file == null) return null;

            return file.findChildContent("name", file.getNamespace());
        }

        public void setName(final String name) {
            if (sims == null) toSims();
            Element file = getFileElement();

            for (Element child : file.getChildren()) {
                if (child.getName().equals("name") && child.getNamespace().equals(file.getNamespace())) {
                    file.removeChild(child);
                }
            }

            if (name != null) {
                file.addChild("name", file.getNamespace()).setContent(name);
            }
        }

        public String getMediaType() {
            Element file = getFileElement();
            if (file == null) return null;

            return file.findChildContent("media-type", file.getNamespace());
        }

        public void setMediaType(final String mime) {
            if (sims == null) toSims();
            Element file = getFileElement();

            for (Element child : file.getChildren()) {
                if (child.getName().equals("media-type") && child.getNamespace().equals(file.getNamespace())) {
                    file.removeChild(child);
                }
            }

            if (mime != null) {
                file.addChild("media-type", file.getNamespace()).setContent(mime);
            }
        }

        public Element toSims() {
            if (sims == null) sims = new Element("reference", "urn:xmpp:reference:0");
            sims.setAttribute("type", "data");
            Element mediaSharing = sims.findChild("media-sharing", "urn:xmpp:sims:1");
            if (mediaSharing == null) mediaSharing = sims.addChild("media-sharing", "urn:xmpp:sims:1");

            Element file = mediaSharing.findChild("file", "urn:xmpp:jingle:apps:file-transfer:5");
            if (file == null) file = mediaSharing.findChild("file", "urn:xmpp:jingle:apps:file-transfer:4");
            if (file == null) file = mediaSharing.findChild("file", "urn:xmpp:jingle:apps:file-transfer:3");
            if (file == null) file = mediaSharing.addChild("file", "urn:xmpp:jingle:apps:file-transfer:5");

            file.removeChild(file.findChild("size", file.getNamespace()));
            if (size != null) file.addChild("size", file.getNamespace()).setContent(size.toString());

            file.removeChild(file.findChild("width", "https://schema.org/"));
            if (width > 0) file.addChild("width", "https://schema.org/").setContent(String.valueOf(width));

            file.removeChild(file.findChild("height", "https://schema.org/"));
            if (height > 0) file.addChild("height", "https://schema.org/").setContent(String.valueOf(height));

            file.removeChild(file.findChild("duration", "https://schema.org/"));
            if (runtime > 0) file.addChild("duration", "https://schema.org/").setContent("PT" + runtime + "S");

            if (url != null) {
                Element sources = mediaSharing.findChild("sources", mediaSharing.getNamespace());
                if (sources == null) sources = mediaSharing.addChild("sources", mediaSharing.getNamespace());

                Element source = sources.findChild("reference", "urn:xmpp:reference:0");
                if (source == null) source = sources.addChild("reference", "urn:xmpp:reference:0");
                source.setAttribute("type", "data");
                source.setAttribute("uri", url);
            }

            return sims;
        }

        protected Element getFileElement() {
            Element file = null;
            if (sims == null) return file;

            Element mediaSharing = sims.findChild("media-sharing", "urn:xmpp:sims:1");
            if (mediaSharing == null) return file;
            file = mediaSharing.findChild("file", "urn:xmpp:jingle:apps:file-transfer:5");
            if (file == null) file = mediaSharing.findChild("file", "urn:xmpp:jingle:apps:file-transfer:4");
            if (file == null) file = mediaSharing.findChild("file", "urn:xmpp:jingle:apps:file-transfer:3");
            return file;
        }

        public void setCids(Iterable<Cid> cids) throws NoSuchAlgorithmException {
            if (sims == null) toSims();
            Element file = getFileElement();

            for (Element child : file.getChildren()) {
                if (child.getName().equals("hash") && child.getNamespace().equals("urn:xmpp:hashes:2")) {
                    file.removeChild(child);
                }
            }

            for (Cid cid : cids) {
                file.addChild("hash", "urn:xmpp:hashes:2")
                    .setAttribute("algo", CryptoHelper.multihashAlgo(cid.getType()))
                    .setContent(Base64.encodeToString(cid.getHash(), Base64.NO_WRAP));
            }
        }

        public List<Cid> getCids() {
            List<Cid> cids = new ArrayList<>();
            Element file = getFileElement();
            if (file == null) return cids;

            for (Element child : file.getChildren()) {
                if (child.getName().equals("hash") && child.getNamespace().equals("urn:xmpp:hashes:2")) {
                    try {
                        cids.add(CryptoHelper.cid(Base64.decode(child.getContent(), Base64.DEFAULT), child.getAttribute("algo")));
                    } catch (final NoSuchAlgorithmException | IllegalStateException e) { }
                }
            }

            cids.sort((x, y) -> y.getType().compareTo(x.getType()));

            return cids;
        }

        public void addThumbnail(int width, int height, String mimeType, String uri) {
            for (Element thumb : getThumbnails()) {
                if (uri.equals(thumb.getAttribute("uri"))) return;
            }

            if (sims == null) toSims();
            Element file = getFileElement();
            file.addChild(
                new Element("thumbnail", "urn:xmpp:thumbs:1")
                    .setAttribute("width", Integer.toString(width))
                    .setAttribute("height", Integer.toString(height))
                    .setAttribute("type", mimeType)
                    .setAttribute("uri", uri)
            );
        }

        public List<Element> getThumbnails() {
            List<Element> thumbs = new ArrayList<>();
            Element file = getFileElement();
            if (file == null) return thumbs;

            for (Element child : file.getChildren()) {
                if (child.getName().equals("thumbnail") && child.getNamespace().equals("urn:xmpp:thumbs:1")) {
                    thumbs.add(child);
                }
            }

            return thumbs;
        }

        public String toString() {
            final StringBuilder builder = new StringBuilder();
            if (url != null) builder.append(url);
            if (size != null) builder.append('|').append(size.toString());
            if (width > 0 || height > 0 || runtime > 0) builder.append('|').append(width);
            if (height > 0 || runtime > 0) builder.append('|').append(height);
            if (runtime > 0) builder.append('|').append(runtime);
            return builder.toString();
        }

        public boolean equals(Object o) {
            if (!(o instanceof FileParams)) return false;
            if (url == null) return false;

            return url.equals(((FileParams) o).url);
        }

        public int hashCode() {
            return url == null ? super.hashCode() : url.hashCode();
        }
    }

    public void setFingerprint(String fingerprint) {
        this.axolotlFingerprint = fingerprint;
    }

    public String getFingerprint() {
        return axolotlFingerprint;
    }

    public boolean isTrusted() {
        final AxolotlService axolotlService = conversation.getAccount().getAxolotlService();
        final FingerprintStatus s = axolotlService != null ? axolotlService.getFingerprintTrust(axolotlFingerprint) : null;
        return s != null && s.isTrusted();
    }

    private int getPreviousEncryption() {
        for (Message iterator = this.prev(); iterator != null; iterator = iterator.prev()) {
            if (iterator.isCarbon() || iterator.getStatus() == STATUS_RECEIVED) {
                continue;
            }
            return iterator.getEncryption();
        }
        return ENCRYPTION_NONE;
    }

    private int getNextEncryption() {
        if (this.conversation instanceof Conversation) {
            Conversation conversation = (Conversation) this.conversation;
            for (Message iterator = this.next(); iterator != null; iterator = iterator.next()) {
                if (iterator.isCarbon() || iterator.getStatus() == STATUS_RECEIVED) {
                    continue;
                }
                return iterator.getEncryption();
            }
            return conversation.getNextEncryption();
        } else {
            throw new AssertionError("This should never be called since isInValidSession should be disabled for stubs");
        }
    }

    public boolean isValidInSession() {
        int pastEncryption = getCleanedEncryption(this.getPreviousEncryption());
        int futureEncryption = getCleanedEncryption(this.getNextEncryption());

        boolean inUnencryptedSession = pastEncryption == ENCRYPTION_NONE
                || futureEncryption == ENCRYPTION_NONE
                || pastEncryption != futureEncryption;

        return inUnencryptedSession || getCleanedEncryption(this.getEncryption()) == pastEncryption;
    }

    private static int getCleanedEncryption(int encryption) {
        if (encryption == ENCRYPTION_DECRYPTED || encryption == ENCRYPTION_DECRYPTION_FAILED) {
            return ENCRYPTION_PGP;
        }
        if (encryption == ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE || encryption == ENCRYPTION_AXOLOTL_FAILED) {
            return ENCRYPTION_AXOLOTL;
        }
        return encryption;
    }

    public static boolean configurePrivateMessage(final Message message) {
        return configurePrivateMessage(message, false);
    }

    public static boolean configurePrivateFileMessage(final Message message) {
        return configurePrivateMessage(message, true);
    }

    private static boolean configurePrivateMessage(final Message message, final boolean isFile) {
        final Conversation conversation;
        if (message.conversation instanceof Conversation) {
            conversation = (Conversation) message.conversation;
        } else {
            return false;
        }
        if (conversation.getMode() == Conversation.MODE_MULTI) {
            final Jid nextCounterpart = conversation.getNextCounterpart();
            return configurePrivateMessage(conversation, message, nextCounterpart, isFile);
        }
        return false;
    }

    public static boolean configurePrivateMessage(final Message message, final Jid counterpart) {
        final Conversation conversation;
        if (message.conversation instanceof Conversation) {
            conversation = (Conversation) message.conversation;
        } else {
            return false;
        }
        return configurePrivateMessage(conversation, message, counterpart, false);
    }

    private static boolean configurePrivateMessage(final Conversation conversation, final Message message, final Jid counterpart, final boolean isFile) {
        if (counterpart == null) {
            return false;
        }
        message.setCounterpart(counterpart);
        message.setTrueCounterpart(conversation.getMucOptions().getTrueCounterpart(counterpart));
        message.setType(isFile ? Message.TYPE_PRIVATE_FILE : Message.TYPE_PRIVATE);
        return true;
    }
}
