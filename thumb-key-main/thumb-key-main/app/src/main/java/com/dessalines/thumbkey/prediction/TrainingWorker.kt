package com.dessalines.thumbkey.prediction

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dessalines.thumbkey.R
import java.util.concurrent.TimeUnit

/**
 * Background worker that periodically fine-tunes the LLM adapter
 * using the user's accumulated training data.
 *
 * Runs as a foreground service with a notification so the OS
 * won't kill the long-running training process. Holds a WakeLock
 * to prevent the CPU from sleeping mid-training.
 *
 * Training is locale-aware: each locale's data is trained separately
 * against the best matching model.
 */
class TrainingWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val trainingLog = TrainingLog(applicationContext)
        if (trainingLog.size < MIN_EXAMPLES) {
            Log.d(TAG, "Auto-training skipped: only ${trainingLog.size} entries (need $MIN_EXAMPLES)")
            return Result.success()
        }

        val modelFile = ModelPaths.getDefaultModelPath(applicationContext)
        if (modelFile == null || !modelFile.exists()) {
            Log.d(TAG, "Auto-training skipped: no model file")
            return Result.success()
        }

        if (!PredictionBridge.isSafeToLoad(applicationContext)) {
            Log.d(TAG, "Auto-training skipped: crash guard active")
            return Result.success()
        }

        setForeground(createForegroundInfo())

        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ThumbKey::TrainingWorker",
        )

        Log.i(TAG, "Auto-training starting with ${trainingLog.size} entries")
        wakeLock.acquire(120 * 60 * 1000L)
        return try {
            val locales = trainingLog.getLocales()
            var anySuccess = false

            for (locale in locales) {
                val localeExamples = trainingLog.getTrainingExamples(locale)
                if (localeExamples.size < MIN_EXAMPLES) {
                    Log.d(TAG, "Skipping locale=$locale: only ${localeExamples.size} entries")
                    continue
                }

                Log.i(TAG, "Training for locale=$locale (${localeExamples.size} raw entries)")
                val success = AdapterTrainerHelper.trainFromLog(
                    context = applicationContext,
                    trainingLog = trainingLog,
                    locale = locale,
                )
                if (success) {
                    anySuccess = true
                    Log.i(TAG, "Auto-training completed for locale=$locale")
                } else {
                    Log.w(TAG, "Auto-training returned false for locale=$locale")
                }
            }

            if (anySuccess) {
                Log.i(TAG, "Auto-training completed successfully")
                Result.success()
            } else {
                Log.w(TAG, "Auto-training: no locale produced a successful training run")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auto-training failed", e)
            Result.retry()
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        createNotificationChannel()

        val cancelIntent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.training_notification_title))
            .setContentText(applicationContext.getString(R.string.training_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_delete, "Cancel", cancelIntent)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.training_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "TrainingWorker"
        private const val MIN_EXAMPLES = 50
        private const val WORK_NAME = "thumbkey_auto_train"
        private const val CHANNEL_ID = "thumbkey_training"
        private const val NOTIFICATION_ID = 9001

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<TrainingWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            Log.i(TAG, "Auto-training scheduled (every 24h, when charging)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Auto-training cancelled")
        }
    }
}
