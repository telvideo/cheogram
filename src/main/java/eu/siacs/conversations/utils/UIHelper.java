package eu.siacs.conversations.utils;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.content.res.ColorStateList;
import android.text.SpannableStringBuilder;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.Pair;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.core.content.res.ResourcesCompat;
import androidx.annotation.ColorRes;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;

import com.google.common.collect.Lists;
import com.google.android.material.color.MaterialColors;
import com.google.common.base.Strings;
import com.google.common.primitives.Ints;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.entities.Presence;
import eu.siacs.conversations.entities.RtpSessionStatus;
import eu.siacs.conversations.entities.Transferable;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.util.MyLinkify;
import eu.siacs.conversations.ui.util.QuoteHelper;
import eu.siacs.conversations.worker.ExportBackupWorker;
import eu.siacs.conversations.xmpp.Jid;

public class UIHelper {

    private static final List<String> LOCATION_QUESTIONS = Arrays.asList(
            "where are you", //en
            "where are you now", //en
            "where are you right now", //en
            "whats your 20", //en
            "what is your 20", //en
            "what's your 20", //en
            "whats your twenty", //en
            "what is your twenty", //en
            "what's your twenty", //en
            "wo bist du", //de
            "wo bist du jetzt", //de
            "wo bist du gerade", //de
            "wo seid ihr", //de
            "wo seid ihr jetzt", //de
            "wo seid ihr gerade", //de
            "dónde estás", //es
            "donde estas" //es
    );

    private static final List<Character> PUNCTIONATION = Arrays.asList('.', ',', '?', '!', ';', ':');

    private static final int SHORT_DATE_FLAGS = DateUtils.FORMAT_SHOW_DATE
            | DateUtils.FORMAT_NO_YEAR | DateUtils.FORMAT_ABBREV_ALL;
    private static final int FULL_DATE_FLAGS = DateUtils.FORMAT_SHOW_TIME
            | DateUtils.FORMAT_ABBREV_ALL | DateUtils.FORMAT_SHOW_DATE;

    public static String readableTimeDifference(Context context, long time) {
        return readableTimeDifference(context, time, false);
    }

    public static String readableTimeDifferenceFull(Context context, long time) {
        return readableTimeDifference(context, time, true);
    }

    private static String readableTimeDifference(Context context, long time,
                                                 boolean fullDate) {
        if (time == 0) {
            return context.getString(R.string.just_now);
        }
        Date date = new Date(time);
        long difference = (System.currentTimeMillis() - time) / 1000;
        if (difference < 60) {
            return context.getString(R.string.just_now);
        } else if (difference < 60 * 2) {
            return context.getString(R.string.minute_ago);
        } else if (difference < 60 * 15) {
            return context.getString(R.string.minutes_ago, Math.round(difference / 60.0));
        } else if (today(date)) {
            java.text.DateFormat df = DateFormat.getTimeFormat(context);
            return df.format(date);
        } else {
            if (fullDate) {
                return DateUtils.formatDateTime(context, date.getTime(),
                        FULL_DATE_FLAGS);
            } else {
                return DateUtils.formatDateTime(context, date.getTime(),
                        SHORT_DATE_FLAGS);
            }
        }
    }

    private static boolean today(Date date) {
        return sameDay(date, new Date(System.currentTimeMillis()));
    }

    public static boolean today(long date) {
        return sameDay(date, System.currentTimeMillis());
    }

    public static boolean yesterday(long date) {
        Calendar cal1 = Calendar.getInstance();
        Calendar cal2 = Calendar.getInstance();
        cal1.add(Calendar.DAY_OF_YEAR, -1);
        cal2.setTime(new Date(date));
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
                && cal1.get(Calendar.DAY_OF_YEAR) == cal2
                .get(Calendar.DAY_OF_YEAR);
    }

    public static boolean sameDay(long a, long b) {
        return sameDay(new Date(a), new Date(b));
    }

    private static boolean sameDay(Date a, Date b) {
        Calendar cal1 = Calendar.getInstance();
        Calendar cal2 = Calendar.getInstance();
        cal1.setTime(a);
        cal2.setTime(b);
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
                && cal1.get(Calendar.DAY_OF_YEAR) == cal2
                .get(Calendar.DAY_OF_YEAR);
    }

