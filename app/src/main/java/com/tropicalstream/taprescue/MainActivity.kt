package com.tropicalstream.taprescue

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.tropicalstream.taprescue.audio.Sfx
import com.tropicalstream.taprescue.game.Particles
import com.tropicalstream.taprescue.game.PooyanGame
import com.tropicalstream.taprescue.input.TrackpadGestureEngine
import com.tropicalstream.taprescue.render.PooyanView
import com.tropicalstream.taprescue.ui.BinocularSbsLayout

/**
 * TapRescue — the balloon-wolf fixed shooter, remade for the RayNeo X3 Pro.
 *
 * Controls (right temple pad):
 *   slide up/down — move Mama Pig's lift (continuous)
 *   tap           — shoot an arrow (start / resume / restart on menus)
 *   flick left    — hurl a meat slab (chains wolves!)
 *   long-hold     — RayNeo system Control Center / exit
 */
class MainActivity : Activity() {

    companion object {
        /** Raw pad px → lift px. Same feel as TapPong's paddle gain. */
        private const val DRAG_GAIN = 0.65f
    }

    private val game = PooyanGame()
    private val particles = Particles()
    private val gestures = TrackpadGestureEngine()
    private val sfx by lazy { Sfx(this) }
    private lateinit var settings: SettingsStore
    private lateinit var view: PooyanView

    private var running = false
    private var lastFrameMs = 0L

