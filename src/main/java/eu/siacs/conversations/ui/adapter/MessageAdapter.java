package eu.siacs.conversations.ui.adapter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.preference.PreferenceManager;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.ImageSpan;
import android.text.style.ClickableSpan;
import android.text.format.DateUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.URLSpan;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.accessibility.AccessibilityEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import com.cheogram.android.BobTransfer;
import com.cheogram.android.MessageTextActionModeCallback;
import com.cheogram.android.SwipeDetector;
import com.cheogram.android.WebxdcPage;
import com.cheogram.android.WebxdcUpdate;
import androidx.core.widget.ImageViewCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import com.lelloman.identicon.view.GithubIdenticonView;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.ipfs.cid.Cid;

import me.saket.bettermovementmethod.BetterLinkMovementMethod;

import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.axolotl.FingerprintStatus;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.Message.FileParams;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.entities.Roster;
import eu.siacs.conversations.entities.RtpSessionStatus;
import eu.siacs.conversations.entities.Transferable;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.MessageArchiveService;
import eu.siacs.conversations.services.NotificationService;
import eu.siacs.conversations.ui.Activities;
import eu.siacs.conversations.ui.ConversationFragment;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.service.AudioPlayer;
import eu.siacs.conversations.ui.text.DividerSpan;
import eu.siacs.conversations.ui.text.QuoteSpan;
import eu.siacs.conversations.ui.util.Attachment;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.ui.util.MyLinkify;
import eu.siacs.conversations.ui.util.QuoteHelper;
import eu.siacs.conversations.ui.util.ShareUtil;
import eu.siacs.conversations.ui.util.ViewUtil;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.Emoticons;
import eu.siacs.conversations.utils.GeoHelper;
import eu.siacs.conversations.utils.MessageUtils;
import eu.siacs.conversations.utils.StylingHelper;
import eu.siacs.conversations.utils.TimeFrameUtils;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.mam.MamReference;
import eu.siacs.conversations.xml.Element;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageAdapter extends ArrayAdapter<Message> {

    public static final String DATE_SEPARATOR_BODY = "DATE_SEPARATOR";
    private static final int SENT = 0;
    private static final int RECEIVED = 1;
    private static final int STATUS = 2;
    private static final int DATE_SEPARATOR = 3;
    private static final int RTP_SESSION = 4;
    private final XmppActivity activity;
    private final AudioPlayer audioPlayer;
    private List<String> highlightedTerm = null;
    private final DisplayMetrics metrics;
    private ConversationFragment mConversationFragment = null;
    private OnContactPictureClicked mOnContactPictureClickedListener;
    private OnContactPictureClicked mOnMessageBoxClickedListener;
    private OnContactPictureClicked mOnMessageBoxSwipedListener;
    private OnContactPictureLongClicked mOnContactPictureLongClickedListener;
    private OnInlineImageLongClicked mOnInlineImageLongClickedListener;
    private boolean mUseGreenBackground = false;
    private BubbleDesign bubbleDesign = new BubbleDesign(false, false);
    private final boolean mForceNames;
    private final Map<String, WebxdcUpdate> lastWebxdcUpdate = new HashMap<>();
    private String selectionUuid = null;

    public MessageAdapter(
            final XmppActivity activity, final List<Message> messages, final boolean forceNames) {
        super(activity, 0, messages);
        this.audioPlayer = new AudioPlayer(this);
        this.activity = activity;
        metrics = getContext().getResources().getDisplayMetrics();
        updatePreferences();
        this.mForceNames = forceNames;
    }

    public MessageAdapter(final XmppActivity activity, final List<Message> messages) {
        this(activity, messages, false);
    }

    private static void resetClickListener(View... views) {
        for (View view : views) {
            if (view != null) view.setOnClickListener(null);
        }
    }

    public void flagScreenOn() {
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    public void flagScreenOff() {
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    public void setVolumeControl(final int stream) {
        activity.setVolumeControlStream(stream);
    }

    public void setOnContactPictureClicked(OnContactPictureClicked listener) {
        this.mOnContactPictureClickedListener = listener;
    }

    public void setOnMessageBoxClicked(OnContactPictureClicked listener) {
        this.mOnMessageBoxClickedListener = listener;
    }

    public void setOnMessageBoxSwiped(OnContactPictureClicked listener) {
        this.mOnMessageBoxSwipedListener = listener;
    }

    public void setConversationFragment(ConversationFragment frag) {
        mConversationFragment = frag;
    }

    public void quoteText(String text) {
        if (mConversationFragment != null) mConversationFragment.quoteText(text);
    }

    public boolean hasSelection() {
        return selectionUuid != null;
    }

    public Activity getActivity() {
        return activity;
    }

    public void setOnContactPictureLongClicked(OnContactPictureLongClicked listener) {
        this.mOnContactPictureLongClickedListener = listener;
    }

    public void setOnInlineImageLongClicked(OnInlineImageLongClicked listener) {
        this.mOnInlineImageLongClickedListener = listener;
    }

    @Override
    public int getViewTypeCount() {
        return 5;
    }

    private int getItemViewType(Message message) {
        if (message.getType() == Message.TYPE_STATUS) {
            if (DATE_SEPARATOR_BODY.equals(message.getBody())) {
                return DATE_SEPARATOR;
            } else {
                return STATUS;
            }
        } else if (message.getType() == Message.TYPE_RTP_SESSION) {
            return RTP_SESSION;
        } else if (message.getStatus() <= Message.STATUS_RECEIVED) {
            return RECEIVED;
        } else {
            return SENT;
        }
    }

    @Override
    public int getItemViewType(int position) {
        return this.getItemViewType(getItem(position));
    }

    private void displayStatus(
            final ViewHolder viewHolder,
            final Message message,
            final int type,
            final BubbleColor bubbleColor) {
        final int mergedStatus = message.getMergedStatus();
        final boolean error;
        if (viewHolder.indicatorReceived != null) {
            viewHolder.indicatorReceived.setVisibility(View.GONE);
        }
        final Transferable transferable = message.getTransferable();
        final boolean multiReceived =
                message.getConversation().getMode() == Conversation.MODE_MULTI
                        && mergedStatus <= Message.STATUS_RECEIVED;
        final String fileSize;
        if (message.isFileOrImage()
                || transferable != null
                || MessageUtils.unInitiatedButKnownSize(message)) {
            final FileParams params = message.getFileParams();
            fileSize = params.size != null ? UIHelper.filesizeToString(params.size) : null;
            if (message.getStatus() == Message.STATUS_SEND_FAILED
                    || (transferable != null
                            && (transferable.getStatus() == Transferable.STATUS_FAILED
                                    || transferable.getStatus()
                                            == Transferable.STATUS_CANCELLED))) {
                error = true;
            } else {
                error = message.getStatus() == Message.STATUS_SEND_FAILED;
            }
        } else {
            fileSize = null;
            error = message.getStatus() == Message.STATUS_SEND_FAILED;
        }
        if (type == SENT) {
            final @DrawableRes Integer receivedIndicator =
                    getMessageStatusAsDrawable(message, mergedStatus);
            if (receivedIndicator == null) {
                viewHolder.indicatorReceived.setVisibility(View.INVISIBLE);
            } else {
                viewHolder.indicatorReceived.setImageResource(receivedIndicator);
                if (mergedStatus == Message.STATUS_SEND_FAILED) {
                    setImageTintError(viewHolder.indicatorReceived);
                } else {
                    setImageTint(viewHolder.indicatorReceived, bubbleColor);
                }
                viewHolder.indicatorReceived.setVisibility(View.VISIBLE);
            }
        }
        final var additionalStatusInfo = getAdditionalStatusInfo(message, mergedStatus);

        if (error && type == SENT) {
            viewHolder.time.setTextColor(
                    MaterialColors.getColor(
                            viewHolder.time, com.google.android.material.R.attr.colorError));
        } else {
            setTextColor(viewHolder.time, bubbleColor);
        }
        if (message.getEncryption() == Message.ENCRYPTION_NONE) {
            viewHolder.indicator.setVisibility(View.GONE);
        } else {
            boolean verified = false;
            if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL) {
                final FingerprintStatus status =
                        message.getConversation()
                                .getAccount()
                                .getAxolotlService()
                                .getFingerprintTrust(message.getFingerprint());
                if (status != null && status.isVerified()) {
                    verified = true;
                }
            }
            if (verified) {
                viewHolder.indicator.setImageResource(R.drawable.ic_verified_user_24dp);
            } else {
                viewHolder.indicator.setImageResource(R.drawable.ic_lock_24dp);
            }
            if (error && type == SENT) {
                setImageTintError(viewHolder.indicator);
            } else {
                setImageTint(viewHolder.indicator, bubbleColor);
            }
            viewHolder.indicator.setVisibility(View.VISIBLE);
        }

        if (viewHolder.edit_indicator != null) {
            if (message.edited()) {
                viewHolder.edit_indicator.setVisibility(View.VISIBLE);
                if (error && type == SENT) {
                    setImageTintError(viewHolder.edit_indicator);
                } else {
                    setImageTint(viewHolder.edit_indicator, bubbleColor);
                }
            } else {
                viewHolder.edit_indicator.setVisibility(View.GONE);
            }
        }

        final String formattedTime =
                UIHelper.readableTimeDifferenceFull(getContext(), message.getMergedTimeSent());
        final String bodyLanguage = message.getBodyLanguage();
        final ImmutableList.Builder<String> timeInfoBuilder = new ImmutableList.Builder<>();
        if (message.getStatus() <= Message.STATUS_RECEIVED) {
            timeInfoBuilder.add(formattedTime);
            if (fileSize != null) {
                timeInfoBuilder.add(fileSize);
            }
            if (mForceNames || multiReceived || (message.getTrueCounterpart() != null && message.getContact() != null)) {
                final String displayName = UIHelper.getMessageDisplayName(message);
                if (displayName != null) {
                    timeInfoBuilder.add(displayName);
                }
            }
            if (bodyLanguage != null) {
                timeInfoBuilder.add(bodyLanguage.toUpperCase(Locale.US));
            }
        } else {
            if (bodyLanguage != null) {
                timeInfoBuilder.add(bodyLanguage.toUpperCase(Locale.US));
            }
            if (fileSize != null) {
                timeInfoBuilder.add(fileSize);
            }
            // for space reasons we display only 'additional status info' (send progress or concrete
            // failure reason) or the time
            if (additionalStatusInfo != null) {
                timeInfoBuilder.add(additionalStatusInfo);
            } else {
                timeInfoBuilder.add(formattedTime);
            }
        }
        final var timeInfo = timeInfoBuilder.build();
        viewHolder.time.setText(Joiner.on(" \u00B7 ").join(timeInfo));
    }

    public static @DrawableRes Integer getMessageStatusAsDrawable(
            final Message message, final int status) {
        final var transferable = message.getTransferable();
        return switch (status) {
            case Message.STATUS_WAITING -> R.drawable.ic_more_horiz_24dp;
            case Message.STATUS_UNSEND -> transferable == null ? null : R.drawable.ic_upload_24dp;
            case Message.STATUS_SEND -> R.drawable.ic_done_24dp;
            case Message.STATUS_SEND_RECEIVED, Message.STATUS_SEND_DISPLAYED -> R.drawable
                    .ic_done_all_24dp;
            case Message.STATUS_SEND_FAILED -> {
                final String errorMessage = message.getErrorMessage();
                if (Message.ERROR_MESSAGE_CANCELLED.equals(errorMessage)) {
                    yield R.drawable.ic_cancel_24dp;
                } else {
                    yield R.drawable.ic_error_24dp;
                }
            }
            case Message.STATUS_OFFERED -> R.drawable.ic_p2p_24dp;
            default -> null;
        };
    }

    @Nullable
    private String getAdditionalStatusInfo(final Message message, final int mergedStatus) {
        final String additionalStatusInfo;
        if (mergedStatus == Message.STATUS_SEND_FAILED) {
            final String errorMessage = Strings.nullToEmpty(message.getErrorMessage());
            final String[] errorParts = errorMessage.split("\\u001f", 2);
            if (errorParts.length == 2 && errorParts[0].equals("file-too-large")) {
                additionalStatusInfo = getContext().getString(R.string.file_too_large);
            } else {
                additionalStatusInfo = null;
            }
        } else if (mergedStatus == Message.STATUS_UNSEND) {
            final var transferable = message.getTransferable();
            if (transferable == null) {
                return null;
            }
            return getContext().getString(R.string.sending_file, transferable.getProgress());
        } else {
            additionalStatusInfo = null;
        }
        return additionalStatusInfo;
    }

    private void displayInfoMessage(
            ViewHolder viewHolder, CharSequence text, final BubbleColor bubbleColor) {
        viewHolder.download_button.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        viewHolder.image.setVisibility(View.GONE);
        viewHolder.messageBody.setVisibility(View.VISIBLE);
        viewHolder.messageBody.setText(text);
        viewHolder.messageBody.setTextColor(
                bubbleToOnSurfaceVariant(viewHolder.messageBody, bubbleColor));
        viewHolder.messageBody.setTextIsSelectable(false);
    }

    private void displayEmojiMessage(
            final ViewHolder viewHolder, final SpannableStringBuilder body, final BubbleColor bubbleColor) {
        viewHolder.download_button.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        viewHolder.image.setVisibility(View.GONE);
        viewHolder.messageBody.setVisibility(View.VISIBLE);
        setTextColor(viewHolder.messageBody, bubbleColor);
        ImageSpan[] imageSpans = body.getSpans(0, body.length(), ImageSpan.class);
        float size = imageSpans.length == 1 || Emoticons.isEmoji(body.toString()) ? 3.0f : 2.0f;
        body.setSpan(
                new RelativeSizeSpan(size), 0, body.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        viewHolder.messageBody.setText(body);
    }

    private void applyQuoteSpan(
            final TextView textView,
            SpannableStringBuilder body,
            int start,
            int end,
            final BubbleColor bubbleColor) {
        if (start > 1 && !"\n\n".equals(body.subSequence(start - 2, start).toString())) {
            body.insert(start++, "\n");
            body.setSpan(
                    new DividerSpan(false), start - 2, start, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            end++;
        }
        if (end < body.length() - 1 && !"\n\n".equals(body.subSequence(end, end + 2).toString())) {
            body.insert(end, "\n");
            body.setSpan(
                new DividerSpan(false),
                end,
                end + ("\n".equals(body.subSequence(end + 1, end + 2).toString()) ? 2 : 1),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
        final DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        body.setSpan(
                new QuoteSpan(bubbleToOnSurfaceVariant(textView, bubbleColor), metrics),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    public boolean handleTextQuotes(final TextView textView, final SpannableStringBuilder body) {
        final boolean colorfulBackground = this.bubbleDesign.colorfulChatBubbles;
        final BubbleColor bubbleColor = colorfulBackground ? BubbleColor.SECONDARY : BubbleColor.SURFACE;
        return handleTextQuotes(textView, body, bubbleColor);
    }

    /**
     * Applies QuoteSpan to group of lines which starts with > or » characters. Appends likebreaks
     * and applies DividerSpan to them to show a padding between quote and text.
     */
    public boolean handleTextQuotes(
            final TextView textView,
            final SpannableStringBuilder body,
            final BubbleColor bubbleColor) {
        boolean startsWithQuote = false;
        int quoteDepth = 1;
        while (QuoteHelper.bodyContainsQuoteStart(body) && quoteDepth <= Config.QUOTE_MAX_DEPTH) {
            char previous = '\n';
            int lineStart = -1;
            int lineTextStart = -1;
            int quoteStart = -1;
            for (int i = 0; i <= body.length(); i++) {
                char current = body.length() > i ? body.charAt(i) : '\n';
                if (lineStart == -1) {
                    if (previous == '\n') {
                        if (i < body.length() && QuoteHelper.isPositionQuoteStart(body, i)) {
                            // Line start with quote
                            lineStart = i;
                            if (quoteStart == -1) quoteStart = i;
                            if (i == 0) startsWithQuote = true;
                        } else if (quoteStart >= 0) {
                            // Line start without quote, apply spans there
                            applyQuoteSpan(textView, body, quoteStart, i - 1, bubbleColor);
                            quoteStart = -1;
                        }
                    }
                } else {
                    // Remove extra spaces between > and first character in the line
                    // > character will be removed too
                    if (current != ' ' && lineTextStart == -1) {
                        lineTextStart = i;
                    }
                    if (current == '\n') {
                        body.delete(lineStart, lineTextStart);
                        i -= lineTextStart - lineStart;
                        if (i == lineStart) {
                            // Avoid empty lines because span over empty line can be hidden
                            body.insert(i++, " ");
                        }
                        lineStart = -1;
                        lineTextStart = -1;
                    }
                }
                previous = current;
            }
            if (quoteStart >= 0) {
                // Apply spans to finishing open quote
                applyQuoteSpan(textView, body, quoteStart, body.length(), bubbleColor);
            }
            quoteDepth++;
        }
        return startsWithQuote;
    }

    private SpannableStringBuilder getSpannableBody(final Message message) {
        Drawable fallbackImg = ResourcesCompat.getDrawable(activity.getResources(), R.drawable.ic_photo_24dp, null);
        return message.getMergedBody((cid) -> {
            try {
                DownloadableFile f = activity.xmppConnectionService.getFileForCid(cid);
                if (f == null || !f.canRead()) {
                    if (!message.trusted() && !message.getConversation().canInferPresence()) return null;

                    try {
                        new BobTransfer(BobTransfer.uri(cid), message.getConversation().getAccount(), message.getCounterpart(), activity.xmppConnectionService).start();
                    } catch (final NoSuchAlgorithmException | URISyntaxException e) { }
                    return null;
                }

                Drawable d = activity.xmppConnectionService.getFileBackend().getThumbnail(f, activity.getResources(), (int) (metrics.density * 288), true);
                if (d == null) {
                    new ThumbnailTask().execute(f);
                }
                return d;
            } catch (final IOException e) {
                return null;
            }
        }, fallbackImg);
    }

    private void displayTextMessage(
            final ViewHolder viewHolder, final Message message, final BubbleColor bubbleColor, final int type) {
        viewHolder.download_button.setVisibility(View.GONE);
        viewHolder.image.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        viewHolder.messageBody.setVisibility(View.VISIBLE);
        setTextColor(viewHolder.messageBody, bubbleColor);
        setTextSize(viewHolder.messageBody, this.bubbleDesign.largeFont);

        viewHolder.messageBody.setTypeface(null, Typeface.NORMAL);

        if (message.getBody() != null && !message.getBody().equals("")) {
            viewHolder.messageBody.setTextIsSelectable(true);
            viewHolder.messageBody.setVisibility(View.VISIBLE);
            final String nick = UIHelper.getMessageDisplayName(message);
            SpannableStringBuilder body = getSpannableBody(message);
            boolean hasMeCommand = message.hasMeCommand();
            if (hasMeCommand) {
                body = body.replace(0, Message.ME_COMMAND.length(), nick + " ");
            }
            if (body.length() > Config.MAX_DISPLAY_MESSAGE_CHARS) {
                body = new SpannableStringBuilder(body, 0, Config.MAX_DISPLAY_MESSAGE_CHARS);
                body.append("\u2026");
            }
            Message.MergeSeparator[] mergeSeparators =
                    body.getSpans(0, body.length(), Message.MergeSeparator.class);
            for (Message.MergeSeparator mergeSeparator : mergeSeparators) {
                int start = body.getSpanStart(mergeSeparator);
                int end = body.getSpanEnd(mergeSeparator);
                body.setSpan(new DividerSpan(true), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            for (final android.text.style.QuoteSpan quote : body.getSpans(0, body.length(), android.text.style.QuoteSpan.class)) {
                int start = body.getSpanStart(quote);
                int end = body.getSpanEnd(quote);
                body.removeSpan(quote);
                applyQuoteSpan(viewHolder.messageBody, body, start, end, bubbleColor);
            }
            boolean startsWithQuote = handleTextQuotes(viewHolder.messageBody, body, bubbleColor);
            if (!message.isPrivateMessage()) {
                if (hasMeCommand) {
                    body.setSpan(
                            new StyleSpan(Typeface.BOLD_ITALIC),
                            0,
                            nick.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } else {
                String privateMarker;
                if (message.getStatus() <= Message.STATUS_RECEIVED) {
                    privateMarker = activity.getString(R.string.private_message);
                } else {
                    Jid cp = message.getCounterpart();
                    privateMarker =
                            activity.getString(
                                    R.string.private_message_to,
                                    Strings.nullToEmpty(cp == null ? null : cp.getResource()));
                }
                body.insert(0, privateMarker);
                int privateMarkerIndex = privateMarker.length();
                if (startsWithQuote) {
                    body.insert(privateMarkerIndex, "\n\n");
                    body.setSpan(
                            new DividerSpan(false),
                            privateMarkerIndex,
                            privateMarkerIndex + 2,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else {
                    body.insert(privateMarkerIndex, " ");
                }
                body.setSpan(
                        new ForegroundColorSpan(
                                bubbleToOnSurfaceVariant(viewHolder.messageBody, bubbleColor)),
                        0,
                        privateMarkerIndex,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                body.setSpan(
                        new StyleSpan(Typeface.BOLD),
                        0,
                        privateMarkerIndex,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (hasMeCommand) {
                    body.setSpan(
                            new StyleSpan(Typeface.BOLD_ITALIC),
                            privateMarkerIndex + 1,
                            privateMarkerIndex + 1 + nick.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            if (message.getConversation().getMode() == Conversation.MODE_MULTI
                    && message.getStatus() == Message.STATUS_RECEIVED) {
                if (message.getConversation() instanceof Conversation conversation) {
                    Pattern pattern =
                            NotificationService.generateNickHighlightPattern(
                                    conversation.getMucOptions().getActualNick());
                    Matcher matcher = pattern.matcher(body);
                    while (matcher.find()) {
                        body.setSpan(
                                new StyleSpan(Typeface.BOLD),
                                matcher.start(),
                                matcher.end(),
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }

                    pattern = NotificationService.generateNickHighlightPattern(conversation.getMucOptions().getActualName());
                    matcher = pattern.matcher(body);
                    while (matcher.find()) {
                        body.setSpan(new StyleSpan(Typeface.BOLD), matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
            }
            Matcher matcher = Emoticons.getEmojiPattern(body).matcher(body);
            while (matcher.find()) {
                if (matcher.start() < matcher.end()) {
                    body.setSpan(
                            new RelativeSizeSpan(1.2f),
                            matcher.start(),
                            matcher.end(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }

            StylingHelper.format(body, viewHolder.messageBody.getCurrentTextColor());
            if (highlightedTerm != null) {
                StylingHelper.highlight(viewHolder.messageBody, body, highlightedTerm);
            }
            MyLinkify.addLinks(body, message.getConversation().getAccount(), message.getConversation().getJid());
            viewHolder.messageBody.setAutoLinkMask(0);
            viewHolder.messageBody.setText(body);
            BetterLinkMovementMethod method = new BetterLinkMovementMethod() {
                @Override
                protected void dispatchUrlLongClick(TextView tv, ClickableSpan span) {
                    if (span instanceof URLSpan || mOnInlineImageLongClickedListener == null) {
                        tv.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0));
                        super.dispatchUrlLongClick(tv, span);
                        return;
                    }

                    Spannable body = (Spannable) tv.getText();
                    ImageSpan[] imageSpans = body.getSpans(body.getSpanStart(span), body.getSpanEnd(span), ImageSpan.class);
                    if (imageSpans.length > 0) {
                        Uri uri = Uri.parse(imageSpans[0].getSource());
                        Cid cid = BobTransfer.cid(uri);
                        if (cid == null) return;
                        if (mOnInlineImageLongClickedListener.onInlineImageLongClicked(cid)) {
                            tv.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0));
                        }
                    }
                }
            };
            method.setOnLinkLongClickListener((tv, url) -> {
                tv.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0));
                ShareUtil.copyLinkToClipboard(activity, url);
                return true;
            });
            viewHolder.messageBody.setMovementMethod(method);
        } else {
            viewHolder.messageBody.setText("");
            viewHolder.messageBody.setTextIsSelectable(false);
            toggleWhisperInfo(viewHolder, message, bubbleColor);
        }
    }

    private void displayDownloadableMessage(
            ViewHolder viewHolder,
            final Message message,
            String text,
            final BubbleColor bubbleColor, final int type) {
        displayTextMessage(viewHolder, message, bubbleColor, type);
        toggleWhisperInfo(viewHolder, message, bubbleColor);
        viewHolder.image.setVisibility(View.GONE);
        List<Element> thumbs = message.getFileParams() != null ? message.getFileParams().getThumbnails() : null;
        if (thumbs != null && !thumbs.isEmpty()) {
            for (Element thumb : thumbs) {
                Uri uri = Uri.parse(thumb.getAttribute("uri"));
                if (uri.getScheme().equals("data")) {
                    String[] parts = uri.getSchemeSpecificPart().split(",", 2);
                    parts = parts[0].split(";");
                    if (!parts[0].equals("image/blurhash") && !parts[0].equals("image/thumbhash") && !parts[0].equals("image/jpeg") && !parts[0].equals("image/png") && !parts[0].equals("image/webp") && !parts[0].equals("image/gif")) continue;
                } else if (uri.getScheme().equals("cid")) {
                    Cid cid = BobTransfer.cid(uri);
                    if (cid == null) continue;
                    DownloadableFile f = activity.xmppConnectionService.getFileForCid(cid);
                    if (f == null || !f.canRead()) {
                        if (!message.trusted() && !message.getConversation().canInferPresence()) continue;

                        try {
                            new BobTransfer(BobTransfer.uri(cid), message.getConversation().getAccount(), message.getCounterpart(), activity.xmppConnectionService).start();
                        } catch (final NoSuchAlgorithmException | URISyntaxException e) { }
                        continue;
                    }
                } else {
                    continue;
                }

                int width = message.getFileParams().width;
                if (width < 1 && thumb.getAttribute("width") != null) width = Integer.parseInt(thumb.getAttribute("width"));
                if (width < 1) width = 1920;

                int height = message.getFileParams().height;
                if (height < 1 && thumb.getAttribute("height") != null) height = Integer.parseInt(thumb.getAttribute("height"));
                if (height < 1) height = 1080;

                viewHolder.image.setVisibility(View.VISIBLE);
                imagePreviewLayout(width, height, viewHolder.image, message.getBody() != null && message.getBody().length() > 0);
                activity.loadBitmap(message, viewHolder.image);
                viewHolder.image.setOnClickListener(v -> ConversationFragment.downloadFile(activity, message));

                break;
            }
        }
        viewHolder.audioPlayer.setVisibility(View.GONE);
        viewHolder.download_button.setVisibility(View.VISIBLE);
        viewHolder.download_button.setText(text);
        final var attachment = Attachment.of(message);
        final @DrawableRes int imageResource = MediaAdapter.getImageDrawable(attachment);
        viewHolder.download_button.setIconResource(imageResource);
        viewHolder.download_button.setOnClickListener(
                v -> ConversationFragment.downloadFile(activity, message));
    }

    private void displayWebxdcMessage(ViewHolder viewHolder, final Message message, final BubbleColor bubbleColor, final int type) {
        Cid webxdcCid = message.getFileParams().getCids().get(0);
        WebxdcPage webxdc = new WebxdcPage(activity, webxdcCid, message, activity.xmppConnectionService);
        displayTextMessage(viewHolder, message, bubbleColor, type);
        viewHolder.image.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        viewHolder.download_button.setVisibility(View.VISIBLE);
        viewHolder.download_button.setText("Open " + webxdc.getName());
        viewHolder.download_button.setOnClickListener(v -> {
            Conversation conversation = (Conversation) message.getConversation();
            if (!conversation.switchToSession("webxdc\0" + message.getUuid())) {
                conversation.startWebxdc(webxdc);
            }
        });

        final WebxdcUpdate lastUpdate;
        synchronized(lastWebxdcUpdate) { lastUpdate = lastWebxdcUpdate.get(message.getUuid()); }
        if (lastUpdate == null) {
            new Thread(() -> {
                final WebxdcUpdate update = activity.xmppConnectionService.findLastWebxdcUpdate(message);
                if (update != null) {
                    synchronized(lastWebxdcUpdate) { lastWebxdcUpdate.put(message.getUuid(), update); }
                    activity.xmppConnectionService.updateConversationUi();
                }
            }).start();
        } else {
            if (lastUpdate != null && (lastUpdate.getSummary() != null || lastUpdate.getDocument() != null)) {
                viewHolder.messageBody.setVisibility(View.VISIBLE);
                viewHolder.messageBody.setText(
                    (lastUpdate.getDocument() == null ? "" : lastUpdate.getDocument() + "\n") +
                    (lastUpdate.getSummary() == null ? "" : lastUpdate.getSummary())
                );
            }
        }

        final LruCache<String, Drawable> cache = activity.xmppConnectionService.getDrawableCache();
        final Drawable d = cache.get("webxdc:icon:" + webxdcCid);
        if (d == null) {
            new Thread(() -> {
                Drawable icon = webxdc.getIcon();
                if (icon != null) {
                    cache.put("webxdc:icon:" + webxdcCid, icon);
                    activity.xmppConnectionService.updateConversationUi();
                }
            }).start();
        } else {
            viewHolder.image.setVisibility(View.VISIBLE);
            viewHolder.image.setImageDrawable(d);
        }
    }

    private void displayOpenableMessage(
            ViewHolder viewHolder, final Message message, final BubbleColor bubbleColor, final int type) {
        displayTextMessage(viewHolder, message, bubbleColor, type);
        toggleWhisperInfo(viewHolder, message, bubbleColor);
        viewHolder.image.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        viewHolder.download_button.setVisibility(View.VISIBLE);
        viewHolder.download_button.setText(
                activity.getString(
                        R.string.open_x_file,
                        UIHelper.getFileDescriptionString(activity, message)));
        final var attachment = Attachment.of(message);
        final @DrawableRes int imageResource = MediaAdapter.getImageDrawable(attachment);
        viewHolder.download_button.setIconResource(imageResource);
        viewHolder.download_button.setOnClickListener(v -> openDownloadable(message));
    }

    private void displayLocationMessage(
            ViewHolder viewHolder, final Message message, final BubbleColor bubbleColor, final int type) {
        displayTextMessage(viewHolder, message, bubbleColor, type);
        toggleWhisperInfo(viewHolder, message, bubbleColor);
        viewHolder.image.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        viewHolder.download_button.setVisibility(View.VISIBLE);
        viewHolder.download_button.setText(R.string.show_location);
        final var attachment = Attachment.of(message);
        final @DrawableRes int imageResource = MediaAdapter.getImageDrawable(attachment);
        viewHolder.download_button.setIconResource(imageResource);
        viewHolder.download_button.setOnClickListener(v -> showLocation(message));
    }

    private void displayAudioMessage(
            ViewHolder viewHolder, Message message, final BubbleColor bubbleColor, final int type) {
        displayTextMessage(viewHolder, message, bubbleColor, type);
        toggleWhisperInfo(viewHolder, message, bubbleColor);
        viewHolder.image.setVisibility(View.GONE);
        viewHolder.download_button.setVisibility(View.GONE);
        final RelativeLayout audioPlayer = viewHolder.audioPlayer;
        audioPlayer.setVisibility(View.VISIBLE);
        AudioPlayer.ViewHolder.get(audioPlayer).setBubbleColor(bubbleColor);
        this.audioPlayer.init(audioPlayer, message);
    }

    private void displayMediaPreviewMessage(
            ViewHolder viewHolder, final Message message, final BubbleColor bubbleColor, final int type) {
        displayTextMessage(viewHolder, message, bubbleColor, type);
        toggleWhisperInfo(viewHolder, message, bubbleColor);
        viewHolder.download_button.setVisibility(View.GONE);
        viewHolder.audioPlayer.setVisibility(View.GONE);
        viewHolder.image.setVisibility(View.VISIBLE);
        final FileParams params = message.getFileParams();
        imagePreviewLayout(params.width, params.height, viewHolder.image, message.getBody() != null && message.getBody().length() > 0);
        activity.loadBitmap(message, viewHolder.image);
        viewHolder.image.setOnClickListener(v -> openDownloadable(message));
    }

    private void imagePreviewLayout(int w, int h, ImageView image, boolean topMargin) {
        final float target = activity.getResources().getDimension(R.dimen.image_preview_width);
        final int scaledW;
        final int scaledH;
        if (Math.max(h, w) * metrics.density <= target) {
            scaledW = (int) (w * metrics.density);
            scaledH = (int) (h * metrics.density);
        } else if (Math.max(h, w) <= target) {
            scaledW = w;
            scaledH = h;
        } else if (w <= h) {
            scaledW = (int) (w / ((double) h / target));
            scaledH = (int) target;
        } else {
            scaledW = (int) target;
            scaledH = (int) (h / ((double) w / target));
        }
        final LinearLayout.LayoutParams layoutParams =
                new LinearLayout.LayoutParams(scaledW, scaledH);
        layoutParams.setMargins(0, topMargin ? (int) (metrics.density * 4) : 0, 0, (int) (metrics.density * 4));
        layoutParams.gravity = Gravity.CENTER;
        image.setLayoutParams(layoutParams);
    }

    private void toggleWhisperInfo(
            ViewHolder viewHolder, final Message message, final BubbleColor bubbleColor) {
        if (message.isPrivateMessage()) {
            final String privateMarker;
            if (message.getStatus() <= Message.STATUS_RECEIVED) {
                privateMarker = activity.getString(R.string.private_message);
            } else {
                Jid cp = message.getCounterpart();
                privateMarker =
                        activity.getString(
                                R.string.private_message_to,
                                Strings.nullToEmpty(cp == null ? null : cp.getResource()));
            }
            final SpannableString body = new SpannableString(privateMarker);
            body.setSpan(
                    new ForegroundColorSpan(
                            bubbleToOnSurfaceVariant(viewHolder.messageBody, bubbleColor)),
                    0,
                    privateMarker.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            body.setSpan(
                    new StyleSpan(Typeface.BOLD),
                    0,
                    privateMarker.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            viewHolder.messageBody.setText(body);
            viewHolder.messageBody.setVisibility(View.VISIBLE);
        } else {
            viewHolder.messageBody.setVisibility(View.GONE);
        }
    }

    private void loadMoreMessages(Conversation conversation) {
        conversation.setLastClearHistory(0, null);
        activity.xmppConnectionService.updateConversation(conversation);
        conversation.setHasMessagesLeftOnServer(true);
        conversation.setFirstMamReference(null);
        long timestamp = conversation.getLastMessageTransmitted().getTimestamp();
        if (timestamp == 0) {
            timestamp = System.currentTimeMillis();
        }
        conversation.messagesLoaded.set(true);
        MessageArchiveService.Query query =
                activity.xmppConnectionService
                        .getMessageArchiveService()
                        .query(conversation, new MamReference(0), timestamp, false);
        if (query != null) {
            Toast.makeText(activity, R.string.fetching_history_from_server, Toast.LENGTH_LONG)
                    .show();
        } else {
            Toast.makeText(
                            activity,
                            R.string.not_fetching_history_retention_period,
                            Toast.LENGTH_SHORT)
                    .show();
        }
    }

    @Override
    public View getView(final int position, View view, final @NonNull ViewGroup parent) {
        final Message message = getItem(position);
        final boolean omemoEncryption = message.getEncryption() == Message.ENCRYPTION_AXOLOTL;
        final boolean isInValidSession =
                message.isValidInSession() && (!omemoEncryption || message.isTrusted());
        final Conversational conversation = message.getConversation();
        final Account account = conversation.getAccount();
        final List<Element> commands = message.getCommands();
        final int type = getItemViewType(position);
        ViewHolder viewHolder;
        if (view == null) {
            viewHolder = new ViewHolder();
            switch (type) {
                case DATE_SEPARATOR:
                    view =
                            activity.getLayoutInflater()
                                    .inflate(R.layout.item_message_date_bubble, parent, false);
                    viewHolder.status_message = view.findViewById(R.id.message_body);
                    viewHolder.message_box = view.findViewById(R.id.message_box);
                    break;
                case RTP_SESSION:
                    view =
                            activity.getLayoutInflater()
                                    .inflate(R.layout.item_message_rtp_session, parent, false);
                    viewHolder.status_message = view.findViewById(R.id.message_body);
                    viewHolder.message_box = view.findViewById(R.id.message_box);
                    viewHolder.indicatorReceived = view.findViewById(R.id.indicator_received);
                    break;
                case SENT:
                    view = activity.getLayoutInflater().inflate(R.layout.item_message_sent, parent, false);
                    viewHolder.status_line = view.findViewById(R.id.status_line);
                    viewHolder.message_box_inner = view.findViewById(R.id.message_box_inner);
                    viewHolder.message_box = view.findViewById(R.id.message_box);
                    viewHolder.contact_picture = view.findViewById(R.id.message_photo);
                    viewHolder.download_button = view.findViewById(R.id.download_button);
                    viewHolder.indicator = view.findViewById(R.id.security_indicator);
                    viewHolder.edit_indicator = view.findViewById(R.id.edit_indicator);
                    viewHolder.image = view.findViewById(R.id.message_image);
                    viewHolder.messageBody = view.findViewById(R.id.message_body);
                    viewHolder.time = view.findViewById(R.id.message_time);
                    viewHolder.subject = view.findViewById(R.id.message_subject);
                    viewHolder.indicatorReceived = view.findViewById(R.id.indicator_received);
                    viewHolder.audioPlayer = view.findViewById(R.id.audio_player);
                    viewHolder.thread_identicon = view.findViewById(R.id.thread_identicon);
                    break;
                case RECEIVED:
                    view = activity.getLayoutInflater().inflate(R.layout.item_message_received, parent, false);
                    viewHolder.status_line = view.findViewById(R.id.status_line);
                    viewHolder.message_box_inner = view.findViewById(R.id.message_box_inner);
                    viewHolder.message_box = view.findViewById(R.id.message_box);
                    viewHolder.contact_picture = view.findViewById(R.id.message_photo);
                    viewHolder.download_button = view.findViewById(R.id.download_button);
                    viewHolder.indicator = view.findViewById(R.id.security_indicator);
                    viewHolder.edit_indicator = view.findViewById(R.id.edit_indicator);
                    viewHolder.image = view.findViewById(R.id.message_image);
                    viewHolder.messageBody = view.findViewById(R.id.message_body);
                    viewHolder.time = view.findViewById(R.id.message_time);
                    viewHolder.subject = view.findViewById(R.id.message_subject);
                    viewHolder.indicatorReceived = view.findViewById(R.id.indicator_received);
                    viewHolder.encryption = view.findViewById(R.id.message_encryption);
                    viewHolder.audioPlayer = view.findViewById(R.id.audio_player);
                    viewHolder.commands_list = view.findViewById(R.id.commands_list);
                    viewHolder.thread_identicon = view.findViewById(R.id.thread_identicon);
                    break;
                case STATUS:
                    view =
                            activity.getLayoutInflater()
                                    .inflate(R.layout.item_message_status, parent, false);
                    viewHolder.contact_picture = view.findViewById(R.id.message_photo);
                    viewHolder.status_message = view.findViewById(R.id.status_message);
                    viewHolder.load_more_messages = view.findViewById(R.id.load_more_messages);
                    break;
                default:
                    throw new AssertionError("Unknown view type");
            }
            view.setTag(viewHolder);
        } else {
            viewHolder = (ViewHolder) view.getTag();
            if (viewHolder == null) {
                return view;
            }
        }

        if (viewHolder.messageBody != null) {
            viewHolder.messageBody.setCustomSelectionActionModeCallback(new MessageTextActionModeCallback(this, viewHolder.messageBody));
        }

        if (viewHolder.thread_identicon != null) {
            viewHolder.thread_identicon.setVisibility(View.GONE);
            final Element thread = message.getThread();
            if (thread != null) {
                final String threadId = thread.getContent();
                if (threadId != null) {
                    viewHolder.thread_identicon.setVisibility(View.VISIBLE);
                    viewHolder.thread_identicon.setColor(UIHelper.getColorForName(threadId));
                    viewHolder.thread_identicon.setHash(UIHelper.identiconHash(threadId));
                }
            }
        }

        final var black = MaterialColors.getColor(view, com.google.android.material.R.attr.colorSecondaryContainer) == view.getContext().getColor(android.R.color.black);
        final boolean colorfulBackground = this.bubbleDesign.colorfulChatBubbles;
        final BubbleColor bubbleColor;
        if (type == RECEIVED) {
            if (isInValidSession) {
                bubbleColor = colorfulBackground  || black ? BubbleColor.SECONDARY : BubbleColor.SURFACE;
            } else {
                bubbleColor = BubbleColor.WARNING;
            }
        } else {
            if (!colorfulBackground && black) {
                bubbleColor = BubbleColor.SECONDARY;
            } else {
                bubbleColor = colorfulBackground ? BubbleColor.TERTIARY : BubbleColor.SURFACE_HIGH;
            }
        }

        if (type == DATE_SEPARATOR) {
            if (UIHelper.today(message.getTimeSent())) {
                viewHolder.status_message.setText(R.string.today);
            } else if (UIHelper.yesterday(message.getTimeSent())) {
                viewHolder.status_message.setText(R.string.yesterday);
            } else {
                viewHolder.status_message.setText(
                        DateUtils.formatDateTime(
                                activity,
                                message.getTimeSent(),
                                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR));
            }
            if (colorfulBackground) {
                setBackgroundTint(viewHolder.message_box, BubbleColor.PRIMARY);
                setTextColor(viewHolder.status_message, BubbleColor.PRIMARY);
            } else {
                setBackgroundTint(viewHolder.message_box, BubbleColor.SURFACE_HIGH);
                setTextColor(viewHolder.status_message, BubbleColor.SURFACE_HIGH);
            }
            return view;
        } else if (type == RTP_SESSION) {
            final boolean received = message.getStatus() <= Message.STATUS_RECEIVED;
            final RtpSessionStatus rtpSessionStatus = RtpSessionStatus.of(message.getBody());
            final long duration = rtpSessionStatus.duration;
            final String callTime = UIHelper.readableTimeDifferenceFull(activity, message.getTimeSent());
            if (received) {
                if (duration > 0) {
                    viewHolder.status_message.setText(
                            activity.getString(
                                    R.string.incoming_call_duration_timestamp,
                                    TimeFrameUtils.resolve(activity, duration),
                                    UIHelper.readableTimeDifferenceFull(
                                            activity, message.getTimeSent())));
                } else if (rtpSessionStatus.successful) {
                    viewHolder.status_message.setText(activity.getString(R.string.incoming_call_timestamp, callTime));
                } else {
                    viewHolder.status_message.setText(
                            activity.getString(
                                    R.string.missed_call_timestamp,
                                    UIHelper.readableTimeDifferenceFull(
                                            activity, message.getTimeSent())));
                }
            } else {
                if (duration > 0) {
                    viewHolder.status_message.setText(
                            activity.getString(
                                    R.string.outgoing_call_duration_timestamp,
                                    TimeFrameUtils.resolve(activity, duration),
                                    UIHelper.readableTimeDifferenceFull(
                                            activity, message.getTimeSent())));
                } else {
                    viewHolder.status_message.setText(
                            activity.getString(
                                    R.string.outgoing_call_timestamp,
                                    UIHelper.readableTimeDifferenceFull(
                                            activity, message.getTimeSent())));
                }
            }
            if (colorfulBackground) {
                setBackgroundTint(viewHolder.message_box, BubbleColor.SECONDARY);
                setTextColor(viewHolder.status_message, BubbleColor.SECONDARY);
                setImageTint(viewHolder.indicatorReceived, BubbleColor.SECONDARY);
            } else {
                setBackgroundTint(viewHolder.message_box, BubbleColor.SURFACE_HIGH);
                setTextColor(viewHolder.status_message, BubbleColor.SURFACE_HIGH);
                setImageTint(viewHolder.indicatorReceived, BubbleColor.SURFACE_HIGH);
            }
            viewHolder.indicatorReceived.setImageResource(
                    RtpSessionStatus.getDrawable(received, rtpSessionStatus.successful));
            return view;
        } else if (type == STATUS) {
            if ("LOAD_MORE".equals(message.getBody())) {
                viewHolder.status_message.setVisibility(View.GONE);
                viewHolder.contact_picture.setVisibility(View.GONE);
                viewHolder.load_more_messages.setVisibility(View.VISIBLE);
                viewHolder.load_more_messages.setOnClickListener(
                        v -> loadMoreMessages((Conversation) message.getConversation()));
            } else {
                viewHolder.status_message.setVisibility(View.VISIBLE);
                viewHolder.load_more_messages.setVisibility(View.GONE);
                viewHolder.status_message.setText(message.getBody());
                boolean showAvatar;
                if (conversation.getMode() == Conversation.MODE_SINGLE) {
                    showAvatar = true;
                    AvatarWorkerTask.loadAvatar(
                            message, viewHolder.contact_picture, R.dimen.avatar_on_status_message);
                } else if (message.getCounterpart() != null
                        || message.getTrueCounterpart() != null
                        || (message.getCounterparts() != null
                                && message.getCounterparts().size() > 0)) {
                    showAvatar = true;
                    AvatarWorkerTask.loadAvatar(
                            message, viewHolder.contact_picture, R.dimen.avatar_on_status_message);
                } else {
                    showAvatar = false;
                }
                if (showAvatar) {
                    viewHolder.contact_picture.setAlpha(0.5f);
                    viewHolder.contact_picture.setVisibility(View.VISIBLE);
                } else {
                    viewHolder.contact_picture.setVisibility(View.GONE);
                }
            }
            return view;
        } else {
            AvatarWorkerTask.loadAvatar(message, viewHolder.contact_picture, R.dimen.avatar);
        }

        resetClickListener(viewHolder.message_box, viewHolder.messageBody);

        viewHolder.message_box.setOnClickListener(v -> {
            if (MessageAdapter.this.mOnMessageBoxClickedListener != null) {
                MessageAdapter.this.mOnMessageBoxClickedListener
                        .onContactPictureClicked(message);
            }
        });
        SwipeDetector swipeDetector = new SwipeDetector((action) -> {
            if (action == SwipeDetector.Action.LR && MessageAdapter.this.mOnMessageBoxSwipedListener != null) {
                MessageAdapter.this.mOnMessageBoxSwipedListener.onContactPictureClicked(message);
            }
        });
        viewHolder.message_box.setOnTouchListener(swipeDetector);
        viewHolder.image.setOnTouchListener(swipeDetector);
        viewHolder.time.setOnTouchListener(swipeDetector);

        // Treat touch-up as click so we don't have to touch twice
        // (touch twice is because it's waiting to see if you double-touch for text selection)
        viewHolder.messageBody.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                if (MessageAdapter.this.mOnMessageBoxClickedListener != null) {
                    MessageAdapter.this.mOnMessageBoxClickedListener
                        .onContactPictureClicked(message);
                }
            }

            swipeDetector.onTouch(v, event);

            return false;
        });
        viewHolder.messageBody.setOnClickListener(v -> {
            if (MessageAdapter.this.mOnMessageBoxClickedListener != null) {
                MessageAdapter.this.mOnMessageBoxClickedListener
                        .onContactPictureClicked(message);
            }
        });
        viewHolder.contact_picture.setOnClickListener(v -> {
            if (MessageAdapter.this.mOnContactPictureClickedListener != null) {
                MessageAdapter.this.mOnContactPictureClickedListener
                        .onContactPictureClicked(message);
            }

        });
        viewHolder.contact_picture.setOnLongClickListener(v -> {
            if (MessageAdapter.this.mOnContactPictureLongClickedListener != null) {
                MessageAdapter.this.mOnContactPictureLongClickedListener
                        .onContactPictureLongClicked(v, message);
                return true;
            } else {
                return false;
            }
        });
        viewHolder.messageBody.setAccessibilityDelegate(null);

        boolean footerWrap = false;

        final Transferable transferable = message.getTransferable();
        final boolean unInitiatedButKnownSize = MessageUtils.unInitiatedButKnownSize(message);

        final boolean muted = message.getStatus() == Message.STATUS_RECEIVED && conversation.getMode() == Conversation.MODE_MULTI && activity.xmppConnectionService.isMucUserMuted(new MucOptions.User(null, conversation.getJid(), message.getOccupantId(), null, null));
        if (muted) {
            // Muted MUC participant
            displayInfoMessage(viewHolder, "Muted", bubbleColor);
        } else if (unInitiatedButKnownSize || message.isDeleted() || (transferable != null && transferable.getStatus() != Transferable.STATUS_UPLOADING)) {
            if (unInitiatedButKnownSize || (message.isDeleted() && message.getModerated() == null) || transferable != null && transferable.getStatus() == Transferable.STATUS_OFFER) {
                displayDownloadableMessage(viewHolder, message, activity.getString(R.string.download_x_file, UIHelper.getFileDescriptionString(activity, message)), bubbleColor, type);
            } else if (transferable != null && transferable.getStatus() == Transferable.STATUS_OFFER_CHECK_FILESIZE) {
                displayDownloadableMessage(viewHolder, message, activity.getString(R.string.check_x_filesize, UIHelper.getFileDescriptionString(activity, message)), bubbleColor, type);
            } else {
                displayInfoMessage(viewHolder, UIHelper.getMessagePreview(activity.xmppConnectionService, message).first, bubbleColor);
            }
        } else if (message.isFileOrImage()
                && message.getEncryption() != Message.ENCRYPTION_PGP
                && message.getEncryption() != Message.ENCRYPTION_DECRYPTION_FAILED) {
            if (message.getFileParams().width > 0 && message.getFileParams().height > 0) {
                displayMediaPreviewMessage(viewHolder, message, bubbleColor, type);
                if (!black && viewHolder.image.getLayoutParams().width > metrics.density * 110) {
                    footerWrap = true;
                }
            } else if (message.getFileParams().runtime > 0) {
                displayAudioMessage(viewHolder, message, bubbleColor, type);
            } else if ("application/xdc+zip".equals(message.getFileParams().getMediaType()) && message.getConversation() instanceof Conversation && message.getThread() != null && !message.getFileParams().getCids().isEmpty()) {
                displayWebxdcMessage(viewHolder, message, bubbleColor, type);
            } else {
                displayOpenableMessage(viewHolder, message, bubbleColor, type);
            }
        } else if (message.getEncryption() == Message.ENCRYPTION_PGP) {
            if (account.isPgpDecryptionServiceConnected()) {
                if (conversation instanceof Conversation
                        && !account.hasPendingPgpIntent((Conversation) conversation)) {
                    displayInfoMessage(
                            viewHolder,
                            activity.getString(R.string.message_decrypting),
                            bubbleColor);
                } else {
                    displayInfoMessage(
                            viewHolder, activity.getString(R.string.pgp_message), bubbleColor);
                }
            } else {
                displayInfoMessage(
                        viewHolder, activity.getString(R.string.install_openkeychain), bubbleColor);
                viewHolder.message_box.setOnClickListener(this::promptOpenKeychainInstall);
                viewHolder.messageBody.setOnClickListener(this::promptOpenKeychainInstall);
            }
        } else if (message.getEncryption() == Message.ENCRYPTION_DECRYPTION_FAILED) {
            displayInfoMessage(
                    viewHolder, activity.getString(R.string.decryption_failed), bubbleColor);
        } else if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE) {
            displayInfoMessage(
                    viewHolder,
                    activity.getString(R.string.not_encrypted_for_this_device),
                    bubbleColor);
        } else if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL_FAILED) {
            displayInfoMessage(
                    viewHolder, activity.getString(R.string.omemo_decryption_failed), bubbleColor);
        } else {
            if (message.isGeoUri()) {
                displayLocationMessage(viewHolder, message, bubbleColor, type);
            } else if (message.treatAsDownloadable()) {
                try {
                    final URI uri = message.getOob();
                    displayDownloadableMessage(viewHolder,
                            message,
                            activity.getString(
                                    R.string.check_x_filesize_on_host,
                                    UIHelper.getFileDescriptionString(activity, message),
                                    uri.getHost()),
                            bubbleColor, type);
                } catch (Exception e) {
                    displayDownloadableMessage(
                            viewHolder,
                            message,
                            activity.getString(
                                    R.string.check_x_filesize,
                                    UIHelper.getFileDescriptionString(activity, message)),
                            bubbleColor, type);
                }
            } else if (message.bodyIsOnlyEmojis() && message.getType() != Message.TYPE_PRIVATE) {
                displayEmojiMessage(viewHolder, getSpannableBody(message), bubbleColor);
            } else {
                displayTextMessage(viewHolder, message, bubbleColor, message.getType());
            }
        }

        viewHolder.message_box_inner.setMinimumWidth(footerWrap ? (int) (110 * metrics.density) : 0);
        LinearLayout.LayoutParams statusParams = (LinearLayout.LayoutParams) viewHolder.status_line.getLayoutParams();
        statusParams.width = footerWrap ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
        viewHolder.status_line.setLayoutParams(statusParams);

        setBackgroundTint(viewHolder.message_box, bubbleColor);
        setTextColor(viewHolder.messageBody, bubbleColor);

        if (type == RECEIVED) {
            if (!muted && commands != null && conversation instanceof Conversation) {
                CommandButtonAdapter adapter = new CommandButtonAdapter(activity);
                adapter.addAll(commands);
                viewHolder.commands_list.setAdapter(adapter);
                viewHolder.commands_list.setVisibility(View.VISIBLE);
                viewHolder.commands_list.setOnItemClickListener((p, v, pos, id) -> {
                    final Element command = adapter.getItem(pos);
                    activity.startCommand(conversation.getAccount(), command.getAttributeAsJid("jid"), command.getAttribute("node"));
                });
            } else {
                // It's unclear if we can set this to null...
                ListAdapter adapter = viewHolder.commands_list.getAdapter();
                if (adapter instanceof ArrayAdapter) {
                    ((ArrayAdapter<?>) adapter).clear();
                }
                viewHolder.commands_list.setVisibility(View.GONE);
                viewHolder.commands_list.setOnItemClickListener(null);
            }

            setTextColor(viewHolder.encryption, bubbleColor);

            if (isInValidSession) {
                viewHolder.encryption.setVisibility(View.GONE);
            } else {
                viewHolder.encryption.setVisibility(View.VISIBLE);
                if (omemoEncryption && !message.isTrusted()) {
                    viewHolder.encryption.setText(R.string.not_trusted);
                } else {
                    viewHolder.encryption.setText(
                            CryptoHelper.encryptionTypeToText(message.getEncryption()));
                }
            }
        }

        if (type == RECEIVED || type == SENT) {
            String subject = message.getSubject();
            if (subject == null && message.getThread() != null) {
                subject = ((Conversation) message.getConversation()).getThread(message.getThread().getContent()).getSubject();
            }
            if (muted || subject == null) {
                viewHolder.subject.setVisibility(View.GONE);
            } else {
                viewHolder.subject.setVisibility(View.VISIBLE);
                viewHolder.subject.setText(subject);
            }
        }

        /* TODO
        if (darkBackground) {
            if (viewHolder.subject != null) viewHolder.subject.setTextAppearance(getContext(), R.style.TextAppearance_Conversations_Caption_OnDark_Bold);
            if (viewHolder.encryption != null) viewHolder.encryption.setTextAppearance(getContext(), R.style.TextAppearance_Conversations_Caption_OnDark_Bold);
        } else {
            if (viewHolder.subject != null) viewHolder.subject.setTextAppearance(getContext(), R.style.TextAppearance_Conversations_Caption_Bold);
            if (viewHolder.encryption != null) viewHolder.encryption.setTextAppearance(getContext(), R.style.TextAppearance_Conversations_Caption_Bold);
        }
        */

        displayStatus(viewHolder, message, type, bubbleColor);

        viewHolder.messageBody.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void sendAccessibilityEvent(View host, int eventType) {
                super.sendAccessibilityEvent(host, eventType);
                if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
                    if (viewHolder.messageBody.hasSelection()) {
                        selectionUuid = message.getUuid();
                    } else if (message.getUuid() != null && message.getUuid().equals(selectionUuid)) {
                        selectionUuid = null;
                    }
                }
            }
        });

        return view;
    }

    private void promptOpenKeychainInstall(View view) {
        activity.showInstallPgpDialog();
    }

    public FileBackend getFileBackend() {
        return activity.xmppConnectionService.getFileBackend();
    }

    public void stopAudioPlayer() {
        audioPlayer.stop();
    }

    public void unregisterListenerInAudioPlayer() {
        audioPlayer.unregisterListener();
    }

    public void startStopPending() {
        audioPlayer.startStopPending();
    }

    public void openDownloadable(Message message) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(
                                activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            ConversationFragment.registerPendingMessage(activity, message);
            ActivityCompat.requestPermissions(
                    activity,
                    new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    ConversationsActivity.REQUEST_OPEN_MESSAGE);
            return;
        }
        final DownloadableFile file =
                activity.xmppConnectionService.getFileBackend().getFile(message);
        ViewUtil.view(activity, file);
    }

    private void showLocation(Message message) {
        for (Intent intent : GeoHelper.createGeoIntentsFromMessage(activity, message)) {
            if (intent.resolveActivity(getContext().getPackageManager()) != null) {
                getContext().startActivity(intent);
                return;
            }
        }
        Toast.makeText(
                        activity,
                        R.string.no_application_found_to_display_location,
                        Toast.LENGTH_SHORT)
                .show();
    }

    public void updatePreferences() {
        final AppSettings appSettings = new AppSettings(activity);
        this.bubbleDesign =
                new BubbleDesign(appSettings.isColorfulChatBubbles(), appSettings.isLargeFont());
    }

    public void setHighlightedTerm(List<String> terms) {
        this.highlightedTerm = terms == null ? null : StylingHelper.filterHighlightedWords(terms);
    }

    public interface OnContactPictureClicked {
        void onContactPictureClicked(Message message);
    }

    public interface OnContactPictureLongClicked {
        void onContactPictureLongClicked(View v, Message message);
    }

    public interface OnInlineImageLongClicked {
        boolean onInlineImageLongClicked(Cid cid);
    }

    private static void setBackgroundTint(final View view, final BubbleColor bubbleColor) {
        view.setBackgroundTintList(bubbleToColorStateList(view, bubbleColor));
    }

    private static ColorStateList bubbleToColorStateList(
            final View view, final BubbleColor bubbleColor) {
        final @AttrRes int colorAttributeResId =
                switch (bubbleColor) {
                    case SURFACE -> Activities.isNightMode(view.getContext())
                            ? com.google.android.material.R.attr.colorSurfaceContainerHigh
                            : com.google.android.material.R.attr.colorSurfaceContainerLow;
                    case SURFACE_HIGH -> Activities.isNightMode(view.getContext())
                            ? com.google.android.material.R.attr.colorSurfaceContainerHighest
                            : com.google.android.material.R.attr.colorSurfaceContainerHigh;
                    case PRIMARY -> com.google.android.material.R.attr.colorPrimaryContainer;
                    case SECONDARY -> com.google.android.material.R.attr.colorSecondaryContainer;
                    case TERTIARY -> com.google.android.material.R.attr.colorTertiaryContainer;
                    case WARNING -> com.google.android.material.R.attr.colorErrorContainer;
                };
        return ColorStateList.valueOf(MaterialColors.getColor(view, colorAttributeResId));
    }

    public static void setImageTint(final ImageView imageView, final BubbleColor bubbleColor) {
        ImageViewCompat.setImageTintList(
                imageView, bubbleToOnSurfaceColorStateList(imageView, bubbleColor));
    }

    public static void setImageTintError(final ImageView imageView) {
        ImageViewCompat.setImageTintList(
                imageView,
                ColorStateList.valueOf(
                        MaterialColors.getColor(
                                imageView, com.google.android.material.R.attr.colorError)));
    }

    public static void setTextColor(final TextView textView, final BubbleColor bubbleColor) {
        textView.setTextColor(bubbleToOnSurfaceColor(textView, bubbleColor));
    }

    private static void setTextSize(final TextView textView, final boolean largeFont) {
        if (largeFont) {
            textView.setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        } else {
            textView.setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        }
    }

    private static @ColorInt int bubbleToOnSurfaceVariant(
            final View view, final BubbleColor bubbleColor) {
        final @AttrRes int colorAttributeResId;
        if (bubbleColor == BubbleColor.SURFACE_HIGH || bubbleColor == BubbleColor.SURFACE) {
            colorAttributeResId = com.google.android.material.R.attr.colorOnSurfaceVariant;
        } else {
            colorAttributeResId = bubbleToOnSurface(bubbleColor);
        }
        return MaterialColors.getColor(view, colorAttributeResId);
    }

    private static @ColorInt int bubbleToOnSurfaceColor(
            final View view, final BubbleColor bubbleColor) {
        return MaterialColors.getColor(view, bubbleToOnSurface(bubbleColor));
    }

    public static ColorStateList bubbleToOnSurfaceColorStateList(
            final View view, final BubbleColor bubbleColor) {
        return ColorStateList.valueOf(bubbleToOnSurfaceColor(view, bubbleColor));
    }

    private static @AttrRes int bubbleToOnSurface(final BubbleColor bubbleColor) {
        return switch (bubbleColor) {
            case SURFACE, SURFACE_HIGH -> com.google.android.material.R.attr.colorOnSurface;
            case PRIMARY -> com.google.android.material.R.attr.colorOnPrimaryContainer;
            case SECONDARY -> com.google.android.material.R.attr.colorOnSecondaryContainer;
            case TERTIARY -> com.google.android.material.R.attr.colorOnTertiaryContainer;
            case WARNING -> com.google.android.material.R.attr.colorOnErrorContainer;
        };
    }

    public enum BubbleColor {
        SURFACE,
        SURFACE_HIGH,
        PRIMARY,
        SECONDARY,
        TERTIARY,
        WARNING
    }

    private static class BubbleDesign {
        public final boolean colorfulChatBubbles;
        public final boolean largeFont;

        private BubbleDesign(final boolean colorfulChatBubbles, final boolean largeFont) {
            this.colorfulChatBubbles = colorfulChatBubbles;
            this.largeFont = largeFont;
        }
    }

    private static class ViewHolder {

        public MaterialButton load_more_messages;
        public ImageView edit_indicator;
        public RelativeLayout audioPlayer;
        protected View status_line;
        protected LinearLayout message_box;
        protected View message_box_inner;
        protected MaterialButton download_button;
        protected ImageView image;
        protected ImageView indicator;
        protected ImageView indicatorReceived;
        protected TextView time;
        protected TextView subject;
        protected TextView messageBody;
        protected ImageView contact_picture;
        protected TextView status_message;
        protected TextView encryption;
        protected ListView commands_list;
        protected GithubIdenticonView thread_identicon;
    }

    class ThumbnailTask extends AsyncTask<DownloadableFile, Void, Drawable[]> {
        @Override
        protected Drawable[] doInBackground(DownloadableFile... params) {
            if (isCancelled()) return null;

            Drawable[] d = new Drawable[params.length];
            for (int i = 0; i < params.length; i++) {
                try {
                    d[i] = activity.xmppConnectionService.getFileBackend().getThumbnail(params[i], activity.getResources(), (int) (metrics.density * 288), false);
                } catch (final IOException e) {
                    d[i] = null;
                }
            }

            return d;
        }

        @Override
        protected void onPostExecute(final Drawable[] d) {
            if (isCancelled()) return;
            activity.xmppConnectionService.updateConversationUi();
        }
    }
}