    public static String lastseen(Context context, boolean active, long time) {
        long difference = (System.currentTimeMillis() - time) / 1000;
        if (active) {
            return context.getString(R.string.online_right_now);
        } else if (difference < 60) {
            return context.getString(R.string.last_seen_now);
        } else if (difference < 60 * 2) {
            return context.getString(R.string.last_seen_min);
        } else if (difference < 60 * 60) {
            return context.getString(R.string.last_seen_mins, Math.round(difference / 60.0));
        } else if (difference < 60 * 60 * 2) {
            return context.getString(R.string.last_seen_hour);
        } else if (difference < 60 * 60 * 24) {
            return context.getString(R.string.last_seen_hours,
                    Math.round(difference / (60.0 * 60.0)));
        } else if (difference < 60 * 60 * 48) {
            return context.getString(R.string.last_seen_day);
        } else {
            return context.getString(R.string.last_seen_days,
                    Math.round(difference / (60.0 * 60.0 * 24.0)));
        }
    }

    public static int identiconHash(String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
            return Ints.fromByteArray(digest);
        } catch (Exception e) {
            return 0;
        }
    }

    public static int getColorForName(String name) {
        return XEP0392Helper.rgbFromNick(name);
    }

    public static Pair<CharSequence, Boolean> getMessagePreview(final XmppConnectionService context, final Message message) {
        return getMessagePreview(context, message, 0);
    }

    public static Pair<CharSequence, Boolean> getMessagePreview(final XmppConnectionService context, final Message message, @ColorInt int textColor) {
        final Transferable d = message.getTransferable();
        final boolean moderated = message.getModerated() != null;
        final boolean muted = message.getStatus() == Message.STATUS_RECEIVED && message.getConversation().getMode() == Conversation.MODE_MULTI && context.isMucUserMuted(new MucOptions.User(null, message.getConversation().getJid(), message.getOccupantId(), null, null));
        if (muted) {
            return new Pair<>("Muted", false);
        } else if (d != null && !moderated) {
            switch (d.getStatus()) {
                case Transferable.STATUS_CHECKING:
                    return new Pair<>(context.getString(R.string.checking_x,
                            getFileDescriptionString(context, message)), true);
                case Transferable.STATUS_DOWNLOADING:
                    return new Pair<>(context.getString(R.string.receiving_x_file,
                            getFileDescriptionString(context, message),
                            d.getProgress()), true);
                case Transferable.STATUS_OFFER:
                case Transferable.STATUS_OFFER_CHECK_FILESIZE:
                    return new Pair<>(context.getString(R.string.x_file_offered_for_download,
                            getFileDescriptionString(context, message)), true);
                case Transferable.STATUS_FAILED:
                    return new Pair<>(context.getString(R.string.file_transmission_failed), true);
                case Transferable.STATUS_CANCELLED:
                    return new Pair<>(context.getString(R.string.file_transmission_cancelled), true);
                case Transferable.STATUS_UPLOADING:
                    if (message.getStatus() == Message.STATUS_OFFERED) {
                        return new Pair<>(context.getString(R.string.offering_x_file,
                                getFileDescriptionString(context, message)), true);
                    } else {
                        return new Pair<>(context.getString(R.string.sending_x_file,
                                getFileDescriptionString(context, message)), true);
                    }
                default:
                    return new Pair<>("", false);
            }
        } else if (message.getEncryption() == Message.ENCRYPTION_PGP) {
            return new Pair<>(context.getString(R.string.pgp_message), true);
        } else if (message.getEncryption() == Message.ENCRYPTION_DECRYPTION_FAILED) {
            return new Pair<>(context.getString(R.string.decryption_failed), true);
        } else if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE) {
            return new Pair<>(context.getString(R.string.not_encrypted_for_this_device), true);
        } else if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL_FAILED) {
            return new Pair<>(context.getString(R.string.omemo_decryption_failed), true);
        } else if (message.isFileOrImage() && !moderated) {
            return new Pair<>(getFileDescriptionString(context, message), true);
        } else if (message.getType() == Message.TYPE_RTP_SESSION) {
            RtpSessionStatus rtpSessionStatus = RtpSessionStatus.of(message.getBody());
            final boolean received = message.getStatus() == Message.STATUS_RECEIVED;
            if (!rtpSessionStatus.successful && received) {
                return new Pair<>(context.getString(R.string.missed_call), true);
            } else {
                return new Pair<>(context.getString(received ? R.string.incoming_call : R.string.outgoing_call), true);
            }
        } else {
            final String body = MessageUtils.filterLtrRtl(message.getBody());
            if (body.startsWith(Message.ME_COMMAND)) {
                return new Pair<>(body.replaceAll("^" + Message.ME_COMMAND,
                        UIHelper.getMessageDisplayName(message) + " "), false);
            } else if (message.isGeoUri()) {
                return new Pair<>(context.getString(R.string.location), true);
            } else if (!moderated && (message.treatAsDownloadable() || MessageUtils.unInitiatedButKnownSize(message))) {
                return new Pair<>(context.getString(R.string.x_file_offered_for_download,
                        getFileDescriptionString(context, message)), true);
            } else {
                Drawable fallbackImg = ResourcesCompat.getDrawable(context.getResources(), R.drawable.ic_photo_24dp, null);
                fallbackImg.setBounds(0, 0, fallbackImg.getIntrinsicWidth(), fallbackImg.getIntrinsicHeight());
                SpannableStringBuilder styledBody = message.getSpannableBody(null, fallbackImg);
                final var processMarkup = styledBody.getSpans(0, body.length(), Message.PlainTextSpan.class).length > 0;
                if (textColor != 0 && processMarkup) {
                    StylingHelper.format(styledBody, 0, styledBody.length() - 1, textColor, false);
                }
                MyLinkify.addLinks(styledBody, message.getConversation().getAccount(), message.getConversation().getJid());

                for (final android.text.style.QuoteSpan quote : Lists.reverse(Lists.newArrayList(styledBody.getSpans(0, styledBody.length(), android.text.style.QuoteSpan.class)))) {
                    int start = styledBody.getSpanStart(quote);
                    int end = styledBody.getSpanEnd(quote);
                    if (start < 0 || end < 0 || (start == 0 && end == styledBody.length())) continue;
                    styledBody.delete(start, end);
                    styledBody.removeSpan(quote);
                }
                if (!processMarkup) return new Pair<>(styledBody, false);

                SpannableStringBuilder builder = new SpannableStringBuilder();
                for (CharSequence l : CharSequenceUtils.split(styledBody, '\n')) {
                    if (l.length() > 0) {
                        if (l.toString().equals("```")) {
                            continue;
                        }
                        char first = l.charAt(0);
                        if ((!QuoteHelper.isPositionQuoteStart(l, 0))) {
                            CharSequence line = CharSequenceUtils.trim(l);
                            if (line.length() == 0) {
                                continue;
                            }
                            char last = line.charAt(line.length() - 1);
                            if (builder.length() != 0) {
                                builder.append(' ');
                            }
                            builder.append(line);
                            if (!PUNCTIONATION.contains(last)) {
                                break;
                            }
                        }
                    }
                }
                if (builder.length() == 0) {
                    builder.append(body.trim());
                }
                return new Pair<>(builder, false);
            }
        }
    }

    public static boolean isLastLineQuote(String body) {
        if (body.endsWith("\n")) {
            return false;
        }
        String[] lines = body.split("\n");
        if (lines.length == 0) {
            return false;
        }
        String line = lines[lines.length - 1];
        if (line.isEmpty()) {
            return false;
        }
        char first = line.charAt(0);
        return first == '>' && isPositionFollowedByQuoteableCharacter(line, 0) || first == '\u00bb';
    }

    public static CharSequence shorten(CharSequence input) {
        return input.length() > 256 ? StylingHelper.subSequence(input, 0, 256) : input;
    }

    public static boolean isPositionPrecededByBodyStart(CharSequence body, int pos){
        // true if not a single linebreak before current position
        for (int i = pos - 1; i >= 0; i--){
            if (body.charAt(i) != ' '){
                return false;
            }
        }
        return true;
    }

    public static boolean isPositionPrecededByLineStart(CharSequence body, int pos){
        if (isPositionPrecededByBodyStart(body, pos)){
            return true;
        }
        return body.charAt(pos - 1) == '\n';
    }

    public static boolean isPositionFollowedByQuoteableCharacter(CharSequence body, int pos) {
        return !isPositionFollowedByNumber(body, pos)
                && !isPositionFollowedByEmoticon(body, pos)
                && !isPositionFollowedByEquals(body, pos);
    }

    private static boolean isPositionFollowedByNumber(CharSequence body, int pos) {
        boolean previousWasNumber = false;
        for (int i = pos + 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (Character.isDigit(body.charAt(i))) {
                previousWasNumber = true;
            } else if (previousWasNumber && (c == '.' || c == ',')) {
                previousWasNumber = false;
            } else {
                return (Character.isWhitespace(c) || c == '%' || c == '+') && previousWasNumber;
            }
        }
        return previousWasNumber;
    }

    private static boolean isPositionFollowedByEquals(CharSequence body, int pos) {
        return body.length() > pos + 1 && body.charAt(pos + 1) == '=';
    }

    private static boolean isPositionFollowedByEmoticon(CharSequence body, int pos) {
        if (body.length() <= pos + 1) {
            return false;
        } else {
            final char first = body.charAt(pos + 1);
            return first == ';'
                    || first == ':'
                    || first == '.' // do not quote >.< (but >>.<)
                    || closingBeforeWhitespace(body, pos + 1);
        }
    }

    private static boolean closingBeforeWhitespace(CharSequence body, int pos) {
        for (int i = pos; i < body.length(); ++i) {
            final char c = body.charAt(i);
            if (Character.isWhitespace(c)) {
                return false;
            } else if (QuoteHelper.isPositionQuoteCharacter(body, pos) || QuoteHelper.isPositionQuoteEndCharacter(body, pos)) {
                return body.length() == i + 1 || Character.isWhitespace(body.charAt(i + 1));
            }
        }
        return false;
    }

    public static String getDisplayName(MucOptions.User user) {
        Contact contact = user.getContact();
        if (contact != null) {
            return contact.getDisplayName();
        } else {
            final String name = user.getNick();
            if (name != null) {
                return name;
            }
            final Jid realJid = user.getRealJid();
            if (realJid != null) {
                return JidHelper.localPartOrFallback(realJid);
            }
            return null;
        }
    }

    public static String concatNames(List<MucOptions.User> users) {
        return concatNames(users, users.size());
    }

    public static String concatNames(List<MucOptions.User> users, int max) {
        StringBuilder builder = new StringBuilder();
        final boolean shortNames = users.size() >= 3;
        for (int i = 0; i < Math.min(users.size(), max); ++i) {
            if (builder.length() != 0) {
                builder.append(", ");
            }
            final String name = UIHelper.getDisplayName(users.get(i));
            if (name != null) {
                builder.append(shortNames ? name.split("\\s+")[0] : name);
            }
        }
        return builder.toString();
    }

    public static String getFileDescriptionString(final Context context, final Message message) {
        final String mime = message.getMimeType();
        if (Strings.isNullOrEmpty(mime)) {
            return context.getString(R.string.file);
        } else if (MimeUtils.AMBIGUOUS_CONTAINER_FORMATS.contains(mime)) {
            return context.getString(R.string.multimedia_file);
        } else if (mime.equals("audio/x-m4b")) {
            return context.getString(R.string.audiobook);
        } else if (mime.startsWith("audio/")) {
            return context.getString(R.string.audio);
        } else if (mime.startsWith("video/")) {
            return context.getString(R.string.video);
        } else if (mime.equals("image/gif")) {
            return context.getString(R.string.gif);
        } else if (mime.equals("image/svg+xml")) {
            return context.getString(R.string.vector_graphic);
        } else if (mime.startsWith("image/") || message.getType() == Message.TYPE_IMAGE) {
            return context.getString(R.string.image);
        } else if (mime.contains("pdf")) {
            return context.getString(R.string.pdf_document);
        } else if (mime.equals("application/vnd.android.package-archive")) {
            return context.getString(R.string.apk);
        } else if (mime.equals(ExportBackupWorker.MIME_TYPE)) {
            return context.getString(R.string.conversations_backup);
        } else if (mime.contains("vcard")) {
            return context.getString(R.string.vcard);
        } else if (mime.equals("text/x-vcalendar") || mime.equals("text/calendar")) {
            return context.getString(R.string.event);
        } else if (mime.equals("application/epub+zip") || mime.equals("application/vnd.amazon.mobi8-ebook")) {
            return context.getString(R.string.ebook);
        } else if (mime.equals("application/gpx+xml")) {
            return context.getString(R.string.gpx_track);
        } else if (mime.equals("application/xdc+zip")) {
            return "Widget";
        } else if (mime.equals("text/plain")) {
            return context.getString(R.string.plain_text_document);
        } else {
            return mime;
        }
    }

    public static String getMessageDisplayName(final Message message) {
        if (message.getModerated() != null) return "moderated";

        final Conversational conversation = message.getConversation();
        if (message.getStatus() == Message.STATUS_RECEIVED) {
            final Contact contact = message.getContact();
            if (conversation.getMode() == Conversation.MODE_MULTI) {
                if (contact != null) {
                    return contact.getDisplayName();
                } else {
                    if (conversation instanceof Conversation) {
                        final MucOptions.User user = ((Conversation) conversation).getMucOptions().findUserByFullJid(message.getCounterpart());
                        if (user != null) {
                            final String dname = getDisplayName(user);
                            if (dname != null) return dname;
                        }
                    }
                    return getDisplayedMucCounterpart(message.getCounterpart());
                }
            } else {
                return contact != null ? contact.getDisplayName() : "";
            }
        } else {
            if (conversation instanceof Conversation && conversation.getMode() == Conversation.MODE_MULTI) {
                return ((Conversation) conversation).getMucOptions().getSelf().getNick();
            } else {
                final Account account = conversation.getAccount();
                final Jid jid = account.getJid();
                final String displayName = account.getDisplayName();
                if (Strings.isNullOrEmpty(displayName)) {
                    return jid.getLocal() != null ? jid.getLocal() : jid.getDomain().toString();
                } else {
                    return displayName;
                }

            }
        }
    }

    public static String getMessageHint(final Context context,final  Conversation conversation) {
        return switch (conversation.getNextEncryption()) {
            case Message.ENCRYPTION_NONE -> {
                if (Config.multipleEncryptionChoices()) {
                    yield context.getString(R.string.send_message);
                } else {
                    yield context.getString(R.string.send_message_to_x, conversation.getName());
                }
            }
            case Message.ENCRYPTION_AXOLOTL -> {
                final AxolotlService axolotlService = conversation.getAccount().getAxolotlService();
                if (axolotlService != null && axolotlService.trustedSessionVerified(conversation)) {
                    yield context.getString(R.string.send_omemo_x509_message);
                } else {
                    yield context.getString(R.string.send_encrypted_message);
                }
            }
            default -> context.getString(R.string.send_encrypted_message);
        };
    }

    public static String getDisplayedMucCounterpart(final Jid counterpart) {
        if (counterpart == null) {
            return "";
        } else if (!counterpart.isBareJid()) {
            return counterpart.getResource().trim();
        } else {
            return counterpart.toString().trim();
        }
    }

    public static boolean receivedLocationQuestion(final Message message) {
        if (message == null
                || message.getStatus() != Message.STATUS_RECEIVED
                || message.getType() != Message.TYPE_TEXT) {
            return false;
        }
        final String body = Strings.nullToEmpty(message.getBody())
                .trim()
                .toLowerCase(Locale.getDefault())
                .replace("?", "").replace("¿", "");
        return LOCATION_QUESTIONS.contains(body);
    }

    public static void setStatus(final TextView textView, Presence.Status status) {
        final @StringRes int text;
        final @ColorRes int color =
                switch (status) {
                    case CHAT -> {
                        text = R.string.presence_chat;
                        yield R.color.green_800;
                    }
                    case ONLINE -> {
                        text = R.string.presence_online;
                        yield R.color.green_800;
                    }
                    case AWAY -> {
                        text = R.string.presence_away;
                        yield R.color.amber_800;
                    }
                    case XA -> {
                        text = R.string.presence_xa;
                        yield R.color.orange_800;
                    }
                    case DND -> {
                        text = R.string.presence_dnd;
                        yield R.color.red_800;
                    }
                    default -> throw new IllegalStateException();
                };
        textView.setText(text);
        textView.setBackgroundTintList(
                ColorStateList.valueOf(
                        MaterialColors.harmonizeWithPrimary(
                                textView.getContext(),
                                ContextCompat.getColor(textView.getContext(), color))));
    }

    public static String filesizeToString(long size) {
        if (size > (1.5 * 1024 * 1024)) {
            return Math.round(size * 1f / (1024 * 1024)) + " MiB";
        } else if (size >= 1024) {
            return Math.round(size * 1f / 1024) + " KiB";
        } else {
            return size + " B";
        }
    }
}