    /** 1dp == 1px on the 640×480-per-eye canvas — idempotent density set. */
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration).apply {
            densityDpi = DisplayMetrics.DENSITY_MEDIUM
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureImmersive()
        settings = SettingsStore(this)

        view = PooyanView(this, game, particles)
        view.highScore = settings.highScore
        val root = BinocularSbsLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(view)
        }
        setContentView(root)

        gestures.setScreenSize(640, 480)
        gestures.onDrag = { _, dy -> game.moveLiftBy(dy * DRAG_GAIN) }
        gestures.onTap = { game.onTap() }
        gestures.onSwipeHorizontal = { direction ->
            // flick LEFT hurls meat toward the wolves (they're always to the left)
            if (direction < 0) game.onMeatGesture()
        }
        gestures.onLongTap = { openRayNeoControlCenter() }

        wireGameEvents()
        sfx.volume = if (settings.sound) 0.65f else 0f
        sfx.loadAsync()

        // Debug/test hook: `adb shell am start … --es phase descent|ascent|bonus`
        // jumps straight into that phase for on-device verification.
        intent.getStringExtra("phase")?.let { jumpToPhase(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("phase")?.let { jumpToPhase(it) }
    }

    private fun jumpToPhase(name: String) {
        val p = when (name.lowercase()) {
            "descent" -> PooyanGame.Phase.DESCENT
            "ascent" -> PooyanGame.Phase.ASCENT
            "bonus" -> PooyanGame.Phase.BONUS
            else -> return
        }
        game.debugStart(p)
    }

    private fun wireGameEvents() {
        game.onStart = { sfx.play(Sfx.START) }
        game.onShoot = { sfx.play(Sfx.SHOOT) }
        game.onPop = { x, y, special ->
            sfx.play(Sfx.POP, pitch = if (special) 0.8f else 1f)
            particles.burst(x, y, if (special) PooyanView.GOLD else PooyanView.CYAN, 14, 200f)
        }
        game.onToughHit = { x, y ->
            sfx.play(Sfx.POP_TOUGH)
            particles.burst(x, y, PooyanView.GOLD, 5, 110f)
        }
        // Wolf powers. Each reuses the existing bank at a shifted pitch rather
        // than adding samples: the family of sounds stays recognisable, and a
        // dodge is audibly a cousin of a blocked hit rather than a new noise
        // the player has to learn from scratch.
        game.onWolfDodge = { x, y ->
            sfx.play(Sfx.POP_TOUGH, pitch = 1.5f, vol = 0.8f)
            particles.burst(x, y, PooyanView.WHITE, 8, 150f)
        }
        game.onWolfDive = { x, y ->
            sfx.play(Sfx.FALL, pitch = 0.7f)
            particles.burst(x, y, PooyanView.RED, 10, 170f)
        }
        game.onSplit = { x, y ->
            sfx.play(Sfx.POP, pitch = 1.45f)
            particles.burst(x, y, PooyanView.CYAN, 18, 220f)
        }
        game.onHowl = { x, y ->
            sfx.play(Sfx.RUMBLE, pitch = 1.5f, vol = 0.9f)
            particles.burst(x, y, PooyanView.GREEN, 22, 260f)
        }
        game.onPigletStolen = { x, y ->
            // A high, sharp cousin of the death sting: unmistakably bad, and
            // unmistakably not the sound of losing a life.
            sfx.play(Sfx.DEATH, pitch = 1.35f, vol = 0.85f)
            particles.burst(x, y, PooyanView.MAGENTA, 20, 200f)
        }
        game.onWolfFall = { sfx.play(Sfx.FALL) }
        game.onWolfLand = { x ->
            sfx.play(Sfx.THUD, vol = 0.7f)
            particles.burst(x, PooyanGame.GROUND_Y - 8f, PooyanView.DIM, 5, 90f)
        }
        game.onSplash = { x, y ->
            sfx.play(Sfx.THUD)
            particles.burst(x, y, PooyanView.MAGENTA, 16, 190f)
        }
        game.onRockThrow = { sfx.play(Sfx.ROCK_THROW, vol = 0.8f) }
        game.onRockBreak = { x, y ->
            sfx.play(Sfx.ROCK_BREAK)
            particles.burst(x, y, PooyanView.GREY, 10, 160f)
        }
        game.onMeatThrow = { sfx.play(Sfx.MEAT) }
        game.onChainGrab = { x, y, chain ->
            sfx.play(Sfx.CHAIN, pitch = 1f + (chain - 1) * 0.12f)
            particles.burst(x, y, PooyanView.ORANGE, 12, 170f)
        }
        game.onClimbTick = { sfx.play(Sfx.CLIMB, vol = 0.5f) }
        game.onDeath = { _ ->
            sfx.play(Sfx.DEATH)
            particles.burst(PooyanGame.LIFT_X, game.liftY, PooyanView.PINK, 30, 260f)
        }
        game.onBoulderPushed = { sfx.play(Sfx.RUMBLE) }
        game.onCrush = {
            sfx.play(Sfx.CRUSH)
            particles.burst(PooyanGame.LIFT_X, game.liftY, PooyanView.GREY, 28, 280f)
        }
        game.onRoundClear = {
            sfx.play(Sfx.CLEAR)
            persistHighScore()
        }
        game.onBonusStart = { sfx.play(Sfx.BONUS) }
        game.onExtraLife = { sfx.play(Sfx.LIFE) }
        game.onRescue = { sfx.play(Sfx.RESCUE) }
        game.onGameOver = {
            sfx.play(Sfx.OVER)
            persistHighScore()
        }
    }

    private fun persistHighScore() {
        settings.highScore = game.score
        view.highScore = settings.highScore
    }

    private fun configureImmersive() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window.decorView.setBackgroundColor(Color.BLACK)
    }

    // ~frame-rate loop; dt-based physics keeps motion speed-correct.
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrameMs == 0L) 0f else ((now - lastFrameMs) / 1000f).coerceAtMost(0.05f)
            lastFrameMs = now
            game.update(dt)
            particles.update(dt)
            view.setFrameTime(now)
            view.invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onResume() {
        super.onResume()
        running = true
        lastFrameMs = 0L
        Choreographer.getInstance().removeFrameCallback(frame)
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onPause() {
        super.onPause()
        running = false
        // Sleep button fires onPause mid-wear — pause the game, don't lose it.
        if (game.state == PooyanGame.State.PLAYING) game.togglePause()
    }

    override fun onDestroy() {
        super.onDestroy()
        gestures.release()
        sfx.release()
    }

    /** Open the genuine X3 launcher panel, with HOME as a firmware-safe escape. */
    private fun openRayNeoControlCenter() {
        val controlCenter = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("mercury://com.ffalconxr.mercury.launcher/openApp/shortcut")
        ).addCategory(Intent.CATEGORY_DEFAULT)
        runCatching { startActivity(controlCenter) }
            .onFailure {
                val home = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(home); finishAndRemoveTask() }
            }
    }

    // Temple FIRM-click arrives as a KEY — check first so nothing swallows it.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gestures.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (gestures.onTouchEvent(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (gestures.onGenericMotion(ev)) return true
        return super.dispatchGenericMotionEvent(ev)
    }
}
