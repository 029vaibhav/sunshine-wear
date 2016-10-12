/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFaceService extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;
    private static final String LOG_TAG = SunshineWatchFaceService.class.getSimpleName();

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFaceService.Engine> weakReference;

        public EngineHandler(SunshineWatchFaceService.Engine reference) {
            weakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFaceService.Engine engine = weakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

        private static final String WEATHER_PATH = "/sunshine";
        private static final String HIGH_TEMPERATURE = "highTemp";
        private static final String LOW_TEMPERATURE = "lowTemp";
        private static final String WEATHER_ID = "weatherID";

        final Handler updateTimeHandler = new EngineHandler(this);

        boolean registeredTimeZoneReceiver = false;
        Paint backgroundPaint;
        Paint textTimePaint;
        Paint textDatePaint;
        Paint textDateAmbientPaint;
        Paint textTempHighPaint;
        Paint textTempLowPaint;
        Paint textTempLowAmbientPaint;
        private Calendar calendar;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                calendar.setTimeZone(TimeZone.getDefault());
                long now = System.currentTimeMillis();
                calendar.setTimeInMillis(now);
            }
        };

        GoogleApiClient googleApiClient = new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        float timeYOffset;
        float dateYOffset;
        float dividerYOffset;
        float weatherYOffset;

        Bitmap weatherIcon;
        String weatherHigh;
        String weatherLow;


        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        boolean mAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFaceService.this.getResources();
            timeYOffset = resources.getDimension(R.dimen.digital_time_y_offset);
            dateYOffset = resources.getDimension(R.dimen.digital_date_y_offset);
            dividerYOffset = resources.getDimension(R.dimen.digital_divider_y_offset);
            weatherYOffset = resources.getDimension(R.dimen.digital_weather_y_offset);

            backgroundPaint = new Paint();
            backgroundPaint.setColor(resources.getColor(R.color.primary_light));

            textTimePaint = createTextPaint(Color.WHITE);
            textDatePaint = createTextPaint(resources.getColor(R.color.background2));
            textDateAmbientPaint = createTextPaint(Color.WHITE);
            textTempHighPaint = createTextPaint(Color.WHITE);
            textTempLowPaint = createTextPaint(resources.getColor(R.color.primary_lighter));
            textTempLowAmbientPaint = createTextPaint(Color.WHITE);

            calendar = Calendar.getInstance();

        }

        @Override
        public void onDestroy() {
            updateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                if (!googleApiClient.isConnected())
                    googleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                calendar.setTimeZone(TimeZone.getDefault());
                long now = System.currentTimeMillis();
                calendar.setTimeInMillis(now);
            } else {
                unregisterReceiver();
                if (googleApiClient != null && googleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(googleApiClient, this);
                    googleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (registeredTimeZoneReceiver) {
                return;
            }
            registeredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!registeredTimeZoneReceiver) {
                return;
            }
            registeredTimeZoneReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFaceService.this.getResources();
            boolean isRound = insets.isRound();

            dateYOffset = resources.getDimension(isRound
                    ? R.dimen.digital_date_y_offset_round : R.dimen.digital_date_y_offset);
            dividerYOffset = resources.getDimension(isRound
                    ? R.dimen.digital_divider_y_offset_round : R.dimen.digital_divider_y_offset);
            weatherYOffset = resources.getDimension(isRound
                    ? R.dimen.digital_weather_y_offset_round : R.dimen.digital_weather_y_offset);

            float timeTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_time_text_size_round : R.dimen.digital_time_text_size);
            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_date_text_size_round : R.dimen.digital_date_text_size);
            float tempTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_temp_text_size_round : R.dimen.digital_temp_text_size);

            textTimePaint.setTextSize(timeTextSize);
            textDatePaint.setTextSize(dateTextSize);
            textDateAmbientPaint.setTextSize(dateTextSize);
            textTempHighPaint.setTextSize(tempTextSize);
            textTempLowAmbientPaint.setTextSize(tempTextSize);
            textTempLowPaint.setTextSize(tempTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    textTimePaint.setAntiAlias(!inAmbientMode);
                    textDatePaint.setAntiAlias(!inAmbientMode);
                    textDateAmbientPaint.setAntiAlias(!inAmbientMode);
                    textTempHighPaint.setAntiAlias(!inAmbientMode);
                    textTempLowAmbientPaint.setAntiAlias(!inAmbientMode);
                    textTempLowPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (mAmbient) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), backgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            calendar.setTimeInMillis(now);

            boolean is24Hour = DateFormat.is24HourFormat(SunshineWatchFaceService.this);

            int minute = calendar.get(Calendar.MINUTE);
            int second = calendar.get(Calendar.SECOND);
            int am_pm = calendar.get(Calendar.AM_PM);

            String timeText;
            if (is24Hour) {
                int hour = calendar.get(Calendar.HOUR_OF_DAY);
                timeText = mAmbient
                        ? String.format("%02d:%02d", hour, minute)
                        : String.format("%02d:%02d:%02d", hour, minute, second);
            } else {
                int hour = calendar.get(Calendar.HOUR);
                if (hour == 0) {
                    hour = 12;
                }

                String amPmText = Utility.getAmPmString(getResources(), am_pm);

                timeText = mAmbient
                        ? String.format("%d:%02d %s", hour, minute, amPmText)
                        : String.format("%d:%02d:%02d %s", hour, minute, second, amPmText);
            }

            float xOffsetTime = textTimePaint.measureText(timeText) / 2;
            canvas.drawText(timeText, bounds.centerX() - xOffsetTime, timeYOffset, textTimePaint);

            Paint datePaint = mAmbient ? textDateAmbientPaint : textDatePaint;

            String dayOfWeekString = Utility.getDayOfWeekString(getResources(), calendar.get(Calendar.DAY_OF_WEEK));
            String monthOfYearString = Utility.getMonthOfYearString(getResources(), calendar.get(Calendar.MONTH));

            int dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH);
            int year = calendar.get(Calendar.YEAR);

            String dateText = String.format("%s, %s %d %d", dayOfWeekString, monthOfYearString, dayOfMonth, year);
            float xOffsetDate = datePaint.measureText(dateText) / 2;
            canvas.drawText(dateText, bounds.centerX() - xOffsetDate, dateYOffset, datePaint);

            if (weatherHigh != null && weatherLow != null) {
                // Draw a line to separate date and time from weather elements
                canvas.drawLine(bounds.centerX() - 20, dividerYOffset, bounds.centerX() + 20, dividerYOffset, datePaint);

                float highTextLen = textTempHighPaint.measureText(weatherHigh);

                if (mAmbient) {
                    float lowTextLen = textTempLowAmbientPaint.measureText(weatherLow);
                    float xOffset = bounds.centerX() - ((highTextLen + lowTextLen + 20) / 2);
                    canvas.drawText(weatherHigh, xOffset, weatherYOffset, textTempHighPaint);
                    canvas.drawText(weatherLow, xOffset + highTextLen + 20, weatherYOffset, textTempLowAmbientPaint);
                } else {
                    float xOffset = bounds.centerX() - (highTextLen / 2);
                    canvas.drawText(weatherHigh, xOffset, weatherYOffset, textTempHighPaint);
                    canvas.drawText(weatherLow, xOffset + highTextLen + 20, weatherYOffset, textTempLowPaint);
                    float iconXOffset = bounds.centerX() - ((highTextLen / 2) + weatherIcon.getWidth() + 30);
                    canvas.drawBitmap(weatherIcon, iconXOffset, weatherYOffset - weatherIcon.getHeight(), null);
                }
            }

        }

        /**
         * Starts the {@link #updateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            updateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                updateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #updateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                updateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(Bundle bundle) {
            Log.d(LOG_TAG, "connected");
            Wearable.DataApi.addListener(googleApiClient, Engine.this);
        }


        @Override
        public void onConnectionSuspended(int cause) {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "onConnectionSuspended: " + cause);
            }
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "onConnectionFailed: " + connectionResult);
            }
        }


        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            Log.d(LOG_TAG, "data_changed_called");
            for (DataEvent dataEvent : dataEvents) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem dataItem = dataEvent.getDataItem();
                    Log.d(LOG_TAG, "data_items " + dataItem.toString());
                    if (dataItem.getUri().getPath().compareTo(WEATHER_PATH) == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();
                        setWeatherData(dataMap.getString(HIGH_TEMPERATURE),
                                dataMap.getString(LOW_TEMPERATURE), dataMap.getInt(WEATHER_ID));
                        invalidate();
                    }
                }
            }
        }

        private void setWeatherData(String highTemperature, String lowTemperature, int weatherCondition) {
            this.weatherHigh = highTemperature;
            this.weatherLow = lowTemperature;

            Log.d("high and low", weatherHigh + "" + weatherLow);

            Drawable b = getResources().getDrawable(Utility.getIconResourceForWeatherCondition(weatherCondition), getTheme());
            Bitmap icon = ((BitmapDrawable) b).getBitmap();
            float scaledWidth = (textTempHighPaint.getTextSize() / icon.getHeight()) * icon.getWidth();
            this.weatherIcon = Bitmap.createScaledBitmap(icon, (int) scaledWidth, (int) textTempHighPaint.getTextSize(), true);
        }
    }


}

