package com.example.android.sunshine.app.sync;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import static com.example.android.sunshine.app.sync.SunshineSyncAdapter.INDEX_MIN_TEMP;
import static com.example.android.sunshine.app.sync.SunshineSyncAdapter.INDEX_WEATHER_ID;

/**
 * Created by vaibhav on 11/10/16.
 */

public class WatchSync implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {


    private final String LOG_TAG = "WatchSync";
    private static WatchSync WatchSync;
    private String key = "sunshine";
    private GoogleApiClient googleApiClient = null;
    Context context;
    private static final String WEATHER_PATH = "/sunshine";
    private static final String HIGH_TEMPERATURE = "highTemp";
    private static final String LOW_TEMPERATURE = "lowTemp";
    private static final String WEATHER_ID = "weatherID";


    public WatchSync(Context context) {
        this.context = context;
    }

    public static WatchSync getInstance(Context context) {
        if (WatchSync == null) {
            WatchSync = new WatchSync(context);

        }

        return WatchSync;
    }


    public void sync()

    {
        String locationQuery = Utility.getPreferredLocation(context);
        Uri weatherUri = WeatherContract.WeatherEntry.buildWeatherLocationWithDate(locationQuery, System.currentTimeMillis());
        Cursor cursor = context.getContentResolver().query(weatherUri, SunshineSyncAdapter.NOTIFY_WEATHER_PROJECTION, null, null, null);
        if (cursor == null) {
            return;
        }

        checkGoogleApiClient();

        PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(WEATHER_PATH);

        if (cursor.moveToFirst()) {
            putDataMapRequest.getDataMap().putString(HIGH_TEMPERATURE, Utility.formatTemperature(context, cursor.getDouble(SunshineSyncAdapter.INDEX_MAX_TEMP)));
            putDataMapRequest.getDataMap().putString(LOW_TEMPERATURE, Utility.formatTemperature(context, cursor.getDouble(INDEX_MIN_TEMP)));
            putDataMapRequest.getDataMap().putInt(WEATHER_ID, cursor.getInt(INDEX_WEATHER_ID));
        }
        cursor.close();

        PutDataRequest weatherRequest = putDataMapRequest.asPutDataRequest();
        weatherRequest.setUrgent();
        Wearable.DataApi.putDataItem(googleApiClient, weatherRequest).setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
            @Override
            public void onResult(@NonNull DataApi.DataItemResult dataItemResult) {

                Log.d(LOG_TAG, "" + dataItemResult.getStatus().isSuccess() + " " + dataItemResult.getDataItem().getUri());
            }
        });
    }


    private void checkGoogleApiClient() {
        if (googleApiClient == null)
            googleApiClient = new GoogleApiClient.Builder(context)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();
        googleApiClient.connect();
    }


    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Log.d(LOG_TAG, "connected");
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.d(LOG_TAG, "onConnectionSuspended");
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        Log.d(LOG_TAG, "failed " + connectionResult.getErrorMessage());
    }
}
