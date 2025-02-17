package org.thoughtcrime.securesms.gcm;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobs.FcmRefreshJob;
import org.thoughtcrime.securesms.jobs.SubmitRateLimitPushChallengeJob;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.registration.PushChallengeRequest;
import org.thoughtcrime.securesms.util.FeatureFlags;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class FcmReceiveService extends FirebaseMessagingService {

  private static final String TAG = Log.tag(FcmReceiveService.class);

  private static final long FCM_FOREGROUND_INTERVAL = TimeUnit.MINUTES.toMillis(3);

  @Override
  public void onMessageReceived(RemoteMessage remoteMessage) {
    Log.i(TAG, String.format(Locale.US,
                             "onMessageReceived() ID: %s, Delay: %d, Priority: %d, Original Priority: %d",
                             remoteMessage.getMessageId(),
                             (System.currentTimeMillis() - remoteMessage.getSentTime()),
                             remoteMessage.getPriority(),
                             remoteMessage.getOriginalPriority()));

    String registrationChallenge = remoteMessage.getData().get("challenge");
    String rateLimitChallenge    = remoteMessage.getData().get("rateLimitChallenge");

    if (registrationChallenge != null) {
      handleRegistrationPushChallenge(registrationChallenge);
    } else if (rateLimitChallenge != null) {
      handleRateLimitPushChallenge(rateLimitChallenge);
    } else {
      handleReceivedNotification(ApplicationDependencies.getApplication(), remoteMessage);
    }
  }

  @Override
  public void onDeletedMessages() {
    Log.w(TAG, "onDeleteMessages() -- Messages may have been dropped. Doing a normal message fetch.");
    handleReceivedNotification(ApplicationDependencies.getApplication(), null);
  }

  @Override
  public void onNewToken(String token) {
    Log.i(TAG, "onNewToken()");

    if (!SignalStore.account().isRegistered()) {
      Log.i(TAG, "Got a new FCM token, but the user isn't registered.");
      return;
    }

    ApplicationDependencies.getJobManager().add(new FcmRefreshJob());
  }

  @Override
  public void onMessageSent(@NonNull String s) {
    Log.i(TAG, "onMessageSent()" + s);
  }

  @Override
  public void onSendError(@NonNull String s, @NonNull Exception e) {
    Log.w(TAG, "onSendError()", e);
  }

  private static void handleReceivedNotification(Context context, @Nullable RemoteMessage remoteMessage) {
    try {
      long timeSinceLastRefresh = System.currentTimeMillis() - SignalStore.misc().getLastFcmForegroundServiceTime();
      if (FeatureFlags.useFcmForegroundService() && Build.VERSION.SDK_INT >= 31 && remoteMessage != null && remoteMessage.getPriority() == RemoteMessage.PRIORITY_HIGH && timeSinceLastRefresh > FCM_FOREGROUND_INTERVAL) {
        context.startService(FcmFetchService.buildIntent(context, true));
        SignalStore.misc().setLastFcmForegroundServiceTime(System.currentTimeMillis());
      } else {
        context.startService(FcmFetchService.buildIntent(context, false));
      }
    } catch (Exception e) {
      Log.w(TAG, "Failed to start service. Falling back to legacy approach.", e);
      FcmFetchService.retrieveMessages(context);
    }
  }

  private static void handleRegistrationPushChallenge(@NonNull String challenge) {
    Log.d(TAG, "Got a registration push challenge.");
    PushChallengeRequest.postChallengeResponse(challenge);
  }

  private static void handleRateLimitPushChallenge(@NonNull String challenge) {
    Log.d(TAG, "Got a rate limit push challenge.");
    ApplicationDependencies.getJobManager().add(new SubmitRateLimitPushChallengeJob(challenge));
  }
}