package com.mashibo.tuesday

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object Haptics {
    @Suppress("DEPRECATION")
    private fun vib(ctx: Context): Vibrator {
        return if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun play(ctx: Context, effect: Int, fallbackMs: Long) {
        try {
            val v = vib(ctx)
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(effect))
            else v.vibrate(VibrationEffect.createOneShot(fallbackMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    fun tick(ctx: Context) = play(ctx, VibrationEffect.EFFECT_TICK, 12L)
    fun confirm(ctx: Context) = play(ctx, VibrationEffect.EFFECT_CLICK, 25L)
    fun heavy(ctx: Context) = play(ctx, VibrationEffect.EFFECT_HEAVY_CLICK, 45L)
}
