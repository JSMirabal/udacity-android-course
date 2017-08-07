package japps.sunshine_version_julio.sync;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.annotation.SuppressLint;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SyncRequest;
import android.content.SyncResult;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.IntDef;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.Log;

import com.bumptech.glide.Glide;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.HttpURLConnection;
import java.util.Calendar;
import java.util.Vector;
import java.util.concurrent.ExecutionException;

import japps.sunshine_version_julio.BuildConfig;
import japps.sunshine_version_julio.R;
import japps.sunshine_version_julio.activities.MainActivity;
import japps.sunshine_version_julio.data.WeatherContract;
import japps.sunshine_version_julio.services.WeatherForecastService;
import japps.sunshine_version_julio.utils.Utility;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.scalars.ScalarsConverterFactory;


public class SunshineSyncAdapter extends AbstractThreadedSyncAdapter {
    public final String LOG_TAG = SunshineSyncAdapter.class.getSimpleName();
    private static Context mContext;
    public static final String SYNC_START_KEY = "SYNC_ADAPTER_START";
    public static final String SYNC_FINISH_KEY = "SYNC_ADAPTER_FINISH";
    public static final String LAST_SYNC_STATE_KEY = "LAST_SYNC_STATE";
    // Interval at which to sync with the weather, in milliseconds.
    // 60 seconds (1 minute)  180 = 3 hours
    public static final int SYNC_INTERVAL = 60 * 2;
    public static final int SYNC_FLEXTIME = SYNC_INTERVAL / 3;
    private static final String[] NOTIFY_WEATHER_PROJECTION = new String[]{
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP,
            WeatherContract.WeatherEntry.COLUMN_SHORT_DESC
    };
    // these indices must match the projection
    private static final int INDEX_WEATHER_ID = 0;
    private static final int INDEX_MAX_TEMP = 1;
    private static final int INDEX_MIN_TEMP = 2;
    private static final int INDEX_SHORT_DESC = 3;
    private static final long DAY_IN_MILLIS = 1000 * 60 * 60 * 24;
    private static final int WEATHER_NOTIFICATION_ID = 3004;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({LOCATION_STATUS_OK, LOCATION_STATUS_SERVER_DOWN, LOCATION_STATUS_SERVER_INVALID,
            LOCATION_STATUS_UNKNOWN, LOCATION_STATUS_INVALID})
    public @interface LocationStatus {
    }

    public static final int LOCATION_STATUS_OK = 0;
    public static final int LOCATION_STATUS_SERVER_DOWN = 1;
    public static final int LOCATION_STATUS_SERVER_INVALID = 2;
    public static final int LOCATION_STATUS_UNKNOWN = 3;
    public static final int LOCATION_STATUS_INVALID = 4;

