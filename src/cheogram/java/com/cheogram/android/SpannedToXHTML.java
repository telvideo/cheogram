package com.cheogram.android;

import android.app.Application;
import android.graphics.Typeface;
import android.text.Spanned;
import android.text.SpannableStringBuilder;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.AlignmentSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.BulletSpan;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.text.style.ParagraphStyle;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.SubscriptSpan;
import android.text.style.SuggestionSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.text.style.UnderlineSpan;
import android.view.inputmethod.BaseInputConnection;

import io.ipfs.cid.Cid;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.TextNode;

public class SpannedToXHTML {
	public static Element append(Element out, Spanned text) {
		SpannableStringBuilder newText = new SpannableStringBuilder(text);
		SuggestionSpan[] spans = newText.getSpans(0, newText.length(), SuggestionSpan.class);
		for (SuggestionSpan span : spans) {
			newText.removeSpan(span);
		}
		BaseInputConnection.removeComposingSpans(newText);
		withinParagraph(out, newText, 0, newText.length());
		return out;
	}

	private static void withinParagraph(Element outer, Spanned text, int start, int end) {
		int next;
		outer:
		for (int i = start; i < end; i = next) {
			Element out = outer;
			next = text.nextSpanTransition(i, end, CharacterStyle.class);
			CharacterStyle[] style = text.getSpans(i, next, CharacterStyle.class);
			for (int j = 0; j < style.length; j++) {
				if (style[j] instanceof StyleSpan) {
					int s = ((StyleSpan) style[j]).getStyle();
					if ((s & Typeface.BOLD) != 0) {
						out = out.addChild("b");
					}
					if ((s & Typeface.ITALIC) != 0) {
						out = out.addChild("i");
					}
				}
				if (style[j] instanceof TypefaceSpan) {
					String s = ((TypefaceSpan) style[j]).getFamily();
					if ("monospace".equals(s)) {
						out = out.addChild("tt");
					}
				}
				if (style[j] instanceof SuperscriptSpan) {
					out = out.addChild("sup");
				}
				if (style[j] instanceof SubscriptSpan) {
					out = out.addChild("sub");
				}
				if (style[j] instanceof UnderlineSpan) {
					out = out.addChild("u");
				}
				if (style[j] instanceof StrikethroughSpan) {
					out = out.addChild("span");
					out.setAttribute("style", "text-decoration:line-through;");
				}
				if (style[j] instanceof URLSpan) {
					out = out.addChild("a");
					out.setAttribute("href", ((URLSpan) style[j]).getURL());
				}
				if (style[j] instanceof ImageSpan) {
					String source = ((ImageSpan) style[j]).getSource();
					if (source != null && source.length() > 0 && source.charAt(0) == 'z') {
						try {
							source = BobTransfer.uri(Cid.decode(source)).toString();
						} catch (final Exception e) { }
					}
					out = out.addChild("img");
					out.setAttribute("src", source);
					out.setAttribute("alt", text.subSequence(i, next).toString());
					continue outer;
				}
				if (style[j] instanceof AbsoluteSizeSpan) {
					try {
						AbsoluteSizeSpan s = ((AbsoluteSizeSpan) style[j]);
						float sizeDip = s.getSize();
						if (!s.getDip()) {
							Class activityThreadClass = Class.forName("android.app.ActivityThread");
							Application application = (Application) activityThreadClass.getMethod("currentApplication").invoke(null);
							sizeDip /= application.getResources().getDisplayMetrics().density;
						}
						// px in CSS is the equivalance of dip in Android
						out = out.addChild("span");
						out.setAttribute("style", String.format("font-size:%.0fpx;", sizeDip));
					} catch (final Exception e) { }
				}
				if (style[j] instanceof RelativeSizeSpan) {
					float sizeEm = ((RelativeSizeSpan) style[j]).getSizeChange();
					out = out.addChild("span");
					out.setAttribute("style", String.format("font-size:%.0fem;", sizeEm));
				}
				if (style[j] instanceof ForegroundColorSpan) {
					int color = ((ForegroundColorSpan) style[j]).getForegroundColor();
					out = out.addChild("span");
					out.setAttribute("style", String.format("color:#%06X;", 0xFFFFFF & color));
				}
				if (style[j] instanceof BackgroundColorSpan) {
					int color = ((BackgroundColorSpan) style[j]).getBackgroundColor();
					out = out.addChild("span");
					out.setAttribute("style", String.format("background-color:#%06X;", 0xFFFFFF & color));
				}
			}
			String content = text.subSequence(i, next).toString();
			for (int c = 0; c < content.length(); c++) {
				if (content.charAt(c) == '\n') {
					out.addChild("br");
				} else {
					out.addChild(new TextNode("" + content.charAt(c)));
				}
			}
		}
	}
}
