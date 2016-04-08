/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.alarmclock;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.TextClock;
import android.widget.TextView;

import com.android.deskclock.DeskClock;
import com.android.deskclock.LogUtils;
import com.android.deskclock.R;
import com.android.deskclock.Utils;
import com.android.deskclock.data.DataModel;

import java.util.Calendar;
import java.util.Locale;

import static android.app.AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED;
import static android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT;
import static android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH;
import static android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT;
import static android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH;
import static android.content.Intent.ACTION_DATE_CHANGED;
import static android.content.Intent.ACTION_LOCALE_CHANGED;
import static android.content.Intent.ACTION_SCREEN_ON;
import static android.content.Intent.ACTION_TIMEZONE_CHANGED;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.util.TypedValue.COMPLEX_UNIT_PX;
import static android.view.View.GONE;
import static android.view.View.MeasureSpec.UNSPECIFIED;
import static android.view.View.VISIBLE;
import static com.android.deskclock.alarms.AlarmStateManager.SYSTEM_ALARM_CHANGE_ACTION;
import static java.lang.Math.max;
import static java.lang.Math.round;

/**
 * <p>This provider produces a widget resembling one of the formats below.</p>
 *
 * If an alarm is scheduled to ring in the future:
 * <pre>
 *         12:59 AM
 * WED, FEB 3 ⏰ THU 9:30 AM
 * </pre>
 *
 * If no alarm is scheduled to ring in the future:
 * <pre>
 *         12:59 AM
 *        WED, FEB 3
 * </pre>
 *
 * This widget is scaling the font sizes to fit within the widget bounds chosen by the user without
 * any clipping. To do so it measures layouts offscreen using a range of font sizes in order to
 * choose optimal values.
 */
public class DigitalAppWidgetProvider extends AppWidgetProvider {

    private static final LogUtils.Logger LOGGER = new LogUtils.Logger("DigitalWidgetProvider");

    /** A custom font containing a special glyph that draws a clock icon with proper drop shadow. */
    private static Typeface sAlarmIconTypeface;

    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
        LOGGER.i("Digital Widget processing action %s", intent.getAction());
        super.onReceive(context, intent);

        final AppWidgetManager wm = AppWidgetManager.getInstance(context);
        if (wm == null) {
            return;
        }

        final ComponentName provider = new ComponentName(context, getClass());
        final int[] widgetIds = wm.getAppWidgetIds(provider);

        switch (intent.getAction()) {
            case ACTION_SCREEN_ON:
            case ACTION_DATE_CHANGED:
            case ACTION_LOCALE_CHANGED:
            case ACTION_TIMEZONE_CHANGED:
            case SYSTEM_ALARM_CHANGE_ACTION:
            case ACTION_NEXT_ALARM_CLOCK_CHANGED:
                for (int widgetId : widgetIds) {
                    updateWidget(context, wm, widgetId, wm.getAppWidgetOptions(widgetId));
                }
        }

