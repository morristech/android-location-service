/*
 * Copyright (C) 2015 P100 OG, Inc.
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

package com.shiftconnects.android.location;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingEvent;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;

import java.util.ArrayList;
import java.util.List;

/**
 * Sets up a location service with callbacks for interested parties.
 *
 * To use, simply place the following into your ApplicationManifest somewhere in the <application> tag.
 *
 * <service android:name=".service.BackgroundLocationService"
 *          android:exported="false">
 *
 *          <intent-filter>
 *              <action android:name="com.shiftconnects.android.ACTION_GEOFENCE_TRANSITION"/>
 *          </intent-filter>
 *
 * </service>
 */
public class BackgroundLocationService extends Service implements GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener, LocationListener {

    private static final String ACTION_GEOFENCE_TRANSITION = "com.shiftconnects.android.ACTION_GEOFENCE_TRANSITION";

    private final IBinder mBinder = new LocalBinder();

    public static interface LocationCallbacks {
        void onLocationChanged(Location location);
    }

    public static interface ConnectionCallbacks {
        void onConnectionSuspended(int flag);
        void onLocationServicesConnectionSuccessful();
        void onLocationServicesConnectionFailed(ConnectionResult connectionResult);
    }

    public static interface GeofenceCallbacks {
        void onGeofenceEntered(String geofenceId);
        void onGeofenceDwelled(String geofenceId);
        void onGeofenceExited(String geofenceId);
        void onGeofenceError(GeofencingEvent event);
        void onGeofencesSetupSuccessful();
        void onGeofencesSetupUnsuccessful(Status status);
    }

    private static final String TAG = BackgroundLocationService.class.getSimpleName();
    
    private static final boolean DEBUG = false;

    private GoogleApiClient mGoogleApiClient;

    private List<LocationCallbacks> mLocationCallbacks;
    private List<ConnectionCallbacks> mConnectionCallbacks;
    private List<GeofenceCallbacks> mGeofenceCallbacks;

    private Location mLastLocation;
    private ConnectionResult mFailedConnectionResult;

