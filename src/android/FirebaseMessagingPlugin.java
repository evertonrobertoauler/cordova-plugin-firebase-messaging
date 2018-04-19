package by.chemerisuk.cordova.firebase;

import android.content.Context;
import android.content.Intent;
import android.app.PendingIntent;
import android.content.pm.PackageManager;
import android.media.RingtoneManager;
import android.os.Bundle;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.content.SharedPreferences;
import android.support.v4.app.NotificationManagerCompat;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.iid.FirebaseInstanceId;

import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.PluginResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Set;

import me.leolin.shortcutbadger.ShortcutBadger;


public class FirebaseMessagingPlugin extends CordovaPlugin {
    private static final String TAG = "FirebaseMessagingPlugin";

    private CallbackContext instanceIdCallback;
    private CallbackContext foregroundCallback;
    private CallbackContext backgroundCallback;
    private static Bundle lastBundle;
    private static FirebaseMessagingPlugin instance;
    private static boolean isInBackground = true;

    private static JSONObject notification;

    @Override
    protected void pluginInitialize() {
        FirebaseMessagingPlugin.instance = this;

        Context context = cordova.getActivity().getApplicationContext();
        Bundle bundle = cordova.getActivity().getIntent().getExtras();
        if (bundle != null && (bundle.containsKey("google.message_id") || bundle.containsKey("google.sent_time"))) {
            lastBundle = bundle;
        }
        // cleanup badge value initially
        ShortcutBadger.applyCount(context, 0);
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if ("subscribe".equals(action)) {
            this.subscribe(callbackContext, args.getString(0));
            return true;
        } else if ("unsubscribe".equals(action)) {
            this.unsubscribe(callbackContext, args.getString(0));
            return true;
        } else if ("getToken".equals(action)) {
            this.getToken(callbackContext);
            return true;
        } else if ("onTokenRefresh".equals(action)) {
            this.registerTokenReceiver(callbackContext);
            return true;
        } else if ("onMessage".equals(action)) {
            this.registerForegroundCallback(callbackContext);
            return true;
        } else if ("onBackgroundMessage".equals(action)) {
            this.registerBackgroundCallback(callbackContext);
            return true;
        } else if ("setBadge".equals(action)) {
            this.setBadge(callbackContext, args.optInt(0));
            return true;
        } else if ("getBadge".equals(action)) {
            this.getBadge(callbackContext);
            return true;
        } else if ("requestPermission".equals(action)) {
            this.requestPermission(callbackContext);
            return true;
        }

        return false;
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        try {
            Bundle extras = intent.getExtras();
            if (extras != null) {
                sendNotification(getNotification(extras), true);
            }
        } catch (JSONException e) {
            Log.e(TAG, "onNewIntent", e);
        }
    }

    private void subscribe(CallbackContext callbackContext, String topic) {
        FirebaseMessaging.getInstance().subscribeToTopic(topic);

        callbackContext.success();
    }

    private void unsubscribe(CallbackContext callbackContext, String topic) {
        FirebaseMessaging.getInstance().unsubscribeFromTopic(topic);

        callbackContext.success();
    }

    private void getToken(CallbackContext callbackContext) {
        String token = FirebaseInstanceId.getInstance().getToken();

        callbackContext.success(token);
    }

    private void registerTokenReceiver(CallbackContext callbackContext) {
        instance.instanceIdCallback = callbackContext;
    }

    private void registerForegroundCallback(CallbackContext callbackContext) throws JSONException {
        instance.foregroundCallback = callbackContext;

        if (notification != null) {
            sendNotification(notification, false);
            notification = null;
        }
    }

    private void registerBackgroundCallback(CallbackContext callbackContext) throws JSONException {
        instance.backgroundCallback = callbackContext;

        if (lastBundle != null) {
            sendNotification(getNotification(lastBundle), true);
            lastBundle = null;
        }
    }

    private void setBadge(CallbackContext callbackContext, int value) {
        if (value >= 0) {
            Context context = cordova.getActivity();
            ShortcutBadger.applyCount(context.getApplicationContext(), value);

            callbackContext.success();
        } else {
            callbackContext.error("Badge value can't be negative");
        }
    }

    private void getBadge(CallbackContext callbackContext) {
        Context context = cordova.getActivity();
        SharedPreferences settings = context.getSharedPreferences("badge", Context.MODE_PRIVATE);
        int number = settings.getInt("badge", 0);
        callbackContext.success(number);
    }

    private void requestPermission(CallbackContext callbackContext) {
        Context context = cordova.getActivity().getApplicationContext();
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            callbackContext.success();
        } else {
            callbackContext.error("Push notifications are disabled");
        }
    }

    private static void sendNotification(JSONObject notificationData, boolean background) throws JSONException {
        if (instance != null) {
            CallbackContext callback = background ? instance.backgroundCallback : instance.foregroundCallback;
            if (callback != null) {
                PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, notificationData);
                pluginResult.setKeepCallback(true);
                callback.sendPluginResult(pluginResult);
            } else {
                notification = notificationData;
            }
        } else {
            notification = notificationData;
        }
    }

    static void sendNotification(JSONObject notificationData, Context context) throws JSONException {
        if (isInBackground) {
            notificationData.put("background", isInBackground);
        }

        sendNotification(notificationData, false);
        showNotification(notificationData, context);
    }

    static void sendInstanceId(String instanceId) {
        if (instance != null && instance.instanceIdCallback != null && instanceId != null) {
            PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, instanceId);
            pluginResult.setKeepCallback(true);
            instance.instanceIdCallback.sendPluginResult(pluginResult);
        }
    }

    private static JSONObject getNotification(Bundle bundle) throws JSONException {
        JSONObject notificationData = new JSONObject();
        Set<String> keys = bundle.keySet();
        for (String key : keys) {
            notificationData.put(key, bundle.get(key));
        }
        return notificationData;
    }

    @Override
    public void onPause(boolean multitasking) {
        super.onPause(multitasking);
        isInBackground = true;
    }

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        isInBackground = false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isInBackground = true;
    }

    private static void showNotification(JSONObject notificationData, Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            Intent launchIntent = pm.getLaunchIntentForPackage(context.getApplicationContext().getPackageName());
            PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, launchIntent, 0);

            NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(context, notificationData.get("hash").toString())
                    .setSmallIcon(context.getApplicationInfo().icon)
                    .setContentTitle(notificationData.get("titulo").toString())
                    .setContentText(notificationData.get("conteudo").toString())
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                    .setVibrate(new long[]{300, 300});

            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
            notificationManager.notify(1, mBuilder.build());

            if (!isInBackground) {
                Thread.sleep(2000);
                notificationManager.cancel(1);
            }
        } catch (Exception e) {
            Log.e(TAG, "showNotification", e);
        }

    }
}