        final DataModel dm = DataModel.getDataModel();
        dm.updateWidgetCount(getClass(), widgetIds.length, R.string.category_digital_widget);
    }

    /**
     * Called when widgets must provide remote views.
     */
    @Override
    public void onUpdate(Context context, AppWidgetManager wm, int[] widgetIds) {
        super.onUpdate(context, wm, widgetIds);

        for (int widgetId : widgetIds) {
            updateWidget(context, wm, widgetId, wm.getAppWidgetOptions(widgetId));
        }
    }

    /**
     * Called when the app widget changes sizes.
     */
    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager wm, int widgetId,
            Bundle options) {
        super.onAppWidgetOptionsChanged(context, wm, widgetId, options);

        // scale the fonts of the clock to fit inside the new size
        updateWidget(context, AppWidgetManager.getInstance(context), widgetId, options);
    }

    /**
     * Compute optimal font and icon sizes offscreen using the last known widget size and apply them
     * to the widget.
     */
    private static void updateWidget(Context context, AppWidgetManager wm, int widgetId,
            Bundle options) {
        // Create a remote view for the digital clock.
        final String packageName = context.getPackageName();
        final RemoteViews widget = new RemoteViews(packageName, R.layout.digital_widget);

        // Tapping on the widget opens the app (if not on the lock screen).
        if (Utils.isWidgetClickable(wm, widgetId)) {
            final Intent openApp = new Intent(context, DeskClock.class);
            final PendingIntent pi = PendingIntent.getActivity(context, 0, openApp, 0);
            widget.setOnClickPendingIntent(R.id.digital_widget, pi);
        }

        // Configure child views of the remote view.
        widget.setCharSequence(R.id.clock, "setFormat12Hour", get12HourFormat(context));
        widget.setCharSequence(R.id.clock, "setFormat24Hour", get24ModeFormat(context));

        final CharSequence dateFormat = getDateFormat(context);
        widget.setCharSequence(R.id.date, "setFormat12Hour", dateFormat);
        widget.setCharSequence(R.id.date, "setFormat24Hour", dateFormat);

        final String nextAlarmTime = Utils.getNextAlarm(context);
        if (TextUtils.isEmpty(nextAlarmTime)) {
            widget.setViewVisibility(R.id.nextAlarm, GONE);
            widget.setViewVisibility(R.id.nextAlarmIcon, GONE);
        } else  {
            widget.setTextViewText(R.id.nextAlarm, nextAlarmTime);
            widget.setViewVisibility(R.id.nextAlarm, VISIBLE);
            widget.setViewVisibility(R.id.nextAlarmIcon, VISIBLE);
        }

        if (options == null) {
            options = wm.getAppWidgetOptions(widgetId);
        }

        // Fetch the widget size selected by the user.
        final Resources resources = context.getResources();
        final float density = resources.getDisplayMetrics().density;
        final int minWidthPx = (int) (density * options.getInt(OPTION_APPWIDGET_MIN_WIDTH));
        final int minHeightPx = (int) (density * options.getInt(OPTION_APPWIDGET_MIN_HEIGHT));
        final int maxWidthPx = (int) (density * options.getInt(OPTION_APPWIDGET_MAX_WIDTH));
        final int maxHeightPx = (int) (density * options.getInt(OPTION_APPWIDGET_MAX_HEIGHT));
        final boolean portrait = resources.getConfiguration().orientation == ORIENTATION_PORTRAIT;
        final int targetWidthPx = portrait ? minWidthPx : maxWidthPx;
        final int targetHeightPx = portrait ? maxHeightPx : minHeightPx;
        final int largestClockFontSizePx =
                resources.getDimensionPixelSize(R.dimen.widget_max_clock_font_size);

        // Create a size template that describes the widget bounds.
        final Sizes template = new Sizes(targetWidthPx, targetHeightPx, largestClockFontSizePx);

        // Compute optimal font sizes and icon sizes to fit within the widget bounds.
        final Sizes sizes = optimizeSizes(context, template, nextAlarmTime);
        if (LOGGER.isVerboseLoggable()) {
            LOGGER.v(sizes.toString());
        }

        // Apply the computed sizes to the remote views.
        widget.setImageViewBitmap(R.id.nextAlarmIcon, sizes.mIconBitmap);
        widget.setTextViewTextSize(R.id.date, COMPLEX_UNIT_PX, sizes.mFontSizePx);
        widget.setTextViewTextSize(R.id.nextAlarm, COMPLEX_UNIT_PX, sizes.mFontSizePx);
        widget.setTextViewTextSize(R.id.clock, COMPLEX_UNIT_PX, sizes.mClockFontSizePx);
        wm.updateAppWidget(widgetId, widget);
    }

    /**
     * Inflate an offscreen copy of the widget views. Binary search through the range of sizes until
     * the optimal sizes that fit within the widget bounds are located.
     */
    private static Sizes optimizeSizes(Context context, Sizes template, String nextAlarmTime) {
        // Inflate a test layout to compute sizes at different font sizes.
        final LayoutInflater inflater = LayoutInflater.from(context);
        @SuppressLint("InflateParams")
        final View sizer = inflater.inflate(R.layout.digital_widget_sizer, null /* root */);

        // Configure the clock to display the widest time string.
        final TextClock clock = (TextClock) sizer.findViewById(R.id.clock);
        clock.setFormat12Hour(get12HourFormat(context));
        clock.setFormat24Hour(get24ModeFormat(context));

        // Configure the date to display the current date string.
        final CharSequence dateFormat = getDateFormat(context);
        final TextClock date = (TextClock) sizer.findViewById(R.id.date);
        date.setFormat12Hour(dateFormat);
        date.setFormat24Hour(dateFormat);

        // Configure the next alarm views to display the next alarm time or be gone.
        final TextView nextAlarmIcon = (TextView) sizer.findViewById(R.id.nextAlarmIcon);
        final TextView nextAlarm = (TextView) sizer.findViewById(R.id.nextAlarm);
        if (TextUtils.isEmpty(nextAlarmTime)) {
            nextAlarm.setVisibility(GONE);
            nextAlarmIcon.setVisibility(GONE);
        } else  {
            nextAlarm.setText(nextAlarmTime);
            nextAlarm.setVisibility(VISIBLE);
            nextAlarmIcon.setVisibility(VISIBLE);
            nextAlarmIcon.setTypeface(getAlarmIconTypeface(context));
        }

        // Measure the widget at the largest possible size.
        Sizes high = measure(template, template.getLargestClockFontSizePx(), sizer);
        if (!high.hasViolations()) {
            return high;
        }

        // Measure the widget at the smallest possible size.
        Sizes low = measure(template, template.getSmallestClockFontSizePx(), sizer);
        if (low.hasViolations()) {
            return low;
        }

        // Binary search between the smallest and largest sizes until an optimum size is found.
        while (low.getClockFontSizePx() != high.getClockFontSizePx()) {
            final int midFontSize = (low.getClockFontSizePx() + high.getClockFontSizePx()) / 2;
            if (midFontSize == low.getClockFontSizePx()) {
                return low;
            }

            final Sizes midSize = measure(template, midFontSize, sizer);
            if (midSize.hasViolations()) {
                high = midSize;
            } else {
                low = midSize;
            }
        }

        return low;
    }

    /**
     * Compute all font and icon sizes based on the given {@code clockFontSize} and apply them to
     * the offscreen {@code sizer} view. Measure the {@code sizer} view and return the resulting
     * size measurements.
     */
    private static Sizes measure(Sizes template, int clockFontSize, View sizer) {
        // Create a copy of the given template sizes.
        final Sizes measuredSizes = template.newSize();

        // Configure the clock to display the widest time string.
        final TextClock date = (TextClock) sizer.findViewById(R.id.date);
        final TextClock clock = (TextClock) sizer.findViewById(R.id.clock);
        final TextView nextAlarm = (TextView) sizer.findViewById(R.id.nextAlarm);
        final TextView nextAlarmIcon = (TextView) sizer.findViewById(R.id.nextAlarmIcon);

        // Adjust the font sizes.
        measuredSizes.setClockFontSizePx(clockFontSize);
        clock.setText(getLongestTimeString(clock));
        clock.setTextSize(COMPLEX_UNIT_PX, measuredSizes.mClockFontSizePx);
        date.setTextSize(COMPLEX_UNIT_PX, measuredSizes.mFontSizePx);
        nextAlarm.setTextSize(COMPLEX_UNIT_PX, measuredSizes.mFontSizePx);
        nextAlarmIcon.setTextSize(COMPLEX_UNIT_PX, measuredSizes.mIconFontSizePx);
        nextAlarmIcon.setPadding(measuredSizes.mIconPaddingPx, 0, measuredSizes.mIconPaddingPx, 0);

        // Measure and layout the sizer.
        final int widthSize = View.MeasureSpec.getSize(measuredSizes.mTargetWidthPx);
        final int heightSize = View.MeasureSpec.getSize(measuredSizes.mTargetHeightPx);
        final int widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(widthSize, UNSPECIFIED);
        final int heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(heightSize, UNSPECIFIED);
        sizer.measure(widthMeasureSpec, heightMeasureSpec);
        sizer.layout(0, 0, sizer.getMeasuredWidth(), sizer.getMeasuredHeight());

        // Copy the measurements into the result object.
        measuredSizes.mMeasuredWidthPx = sizer.getMeasuredWidth();
        measuredSizes.mMeasuredHeightPx = sizer.getMeasuredHeight();
        measuredSizes.mMeasuredTextClockWidthPx = clock.getMeasuredWidth();
        measuredSizes.mMeasuredTextClockHeightPx = clock.getMeasuredHeight();

        // If an alarm icon is required, generate one from the TextView with the special font.
        if (nextAlarmIcon.getVisibility() == VISIBLE) {
            measuredSizes.mIconBitmap = Utils.createBitmap(nextAlarmIcon);
        }

        return measuredSizes;
    }

    /**
     * @return "11:59" or "23:59" in the current locale
     */
    private static CharSequence getLongestTimeString(TextClock clock) {
        final CharSequence format = clock.is24HourModeEnabled()
                ? clock.getFormat24Hour()
                : clock.getFormat12Hour();
        final Calendar longestPMTime = Calendar.getInstance();
        longestPMTime.set(0, 0, 0, 23, 59);
        return DateFormat.format(format, longestPMTime);
    }

    /**
     * @return the locale-specific date pattern
     */
    private static String getDateFormat(Context context) {
        final Locale locale = Locale.getDefault();
        final String skeleton = context.getString(R.string.abbrev_wday_month_day_no_year);
        return DateFormat.getBestDateTimePattern(locale, skeleton);
    }

    /**
     * @return the 12-hour time format that matches the lock screen
     */
    private static CharSequence get12HourFormat(Context context) {
        return context.getString(R.string.lock_screen_12_hour_format);
    }

    /**
     * @return the 24-hour time format that matches the lock screen
     */
    private static CharSequence get24ModeFormat(Context context) {
        return context.getString(R.string.lock_screen_24_hour_format);
    }

    /**
     * This special font ensures that the drop shadow under the alarm clock icon matches the drop
     * shadow under normal text.
     *
     * @return a special font containing a glyph that draws an alarm clock
     */
    private static Typeface getAlarmIconTypeface(Context context) {
        if (sAlarmIconTypeface == null) {
            sAlarmIconTypeface = Typeface.createFromAsset(context.getAssets(), "fonts/clock.ttf");
        }
        return sAlarmIconTypeface;
    }

    /**
     * This class stores the target size of the widget as well as the measured size using a given
     * clock font size. All other fonts and icons are scaled proportional to the clock font.
     */
    private static final class Sizes {

        private final int mTargetWidthPx;
        private final int mTargetHeightPx;
        private final int mLargestClockFontSizePx;
        private final int mSmallestClockFontSizePx;
        private Bitmap mIconBitmap;

        private int mMeasuredWidthPx;
        private int mMeasuredHeightPx;
        private int mMeasuredTextClockWidthPx;
        private int mMeasuredTextClockHeightPx;

        /** The size of the font to use on the date / next alarm time fields. */
        private int mFontSizePx;

        /** The size of the font to use on the clock field. */
        private int mClockFontSizePx;

        private int mIconFontSizePx;
        private int mIconPaddingPx;

        private Sizes(int targetWidthPx, int targetHeightPx, int largestClockFontSizePx) {
            mTargetWidthPx = targetWidthPx;
            mTargetHeightPx = targetHeightPx;
            mLargestClockFontSizePx = largestClockFontSizePx;
            mSmallestClockFontSizePx = 1;
        }

        private int getLargestClockFontSizePx() { return mLargestClockFontSizePx; }
        private int getSmallestClockFontSizePx() { return mSmallestClockFontSizePx; }
        private int getClockFontSizePx() { return mClockFontSizePx; }
        private void setClockFontSizePx(int clockFontSizePx) {
            mClockFontSizePx = clockFontSizePx;
            mFontSizePx = max(1, round(clockFontSizePx / 7.5f));
            mIconFontSizePx = (int) (mFontSizePx * 1.4f);
            mIconPaddingPx = mFontSizePx / 3;
        }

        private boolean hasViolations() {
            return mMeasuredWidthPx > mTargetWidthPx || mMeasuredHeightPx > mTargetHeightPx;
        }

        private Sizes newSize() {
            return new Sizes(mTargetWidthPx, mTargetHeightPx, mLargestClockFontSizePx);
        }

        @Override
        public String toString() {
            final StringBuilder builder = new StringBuilder(1000);
            builder.append("\n");
            append(builder, "Target dimensions: %dpx x %dpx\n", mTargetWidthPx, mTargetHeightPx);
            append(builder, "Last valid widget container measurement: %dpx x %dpx\n",
                    mMeasuredWidthPx, mMeasuredHeightPx);
            append(builder, "Last text clock measurement: %dpx x %dpx\n",
                    mMeasuredTextClockWidthPx, mMeasuredTextClockHeightPx);
            if (mMeasuredWidthPx > mTargetWidthPx) {
                append(builder, "Measured width %dpx exceeded widget width %dpx\n",
                        mMeasuredWidthPx, mTargetWidthPx);
            }
            if (mMeasuredHeightPx > mTargetHeightPx) {
                append(builder, "Measured height %dpx exceeded widget height %dpx\n",
                        mMeasuredHeightPx, mTargetHeightPx);
            }
            append(builder, "Clock font: %dpx\n", mClockFontSizePx);
            return builder.toString();
        }

        private static void append(StringBuilder builder, String format, Object... args) {
            builder.append(String.format(Locale.ENGLISH, format, args));
        }
    }
}