    @Override public void onCreate() {
        if( DEBUG ) {
            Log.d(TAG, "Service created.");
        }
        super.onCreate();
        mLocationCallbacks = new ArrayList<>();
        mGeofenceCallbacks = new ArrayList<>();
        mConnectionCallbacks = new ArrayList<>();
        if (mGoogleApiClient == null) {
            mGoogleApiClient = new GoogleApiClient.Builder(this)
                    .addApi(LocationServices.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
        }
        mGoogleApiClient.connect();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handleCommand(intent);
        return START_NOT_STICKY;
    }

    private void handleCommand(Intent intent) {
        if (intent != null) {
            final String action = intent.getAction();
            if( DEBUG ) {
                Log.d(TAG, "Received an intent with action [" + action + "]");
            }
            if (TextUtils.equals(ACTION_GEOFENCE_TRANSITION, action)) {
                GeofencingEvent event = GeofencingEvent.fromIntent(intent);
                if (event.hasError()) {
                    if( DEBUG ) {
                        Log.w(TAG, "Received a geofence event with an error!");
                    }
                    if( null != mGeofenceCallbacks ){
                        for( GeofenceCallbacks cb : mGeofenceCallbacks ) {
                            cb.onGeofenceError(event);
                        }
                    }
                } else {
                    switch (event.getGeofenceTransition()) {
                        case Geofence.GEOFENCE_TRANSITION_ENTER:
                            if( DEBUG ) {
                                Log.d(TAG, "Received a geofence ENTER event");
                            }
                            for (Geofence geofence : event.getTriggeringGeofences()) {
                                notifyCallbacksOnGeofenceEntered(geofence.getRequestId());
                            }
                            break;
                        case Geofence.GEOFENCE_TRANSITION_DWELL:
                            if( DEBUG ) {
                                Log.d(TAG, "Received a geofence DWELL event");
                            }
                            for (Geofence geofence : event.getTriggeringGeofences()) {
                                notifyCallbacksOnGeofenceDwelled(geofence.getRequestId());
                            }
                            break;
                        case Geofence.GEOFENCE_TRANSITION_EXIT:
                            if( DEBUG ) {
                                Log.d(TAG, "Received a geofence EXIT event");
                            }
                            for (Geofence geofence : event.getTriggeringGeofences()) {
                                notifyCallbacksOnGeofenceExited(geofence.getRequestId());
                            }
                            break;
                    }
                }
            }
        }
    }

    @Override public void onDestroy() {
        if( DEBUG ) {
            Log.d(TAG, "Service destroyed.");
        }
        super.onDestroy();
        if (mGoogleApiClient != null) {
            mGoogleApiClient.disconnect();
        }
    }

    // region callbacks

    public boolean addLocationCallbacks(LocationCallbacks callbacks) {
        if (mLastLocation != null) {
            callbacks.onLocationChanged(mLastLocation);
        }
        return mLocationCallbacks.add(callbacks);
    }

    public boolean removeLocationCallbacks(LocationCallbacks callbacks) {
        return mLocationCallbacks.remove(callbacks);
    }

    public boolean addGeofenceCallbacks(GeofenceCallbacks callbacks) {
        return mGeofenceCallbacks.add(callbacks);
    }

    public boolean removeGeofenceCallbacks(GeofenceCallbacks callbacks) {
        return mGeofenceCallbacks.remove(callbacks);
    }

    public boolean addConnectionCallbacks(ConnectionCallbacks callbacks) {
        if (mFailedConnectionResult != null) {
            callbacks.onLocationServicesConnectionFailed(mFailedConnectionResult);
        } else if (isLocationServicesConnected()) {
            callbacks.onLocationServicesConnectionSuccessful();
        }
        return mConnectionCallbacks.add(callbacks);
    }

    public boolean removeConnectionCallbacks(ConnectionCallbacks callbacks) {
        return mConnectionCallbacks.remove(callbacks);
    }

    private void notifyCallbacksOnGeofenceEntered(String geofenceId) {
        for (GeofenceCallbacks callbacks : mGeofenceCallbacks) {
            callbacks.onGeofenceEntered(geofenceId);
        }
    }

    private void notifyCallbacksOnGeofenceDwelled(String geofenceId) {
        for (GeofenceCallbacks callbacks : mGeofenceCallbacks) {
            callbacks.onGeofenceDwelled(geofenceId);
        }
    }

    private void notifyCallbacksOnGeofenceExited(String geofenceId) {
        for (GeofenceCallbacks callbacks : mGeofenceCallbacks) {
            callbacks.onGeofenceExited(geofenceId);
        }
    }

    private void notifyCallbacksOnLocationChanged() {
        for (LocationCallbacks callbacks : mLocationCallbacks) {
            callbacks.onLocationChanged(mLastLocation);
        }
    }

    private void notifyCallbacksOnConnectionFailed(ConnectionResult connectionResult) {
        for (ConnectionCallbacks callbacks : mConnectionCallbacks) {
            callbacks.onLocationServicesConnectionFailed(connectionResult);
        }
    }

    private void notifyCallbacksOnConnectionSuccessful() {
        for (ConnectionCallbacks callbacks : mConnectionCallbacks) {
            callbacks.onLocationServicesConnectionSuccessful();
        }
    }

    // endregion

    public void setupGeofences(List<Geofence> geofences) {
        if (isLocationServicesConnected()) {
            if( DEBUG ) {
                Log.d(TAG, "Setting up geofences [" + geofences + "]...");
            }
            LocationServices.GeofencingApi.addGeofences(
                    getGoogleApiClient(),
                    geofences,
                    getGeofencePendingIntent()
            ).setResultCallback(new ResultCallback<Status>() {
                @Override
                public void onResult(Status status) {
                    if (status.isSuccess()) {
                        if( DEBUG ) {
                            Log.d(TAG, "Successfully setup geofences.");
                        }
                        if( null != mGeofenceCallbacks ){
                            for( GeofenceCallbacks cb : mGeofenceCallbacks ) {
                                cb.onGeofencesSetupSuccessful();
                            }
                        }
                    } else {
                        if( null != mGeofenceCallbacks ){
                            for( GeofenceCallbacks cb : mGeofenceCallbacks ) {
                                cb.onGeofencesSetupUnsuccessful(status);
                            }
                        }
                    }
                }
            });
        }
    }

    public void removeGeofences() {
        if (isLocationServicesConnected()) {
            if( DEBUG ) {
                Log.d(TAG, "Removing all geofences...");
            }

            // remove from geofencing api
            LocationServices.GeofencingApi.removeGeofences(getGoogleApiClient(), getGeofencePendingIntent());
        }
    }

    @Override
    public void onConnected(Bundle bundle) {
        if( DEBUG ) {
            Log.d(TAG, "Connected.");
        }
        mFailedConnectionResult = null;
        notifyCallbacksOnConnectionSuccessful();
    }

    public void requestUpdates(LocationRequest locationRequest) {
        if (isLocationServicesConnected()) {
            if( DEBUG ) {
                Log.d(TAG, "Requesting updates for [" + locationRequest + "]");
            }
            LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClient, locationRequest, this);
            Location location = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);
            if (location != null) {
                onLocationChanged(location);
            }
        }
    }

    public void removeLocationUpdates() {
        if (isLocationServicesConnected()) {
            if( DEBUG ) {
                Log.d(TAG, "Removing location updates.");
            }
            LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleApiClient, this);
        }
    }

    public void onConnectionResolved() {
        if (mGoogleApiClient != null && !mGoogleApiClient.isConnecting()) {
            mGoogleApiClient.connect();
        }
    }

    @Override
    public final void onLocationChanged(Location location) {
        if( DEBUG ) {
            Log.d(TAG, "onLocationChanged [" + location + "]");
        }
        mLastLocation = location;
        notifyCallbacksOnLocationChanged();
        if( null != mLocationCallbacks ){
            for( LocationCallbacks cb : mLocationCallbacks ) {
                cb.onLocationChanged(location);
            }
        }
    }

    @Override
    public final void onConnectionSuspended(int i) {
        if( DEBUG ) {
            Log.w(TAG, "Connection to Google Play Services suspended!");
        }
        if( null != mConnectionCallbacks ){
            for( ConnectionCallbacks cb : mConnectionCallbacks ) {
                cb.onConnectionSuspended(i);
            }
        }
    }

    @Override
    public final void onConnectionFailed(ConnectionResult connectionResult) {
        mFailedConnectionResult = connectionResult;
        if( DEBUG ) {
            Log.w(TAG, "Connection to Google Play Services failed!");
        }
        notifyCallbacksOnConnectionFailed(connectionResult);
        if( null != mConnectionCallbacks ){
            for( ConnectionCallbacks cb : mConnectionCallbacks ) {
                cb.onLocationServicesConnectionFailed(connectionResult);
            }
        }
    }

    private PendingIntent getGeofencePendingIntent() {
        return PendingIntent.getService(
                this,
                0,
                new Intent(ACTION_GEOFENCE_TRANSITION),
                PendingIntent.FLAG_UPDATE_CURRENT
        );
    }

    public GoogleApiClient getGoogleApiClient() {
        return mGoogleApiClient;
    }

    public boolean isLocationServicesConnected() {
        return mGoogleApiClient != null && mGoogleApiClient.isConnected();
    }

    @Override public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public class LocalBinder extends Binder {
        public BackgroundLocationService getBackgroundLocationService() {
            return BackgroundLocationService.this;
        }
    }
}
