package com.testemodelo.video;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;

/**
 * Âncora de foreground service exigida pelo Android 14+ para MediaProjection.
 * Adaptado do Lab Arquiteturas (pacote + nome). Cria o MediaProjection e o
 * expõe via getter estático; a Activity o usa após o broadcast FGS_READY.
 */
public class CaptureService extends Service {

    public static final String ACTION_START = "com.testemodelo.video.START";
    public static final String ACTION_STOP = "com.testemodelo.video.STOP";
    public static final String BROADCAST_READY = "com.testemodelo.video.FGS_READY";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    private static final int NOTIF_ID = 1;
    private static final String CHANNEL_ID = "teste-modelo-captura";

    private static MediaProjection projection;

    public static MediaProjection getProjection() {
        return projection;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            if (projection != null) {
                try { projection.stop(); } catch (Exception ignored) {}
                projection = null;
            }
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    "Captura de teste", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notif = b.setContentTitle("Teste Modelo")
                .setContentText("Criando vídeo de teste.")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, notif);
        }
        if (intent != null && intent.hasExtra(EXTRA_RESULT_CODE)) {
            int code = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            try {
                MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                projection = mgr.getMediaProjection(code, data);
            } catch (Exception e) {
                projection = null;
            }
        }
        Intent ready = new Intent(BROADCAST_READY);
        ready.setPackage(getPackageName());
        sendBroadcast(ready);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
