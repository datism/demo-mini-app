package com.demo.imsdc.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.demo.imsdc.R
import com.demo.imsdc.call.CallService
import com.demo.imsdc.ui.CallActivity

private const val TAG = "CallRequestNotif"
private const val CHANNEL_ID = "call_requests"
private const val NOTIFICATION_ID = 4202

/**
 * The other phone asks for something during the call (open an app, share a photo or video) while the call
 * screen is hidden: the sheet that asks is on that screen, so a heads-up says so. Tap opens the call screen
 * with the sheet; "Đồng ý" opens it and accepts; "Từ chối" declines without leaving the current app.
 */
object CallRequestNotification {

    fun show(context: Context, peer: String, text: String, timeoutMs: Long) {
        val ctx = context.applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Yêu cầu trong cuộc gọi", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Người kia muốn mở ứng dụng hoặc chia sẻ nội dung"
                }
            )
        }
        fun openCall(code: Int, accept: Boolean) = PendingIntent.getActivity(
            ctx, code,
            Intent(ctx, CallActivity::class.java)
                .putExtra(CallActivity.EXTRA_FROM_ONGOING, true)
                .putExtra(CallActivity.EXTRA_REQUEST_ANSWER, accept)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val decline = PendingIntent.getService(
            ctx, 3, Intent(ctx, CallService::class.java).setAction(CallService.ACTION_DECLINE_REQUEST),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_phone_incoming)
            .setContentTitle(peer)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openCall(10, accept = false))
            .setTimeoutAfter(timeoutMs)
            .setAutoCancel(true)
            .addAction(0, "Từ chối", decline)
            .addAction(0, "Đồng ý", openCall(11, accept = true))
            .build()
        try {
            nm.notify(NOTIFICATION_ID, n)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot post the request notification: ${e.message}")
        }
    }

    fun cancel(context: Context) {
        try {
            context.applicationContext.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
    }
}
