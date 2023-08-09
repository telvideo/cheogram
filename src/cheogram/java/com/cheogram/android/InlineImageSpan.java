package com.cheogram.android;

import android.graphics.Paint;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.style.ImageSpan;

public class InlineImageSpan extends ImageSpan {
	private final Paint.FontMetricsInt mTmpFontMetrics = new Paint.FontMetricsInt();
	private final float dHeight;
	private final float dWidth;

	public InlineImageSpan(Drawable d, final String source) {
		super(d.getConstantState() == null ? d : d.getConstantState().newDrawable(), source);
		dHeight = d.getIntrinsicHeight();
		dWidth = d.getIntrinsicWidth();
		if (Build.VERSION.SDK_INT >= 28 && d instanceof AnimatedImageDrawable) {
			((AnimatedImageDrawable) getDrawable()).start();
		}
	}

	@Override
	public int getSize(final Paint paint, final CharSequence text, final int start, final int end, final Paint.FontMetricsInt fm) {
		paint.getFontMetricsInt(mTmpFontMetrics);
		final int fontHeight = Math.abs(mTmpFontMetrics.descent - mTmpFontMetrics.ascent);
		float mRatio = fontHeight * 1.0f / dHeight;
		int mHeight = (short) (dHeight * mRatio);
		int mWidth = (short) (dWidth * mRatio);
		getDrawable().setBounds(0, 0, mWidth, mHeight);
		if (fm != null) {
			fm.ascent = mTmpFontMetrics.ascent;
			fm.descent = mTmpFontMetrics.descent;
			fm.top = mTmpFontMetrics.top;
			fm.bottom = mTmpFontMetrics.bottom;
		}
		return mWidth;
	}
}