    public SunshineSyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
        mContext = mContext == null ? context : mContext;
    }

    @Override
    public void onPerformSync(Account account, Bundle extras, String authority, ContentProviderClient provider, SyncResult syncResult) {
        Log.d(LOG_TAG, "Syncing data...");
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(new Intent(SYNC_START_KEY));
        requestWeatherData();
    }

    private void notifyWeather() {
        //checking the last update and notify if it' the first of the day
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        String lastNotificationKey = mContext.getString(R.string.pref_last_notification);
        long lastSync = prefs.getLong(lastNotificationKey, 0);

        if (System.currentTimeMillis() - lastSync >= DAY_IN_MILLIS) {
            // Last sync was more than 1 day ago, let's send a notification with the weather.
            String locationQuery = Utility.getPreferredLocationSetting(mContext);

            Uri weatherUri = WeatherContract.WeatherEntry.buildWeatherLocationWithDate(locationQuery, System.currentTimeMillis());

            // we'll query our contentProvider, as always
            Cursor cursor = mContext.getContentResolver().query(weatherUri, NOTIFY_WEATHER_PROJECTION, null, null, null);

            if (cursor.moveToFirst()) {
                int weatherId = cursor.getInt(INDEX_WEATHER_ID);
                double high = cursor.getDouble(INDEX_MAX_TEMP);
                double low = cursor.getDouble(INDEX_MIN_TEMP);
                String desc = cursor.getString(INDEX_SHORT_DESC);

                int iconId = Utility.getIconResourceForWeatherCondition(weatherId);
                Resources resources = mContext.getResources();
                int artResourceId = Utility.getArtResourceForWeatherCondition(weatherId);
                String artUrl = Utility.getArtUrlForWeatherCondition(mContext, weatherId);

                // On Honeycomb and higher devices, we can retrieve the size of the large icon
                // Prior to that, we use a fixed size
                @SuppressLint("InlinedApi")
                int largeIconWidth = Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB
                        ? resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
                        : resources.getDimensionPixelSize(R.dimen.notification_large_icon_default);
                @SuppressLint("InlinedApi")
                int largeIconHeight = Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB
                        ? resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_height)
                        : resources.getDimensionPixelSize(R.dimen.notification_large_icon_default);

                // Retrieve the large icon
                Bitmap largeIcon;
                try {
                    largeIcon = Glide.with(mContext)
                            .asBitmap()
                            .load(artUrl)
                            .submit(largeIconWidth, largeIconHeight)
                            .get();
                } catch (InterruptedException | ExecutionException e) {
                    Log.e(LOG_TAG, "Error retrieving large icon from " + artUrl, e);
                    largeIcon = BitmapFactory.decodeResource(resources, artResourceId);
                }
                String title = mContext.getString(R.string.app_name);
                // Define the text of the forecast.
                boolean isMetric = Utility.isMetric(mContext);
                String contentText = String.format(mContext.getString(R.string.format_notification),
                        desc,
                        Utility.formatTemperature(mContext, high, isMetric),
                        Utility.formatTemperature(mContext, low, isMetric));

                //build your notification here.
                NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext);
                builder.setSmallIcon(iconId)
                        .setLargeIcon(largeIcon)
                        .setContentTitle(title)
                        .setContentText(Utility.capitalize(contentText));
                Intent intent = new Intent(mContext, MainActivity.class);
                TaskStackBuilder stackBuilder = TaskStackBuilder.create(mContext);
                stackBuilder.addParentStack(MainActivity.class);
                stackBuilder.addNextIntent(intent);
                PendingIntent pendingIntent = stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT);
                builder.setContentIntent(pendingIntent);
                ((NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE))
                        .notify(WEATHER_NOTIFICATION_ID, builder.build());
                //refreshing last sync
                SharedPreferences.Editor editor = prefs.edit();
                editor.putLong(lastNotificationKey, System.currentTimeMillis());
                editor.apply();
            }
        }

    }

    /**
     * Helper method to schedule the sync adapter periodic execution
     */
    public static void configurePeriodicSync(Context context, int syncInterval, int flexTime) {
        Account account = getSyncAccount(context);
        String authority = context.getString(R.string.content_authority);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            // we can enable inexact timers in our periodic sync
            SyncRequest request = new SyncRequest.Builder().
                    syncPeriodic(syncInterval, flexTime).
                    setSyncAdapter(account, authority).
                    setExtras(new Bundle()).build();
            ContentResolver.requestSync(request);
        } else {
            ContentResolver.addPeriodicSync(account,
                    authority, new Bundle(), syncInterval);
        }
    }


    private static void onAccountCreated(Account newAccount, Context context) {
        /*
         * Since we've created an account
         */
        SunshineSyncAdapter.configurePeriodicSync(context, SYNC_INTERVAL, SYNC_FLEXTIME);

        /*
         * Without calling setSyncAutomatically, our periodic sync will not be enabled.
         */
        ContentResolver.setSyncAutomatically(newAccount, context.getString(R.string.content_authority), true);

        /*
         * Finally, let's do a sync to get things started
         */
        syncImmediately(context);
    }

    public static void initializeSyncAdapter(Context context) {
        getSyncAccount(context);
    }

    /**
     * Helper method to have the sync adapter sync immediately
     *
     * @param context The context used to access the account service
     */
    public static void syncImmediately(Context context) {
        Bundle bundle = new Bundle();
        mContext = context;
        bundle.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        bundle.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        ContentResolver.requestSync(getSyncAccount(context),
                context.getString(R.string.content_authority), bundle);
    }

    /**
     * Helper method to get the fake account to be used with SyncAdapter, or make a new one
     * if the fake account doesn't exist yet.  If we make a new account, we call the
     * onAccountCreated method so we can initialize things.
     *
     * @param context The context used to access the account service
     * @return a fake account.
     */
    public static Account getSyncAccount(Context context) {
        // Get an instance of the Android account manager
        AccountManager accountManager =
                (AccountManager) context.getSystemService(Context.ACCOUNT_SERVICE);

        // Create the account type and default account
        Account newAccount = new Account(
                context.getString(R.string.app_name), context.getString(R.string.sync_account_type));

        // If the password doesn't exist, the account doesn't exist
        if (null == accountManager.getPassword(newAccount)) {

        /*
         * Add the account and account type, no password or user data
         * If successful, return the Account object, otherwise report an error.
         */
            if (!accountManager.addAccountExplicitly(newAccount, "", null)) {
                return null;
            }
            /*
             * If you don't set android:syncable="true" in
             * in your <provider> element in the manifest,
             * then call ContentResolver.setIsSyncable(account, AUTHORITY, 1)
             * here.
             */

            onAccountCreated(newAccount, context);
        }
        return newAccount;
    }

    private void requestWeatherData() {

        final String CITY = Utility.getPreferredCity(mContext);
        final String UNITS = "metric";
        final String BASE_URL = "http://api.openweathermap.org/";
        final String FROM_DAYS = "12";
        final String API_KEY = BuildConfig.OPEN_WEATHER_MAP_API_KEY;
        final String LANG = Utility.getLocale();

        try {
            Retrofit retrofit = new Retrofit.Builder()
                    .baseUrl(BASE_URL)
                    .addConverterFactory(ScalarsConverterFactory.create())
                    .build();
            WeatherForecastService service = retrofit.create(WeatherForecastService.class);
            Call<String> request = service.requestWeatherForecast(
                    CITY, UNITS, FROM_DAYS, LANG, API_KEY
            );

            Response<String> response = request.execute();
            String forecastJsonStr = response.body();
            if (!TextUtils.isEmpty(forecastJsonStr)) {
                getWeatherDataFromJson(forecastJsonStr, CITY);
                LocalBroadcastManager.getInstance(mContext).sendBroadcast(new Intent(SYNC_FINISH_KEY));
            } else if (response.code() == HttpURLConnection.HTTP_NOT_FOUND) {
                setLocationStatus(mContext, SunshineSyncAdapter.LOCATION_STATUS_INVALID);
            } else {
                setLocationStatus(mContext, SunshineSyncAdapter.LOCATION_STATUS_SERVER_DOWN);
            }
        } catch (IOException | JSONException | IllegalArgumentException e) {
            if (e instanceof IOException) {
                setLocationStatus(getContext(), LOCATION_STATUS_SERVER_INVALID);
                Log.e(LOG_TAG, e.getMessage(), e);
            } else if (e instanceof JSONException) {
                setLocationStatus(getContext(), LOCATION_STATUS_SERVER_DOWN);
                Log.e(LOG_TAG, "JSON_Error ", e);
            } else {
                e.printStackTrace();
            }
        }
    }

    private void getWeatherDataFromJson(String forecastJsonStr, String cityName)
            throws JSONException {

        // Now we have a String representing the complete forecast in JSON Format.
        // Fortunately parsing is easy:  constructor takes the JSON string and converts it
        // into an Object hierarchy for us.

        // These are the names of the JSON objects that need to be extracted.

        // Location information
        final String OWM_CITY = "city";
        final String OWM_CITY_ID = "id";
        final String OWM_COORD = "coord";

        // Location coordinate
        final String OWM_LATITUDE = "lat";
        final String OWM_LONGITUDE = "lon";

        // Weather information.  Each day's forecast info is an element of the "list" array.
        final String OWM_LIST = "list";

        final String OWM_PRESSURE = "pressure";
        final String OWM_HUMIDITY = "humidity";
        final String OWM_WINDSPEED = "speed";
        final String OWM_WIND_DIRECTION = "deg";

        // All temperatures are children of the "temp" object.
        final String OWM_TEMPERATURE = "temp";
        final String OWM_MAX = "max";
        final String OWM_MIN = "min";

        final String OWM_WEATHER = "weather";
        final String OWM_DESCRIPTION = "description";
        final String OWM_WEATHER_ID = "id";
        final String OWM_DT = "dt";
        final String OWM_MESSAGE_CODE = "code";

        JSONObject forecastJson = new JSONObject(forecastJsonStr);
        JSONArray weatherArray = forecastJson.getJSONArray(OWM_LIST);

        JSONObject cityJson = forecastJson.getJSONObject(OWM_CITY);
        String locationSetting = cityJson.getString(OWM_CITY_ID);
        // Adding location_setting value to SharedPreferences for later usage
        PreferenceManager.getDefaultSharedPreferences(mContext).edit().putString(
                mContext.getString(R.string.pref_location_setting_key), locationSetting).apply();
        //--------------------------------------------------------------------------------------
        JSONObject cityCoord = cityJson.getJSONObject(OWM_COORD);
        double cityLatitude = cityCoord.getDouble(OWM_LATITUDE);
        double cityLongitude = cityCoord.getDouble(OWM_LONGITUDE);

        long locationId = addLocation(locationSetting, cityName, cityLatitude, cityLongitude);
        // Insert the new weather information into the database
        Vector<ContentValues> cVVector = new Vector<>(weatherArray.length());

        // OWM returns daily forecasts based upon the local time of the city that is being
        // asked for, which means that we need to know the GMT offset to translate this data
        // properly.

        for (int i = 0; i < weatherArray.length(); i++) {
            // These are the values that will be collected.
            long dateTime;
            double pressure;
            int humidity;
            double windSpeed;
            double windDirection;

            double high;
            double low;

            String description;
            int weatherId;

            // Get the JSON object representing the day
            JSONObject dayForecast = weatherArray.getJSONObject(i);

            // Cheating to convert this to UTC time, which is what we want anyhow
            Calendar calendar = Calendar.getInstance();
            calendar.add(Calendar.DATE, i);
            dateTime = calendar.getTimeInMillis();
            pressure = dayForecast.getDouble(OWM_PRESSURE);
            humidity = dayForecast.getInt(OWM_HUMIDITY);
            windSpeed = dayForecast.getDouble(OWM_WINDSPEED);
            windDirection = dayForecast.getDouble(OWM_WIND_DIRECTION);

            // Description is in a child array called "weather", which is 1 element long.
            // That element also contains a weather code.
            JSONObject weatherObject =
                    dayForecast.getJSONArray(OWM_WEATHER).getJSONObject(0);
            description = weatherObject.getString(OWM_DESCRIPTION);
            weatherId = weatherObject.getInt(OWM_WEATHER_ID);

            // Temperatures are in a child object called "temp".  Try not to name variables
            // "temp" when working with temperature.  It confuses everybody.
            JSONObject temperatureObject = dayForecast.getJSONObject(OWM_TEMPERATURE);
            high = temperatureObject.getDouble(OWM_MAX);
            low = temperatureObject.getDouble(OWM_MIN);

            ContentValues weatherValues = new ContentValues();

            weatherValues.put(WeatherContract.WeatherEntry.COLUMN_LOC_KEY, locationId);
            weatherValues.put(WeatherContract.WeatherEntry.COLUMN_DATE, dateTime);
            weatherValues.put(WeatherContract.WeatherEntry.COLUMN_HUMIDITY, humidity);
            weatherValues.put(WeatherContract.WeatherEntry.COLUMN_PRESSURE, pressure);
            weatherValues.put(WeatherContract.WeatherEntry.COLUMN_WIND_SPEED, windSpeed);
            weatherValues.put(WeatherContract.WeatherEntry.COLUMN_DEGREES, windDirection);
            weatherValues.put(WeatherContract.WeatherEntry.COLUMN_MAX_TEMP, high);
            weatherValues.put(WeatherContract.WeatherEntry.COLUMN_MIN_TEMP, low);
            weatherValues.put(WeatherContract.WeatherEntry.COLUMN_SHORT_DESC, description);
            weatherValues.put(WeatherContract.WeatherEntry.COLUMN_WEATHER_ID, weatherId);
            cVVector.add(weatherValues);
        }

        // add to database
        int inserted = 0;
        Calendar calendar = Calendar.getInstance();
//            String today = Long.toString(Utility.getStartOfDayInMillis(calendar.getTime()));
        calendar.add(Calendar.DATE, -1);
        String yesterday = Long.toString(Utility.getStartOfDayInMillis(calendar.getTime()));
        if (cVVector.size() > 0) {
            ContentResolver resolver = mContext.getContentResolver();
            ContentValues[] valuesArray = new ContentValues[cVVector.size()];
            cVVector.toArray(valuesArray);
            inserted = resolver.bulkInsert(WeatherContract.WeatherEntry.CONTENT_URI, valuesArray);
            // delete old data so we don't build up an endless history
            getContext().getContentResolver().delete(WeatherContract.WeatherEntry.CONTENT_URI,
                    WeatherContract.WeatherEntry.COLUMN_DATE + " <= ?",
                    new String[]{yesterday});
            if (Utility.isNotificationActive(mContext)) {
                notifyWeather();
            }
        }
        setLocationStatus(getContext(), LOCATION_STATUS_OK);
        Log.d(LOG_TAG, "FetchWeatherTask from service Complete. " + inserted + " Inserted");
    }

    private void setLocationStatus(Context context, int locationStatus) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor spe = sp.edit();
        spe.putInt(context.getString(R.string.pref_location_status_key), locationStatus);
        spe.apply();
    }

    public long addLocation(String locationSetting, String cityName, double lat, double lon) {
        // Students: First, check if the location with this city name exists in the db
        // If it exists, return the current ID
        // Otherwise, insert it using the content resolver and the base URI
        Uri locationUri = WeatherContract.LocationEntry.CONTENT_URI;
        ContentResolver resolver = mContext.getContentResolver();
        ContentValues values;
        String[] columns = {WeatherContract.LocationEntry._ID};
        String selection = WeatherContract.LocationEntry.COLUMN_LOCATION_SETTING + " = ?";
        String[] selectionArgs = {locationSetting};
        Cursor cursor = resolver.query(locationUri, columns, selection, selectionArgs, null);

        if (cursor.moveToFirst()) {
            return cursor.getLong(cursor.getColumnIndex(WeatherContract.LocationEntry._ID));
        }
        values = new ContentValues();
        values.put(WeatherContract.LocationEntry.COLUMN_LOCATION_SETTING, locationSetting);
        values.put(WeatherContract.LocationEntry.COLUMN_CITY_NAME, cityName);
        values.put(WeatherContract.LocationEntry.COLUMN_COORD_LAT, lat);
        values.put(WeatherContract.LocationEntry.COLUMN_COORD_LONG, lon);
        return ContentUris.parseId(resolver.insert(locationUri, values));
    }
}