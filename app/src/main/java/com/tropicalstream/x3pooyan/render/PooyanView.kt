package com.tropicalstream.x3pooyan.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View
import com.tropicalstream.x3pooyan.game.Particles
import com.tropicalstream.x3pooyan.game.PooyanGame
import com.tropicalstream.x3pooyan.game.PooyanGame.Phase
import com.tropicalstream.x3pooyan.game.PooyanGame.State
import com.tropicalstream.x3pooyan.game.PooyanGame.WolfMode
import kotlin.math.min
import kotlin.math.sin

/**
 * All rendering for X3Pooyan on the 640×480 logical eye canvas — original
 * neon-vector art on pure black (waveguide-off): wireframe cliffs and ladders,
 * balloon wolves as angular magenta glyphs, Mama Pig in a golden gondola,
 * arcing meat, the boulder, caged piglets, and the HUD.
 *
 * Glow is layered translucent strokes (no BlurMaskFilter) so everything stays
 * on the hardware-accelerated path BinocularSbsLayout's dual-draw needs.
 */
class PooyanView(
    context: Context,
    private val game: PooyanGame,
    private val particles: Particles
) : View(context) {

    companion object {
        const val CYAN = 0xFF00E5FF.toInt()
        const val MAGENTA = 0xFFFF2E97.toInt()
        const val GOLD = 0xFFFFD54F.toInt()
        const val GREEN = 0xFF69F0AE.toInt()
        const val PINK = 0xFFFF8AB4.toInt()
        const val ORANGE = 0xFFFF8A50.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val GREY = 0xFFB0BEC5.toInt()
        const val RED = 0xFFFF5252.toInt()
        const val DIM = 0x66FFFFFF
    }

    var highScore = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private var frameTime = 0L

    fun setFrameTime(t: Long) { frameTime = t }

    private val t: Float get() = frameTime / 1000f

    override fun onDraw(canvas: Canvas) {
        val s = min(width / PooyanGame.W, height / PooyanGame.H)
        canvas.save()
        canvas.scale(s, s)

        when (game.state) {
            State.ATTRACT -> drawAttract(canvas)
            State.GAME_OVER -> { drawWorld(canvas); drawGameOver(canvas) }
            State.PAUSED -> { drawWorld(canvas); drawPause(canvas) }
            else -> {
                drawWorld(canvas)
                if (game.state == State.ROUND_INTRO) drawRoundIntro(canvas)
                if (game.state == State.LIFE_LOST) drawLifeLost(canvas)
                if (game.state == State.ROUND_CLEAR) drawRoundClear(canvas)
            }
        }
        particles.draw(canvas)
        canvas.restore()
    }

    // ------------------------------------------------------------- attract

    private fun drawAttract(c: Canvas) {
        text.textAlign = Paint.Align.CENTER
        neonText(c, "X3 POOYAN", 320f, 130f, 52f, PINK)
        neonText(c, "MAMA PIG VS THE BALLOON WOLVES", 320f, 172f, 15f, CYAN)

        // a little scene: mama in her gondola + a wolf floating by
        drawLiftAt(c, 470f, 260f)
        drawBalloonWolf(c, 200f + sin(t * 0.9f) * 24f, 250f + sin(t * 0.7f) * 16f, false, 1f)
        drawBalloonWolf(c, 300f + sin(t * 1.1f) * 18f, 300f - sin(t * 0.8f) * 14f, true, 1f)
        drawPiglets(c)          // the cage, so the story below has a subject
        text.textAlign = Paint.Align.CENTER

        text.color = GOLD; text.textSize = 20f
        c.drawText("HIGH SCORE  ${highScore.toString().padStart(6, '0')}", 320f, 340f, text)

        if ((t * 2f).toInt() % 2 == 0) neonText(c, "TAP TO START", 320f, 416f, 24f, WHITE)
        text.color = PINK; text.textSize = 14f
        c.drawText("The wolves have caged your piglets.", 320f, 366f, text)
        c.drawText("Ride the lift. Pop every balloon. Bring them home.", 320f, 384f, text)

        text.color = DIM; text.textSize = 13f
        c.drawText("pad ↑↓ move · tap shoot · flick ← meat · 2×tap pause", 320f, 446f, text)
        c.drawText("clear a round to free a piglet", 320f, 464f, text)
    }

    // ------------------------------------------------------------- world

    private fun drawWorld(c: Canvas) {
        drawTerrain(c)
        drawPiglets(c)
        drawPushersAndBoulder(c)
        for (w in game.wolves) drawWolf(c, w)
        drawLiftAt(c, PooyanGame.LIFT_X, game.liftY)
        for (a in game.arrows) drawArrow(c, a.x, a.y)
        for (r in game.rocks) drawRock(c, r.x, r.y)
        for (m in game.meats) drawMeat(c, m.x, m.y)
        drawHud(c)
    }

    private fun drawTerrain(c: Canvas) {
        // ground
        stroke.color = GREEN; stroke.strokeWidth = 3f; stroke.alpha = 230
        c.drawLine(0f, PooyanGame.GROUND_Y, 640f, PooyanGame.GROUND_Y, stroke)
        glowLine(c, 0f, PooyanGame.GROUND_Y, 640f, PooyanGame.GROUND_Y, GREEN)

        // left/top cliff block (jump ledge in round 1, boulder cliff in round 2)
        val ledge = PooyanGame.LEDGE_Y
        stroke.color = ORANGE; stroke.strokeWidth = 2.5f; stroke.alpha = 210
        path.reset()
        path.moveTo(0f, ledge)
        path.lineTo(230f, ledge)
        path.lineTo(210f, ledge + 34f)
        path.lineTo(150f, ledge + 60f)
        path.lineTo(90f, ledge + 110f)
        path.lineTo(60f, ledge + 190f)
        path.lineTo(30f, ledge + 260f)
        path.lineTo(0f, ledge + 300f)
        c.drawPath(path, stroke)
        // jagged inner strata
        stroke.alpha = 70
        c.drawLine(20f, ledge + 40f, 150f, ledge + 24f, stroke)
        c.drawLine(14f, ledge + 120f, 80f, ledge + 96f, stroke)
        stroke.alpha = 255

        // right shaft: rails + ladder rungs
        stroke.color = CYAN; stroke.strokeWidth = 2f; stroke.alpha = 150
        val lx = PooyanGame.LIFT_X
        c.drawLine(lx - 16f, 88f, lx - 16f, PooyanGame.GROUND_Y, stroke)
        c.drawLine(lx + 16f, 88f, lx + 16f, PooyanGame.GROUND_Y, stroke)
        stroke.alpha = 80
        var y = 110f
        while (y < PooyanGame.GROUND_Y) {
            c.drawLine(lx - 16f, y, lx + 16f, y, stroke)
            y += 26f
        }
        stroke.alpha = 255
    }

    private fun drawPiglets(c: Canvas) {
        // caged piglets bottom-left; freed ones hop outside the cage
        val cx = 26f; val cy = PooyanGame.GROUND_Y - 34f
        stroke.color = CYAN; stroke.strokeWidth = 2f; stroke.alpha = 160
        c.drawRect(cx, cy, cx + 58f, cy + 32f, stroke)
        for (i in 0..3) c.drawLine(cx + 12f + i * 12f, cy, cx + 12f + i * 12f, cy + 32f, stroke)
        stroke.alpha = 255
        for (i in 0 until 3) {
            paint.color = PINK
            c.drawCircle(cx + 13f + i * 17f, cy + 20f, 6f, paint)
        }
        for (i in 0 until game.pigletsFreed.coerceAtMost(4)) {
            val hop = if ((t * 3f + i).toInt() % 2 == 0) 3f else 0f
            paint.color = PINK
            c.drawCircle(cx + 74f + i * 16f, cy + 22f - hop, 6f, paint)
        }
        // Say what the cage IS. Three pink dots behind bars read as scenery;
        // they are the reason Mama is up there at all, and the player has no
        // way to know that without being told.
        text.textAlign = Paint.Align.LEFT
        text.color = CYAN; text.textSize = 11f
        c.drawText("YOUR PIGLETS", cx, cy - 6f, text)
    }

    private fun drawPushersAndBoulder(c: Canvas) {
        if (game.phase != Phase.ASCENT) return
        // gathered pushers marching in place beside the boulder
        for (i in 0 until game.pushers) {
            drawWolfHead(c, 150f + i * 16f, PooyanGame.LEDGE_Y - 10f, 7f)
        }
        // the boulder
        paint.color = GREY
        c.drawCircle(game.boulderX, game.boulderY, 22f, paint)
        stroke.color = 0xFF546E7A.toInt(); stroke.strokeWidth = 2f
        c.drawCircle(game.boulderX, game.boulderY, 22f, stroke)
        c.drawLine(game.boulderX - 9f, game.boulderY - 5f, game.boulderX + 3f, game.boulderY + 7f, stroke)
        c.drawLine(game.boulderX + 2f, game.boulderY - 10f, game.boulderX + 8f, game.boulderY - 1f, stroke)
        // pusher-count meter under the cliff
        text.textAlign = Paint.Align.LEFT
        text.color = if (game.pushers >= 5) RED else ORANGE
        text.textSize = 13f
        c.drawText("WOLVES AT BOULDER ${game.pushers}/${PooyanGame.PUSHERS_NEEDED}", 12f, PooyanGame.LEDGE_Y - 44f, text)
        text.textAlign = Paint.Align.CENTER
    }

    // ------------------------------------------------------------- actors

    private fun drawWolf(c: Canvas, w: PooyanGame.Wolf) {
        when (w.mode) {
            WolfMode.BALLOON -> drawBalloonWolf(c, w.x, w.y, w.special, w.hp / 2f)
            WolfMode.FALLING -> {
                c.save()
                c.rotate(sin(t * 14f) * 24f, w.x, w.y)
                drawWolfBody(c, w.x, w.y)
                c.restore()
            }
            WolfMode.WALKING -> drawWolfBody(c, w.x, w.y, running = true)
            WolfMode.CLIMBING -> {
                drawWolfBody(c, w.x, w.y)
                // hungry eyes flash as it climbs
                paint.color = RED
                c.drawCircle(w.x - 4f, w.y - 8f, 1.8f, paint)
                c.drawCircle(w.x + 2f, w.y - 8f, 1.8f, paint)
            }
            else -> {}
        }
    }

    private fun drawBalloonWolf(c: Canvas, x: Float, y: Float, special: Boolean, hpFrac: Float) {
        // balloon
        val col = if (special) GOLD else CYAN
        stroke.color = col; stroke.strokeWidth = 2.4f
        glowCircle(c, x, y - 26f, 15f, col)
        c.drawCircle(x, y - 26f, 15f, stroke)
        if (special && hpFrac > 0.5f) c.drawCircle(x, y - 26f, 10f, stroke)   // double ring = 2 hits
        // string
        stroke.strokeWidth = 1.4f; stroke.alpha = 170
        c.drawLine(x, y - 11f, x, y - 2f, stroke)
        stroke.alpha = 255
        drawWolfBody(c, x, y + 8f)
    }

    private fun drawWolfBody(c: Canvas, x: Float, y: Float, running: Boolean = false) {
        // Filled silhouette, not an outline sketch. On a waveguide a thin
        // magenta wireframe reads as noise; a solid body with a bright rim
        // holds its shape at speed and against the neon terrain.
        val legKick = if (running) sin(t * 16f) * 3.5f else 0f

        // tail — first, so the body overlaps its root
        stroke.color = MAGENTA; stroke.strokeWidth = 2.6f
        path.reset()
        path.moveTo(x - 10f, y + 2f)
        path.quadTo(x - 18f, y - 2f, x - 15f, y - 10f)
        c.drawPath(path, stroke)

        // body: a rounded haunch tapering to the chest
        paint.color = MAGENTA
        path.reset()
        path.moveTo(x - 11f, y + 2f)
        path.quadTo(x - 12f, y + 12f, x - 4f, y + 13f)
        path.lineTo(x + 7f, y + 13f)
        path.quadTo(x + 13f, y + 11f, x + 12f, y + 2f)
        path.quadTo(x + 4f, y - 3f, x - 11f, y + 2f)
        path.close()
        c.drawPath(path, paint)

        // legs
        stroke.color = MAGENTA; stroke.strokeWidth = 3f
        c.drawLine(x - 6f, y + 12f, x - 7f - legKick, y + 20f, stroke)
        c.drawLine(x + 6f, y + 12f, x + 7f + legKick, y + 20f, stroke)

        // head: muzzle wedge + two upright ears
        paint.color = MAGENTA
        path.reset()
        path.moveTo(x + 4f, y - 2f)
        path.quadTo(x + 14f, y - 6f, x + 20f, y + 1f)   // snout
        path.lineTo(x + 14f, y + 5f)
        path.quadTo(x + 8f, y + 5f, x + 4f, y - 2f)
        path.close()
        c.drawPath(path, paint)
        c.drawCircle(x + 6f, y - 4f, 7f, paint)          // skull

        path.reset()                                      // ears
        path.moveTo(x + 1f, y - 9f);  path.lineTo(x + 2f, y - 18f); path.lineTo(x + 7f, y - 10f)
        path.close(); c.drawPath(path, paint)
        path.reset()
        path.moveTo(x + 8f, y - 10f); path.lineTo(x + 12f, y - 17f); path.lineTo(x + 13f, y - 8f)
        path.close(); c.drawPath(path, paint)

        // eye + nose read the direction of travel at a glance
        paint.color = WHITE
        c.drawCircle(x + 8f, y - 5f, 2.1f, paint)
        paint.color = 0xFF10121A.toInt()
        c.drawCircle(x + 8.6f, y - 5f, 1.0f, paint)
        paint.color = WHITE
        c.drawCircle(x + 19f, y + 0.5f, 1.5f, paint)
    }

    private fun drawWolfHead(c: Canvas, x: Float, y: Float, r: Float) {
        stroke.color = MAGENTA; stroke.strokeWidth = 2f
        path.reset()
        path.moveTo(x - r, y - r)
        path.lineTo(x - r * 0.4f, y - r * 0.2f)
        path.lineTo(x, y - r)
        path.lineTo(x + r * 0.5f, y - r * 0.2f)
        path.lineTo(x + r, y + r * 0.4f)
        path.lineTo(x - r * 0.6f, y + r * 0.5f)
        path.close()
        c.drawPath(path, stroke)
    }

    private fun drawLiftAt(c: Canvas, x: Float, y: Float) {
        val h = PooyanGame.LIFT_HALF
        // gondola basket
        stroke.color = GOLD; stroke.strokeWidth = 2.6f
        glowRect(c, x - 18f, y - 4f, x + 18f, y + h, GOLD)
        c.drawRect(x - 18f, y - 4f, x + 18f, y + h, stroke)
        c.drawLine(x - 18f, y - 4f, x - 12f, y - h, stroke)
        c.drawLine(x + 18f, y - 4f, x + 12f, y - h, stroke)
        // MAMA PIG — she is the character the whole plot hangs on, so she
        // gets a readable face rather than a pink dot: full head, snout with
        // nostrils, ears, and an eye that looks left down her own firing line.
        paint.color = PINK
        c.drawCircle(x - 3f, y - 9f, 11f, paint)                 // head
        path.reset()                                              // ears
        path.moveTo(x - 12f, y - 15f); path.lineTo(x - 10f, y - 24f); path.lineTo(x - 4f, y - 16f)
        path.close(); c.drawPath(path, paint)
        path.reset()
        path.moveTo(x + 2f, y - 16f);  path.lineTo(x + 8f, y - 23f); path.lineTo(x + 6f, y - 14f)
        path.close(); c.drawPath(path, paint)

        paint.color = 0xFFE91E63.toInt()                          // snout
        c.drawOval(x - 17f, y - 11f, x - 7f, y - 4f, paint)
        paint.color = 0xFF7A1030.toInt()                          // nostrils
        c.drawCircle(x - 14.5f, y - 7.5f, 1.1f, paint)
        c.drawCircle(x - 10.5f, y - 7.5f, 1.1f, paint)

        paint.color = WHITE                                       // eye
        c.drawCircle(x - 5f, y - 12f, 2.6f, paint)
        paint.color = 0xFF10121A.toInt()
        c.drawCircle(x - 6.2f, y - 12f, 1.3f, paint)

        // bow: an arc + string facing left
        stroke.color = CYAN; stroke.strokeWidth = 2f
        c.drawArc(x - 26f, y - 14f, x - 12f, y + 2f, 90f, 180f, false, stroke)
        stroke.strokeWidth = 1.2f
        c.drawLine(x - 19f, y - 14f, x - 19f, y + 2f, stroke)
    }

    private fun drawArrow(c: Canvas, x: Float, y: Float) {
        stroke.color = WHITE; stroke.strokeWidth = 2.2f
        c.drawLine(x, y, x + 16f, y, stroke)
        paint.color = WHITE
        path.reset()
        path.moveTo(x - 3f, y)
        path.lineTo(x + 3f, y - 3.4f)
        path.lineTo(x + 3f, y + 3.4f)
        path.close()
        c.drawPath(path, paint)
    }

    private fun drawRock(c: Canvas, x: Float, y: Float) {
        paint.color = GREY
        path.reset()
        path.moveTo(x - 6f, y - 2f); path.lineTo(x - 2f, y - 6f); path.lineTo(x + 5f, y - 4f)
        path.lineTo(x + 6f, y + 3f); path.lineTo(x, y + 6f); path.close()
        c.drawPath(path, paint)
    }

    private fun drawMeat(c: Canvas, x: Float, y: Float) {
        // drumstick: meat blob + bone
        paint.color = 0xFFD2691E.toInt()
        c.drawCircle(x, y, 8f, paint)
        paint.color = ORANGE
        c.drawCircle(x - 2f, y - 2f, 5f, paint)
        stroke.color = WHITE; stroke.strokeWidth = 3f
        c.drawLine(x + 6f, y + 5f, x + 12f, y + 10f, stroke)
        paint.color = WHITE
        c.drawCircle(x + 13f, y + 11f, 2.6f, paint)
    }

    // ------------------------------------------------------------- HUD

    private fun drawHud(c: Canvas) {
        text.textAlign = Paint.Align.LEFT
        text.color = GOLD; text.textSize = 17f
        c.drawText("SCORE ${game.score.toString().padStart(6, '0')}", 10f, 26f, text)
        text.textAlign = Paint.Align.CENTER
        text.color = GREEN
        c.drawText("HI ${maxOf(highScore, game.score).toString().padStart(6, '0')}", 320f, 26f, text)
        text.textAlign = Paint.Align.RIGHT
        text.color = CYAN
        val phaseName = when (game.phase) {
            Phase.DESCENT -> "DESCENT"
            Phase.ASCENT -> "ASCENT"
            Phase.BONUS -> "BONUS"
        }
        c.drawText("L${game.level} $phaseName", 630f, 26f, text)

        // lives: little pig heads, bottom-left above the ground line
        for (i in 0 until game.lives) {
            paint.color = PINK
            c.drawCircle(16f + i * 20f, 466f, 6.5f, paint)
            paint.color = 0xFFE91E63.toInt()
            c.drawCircle(12f + i * 20f, 467f, 2.4f, paint)
        }
        // meat stock, bottom-right
        for (i in 0 until game.meat) {
            drawMeatIcon(c, 596f - i * 26f, 464f)
        }
        text.textAlign = Paint.Align.CENTER
    }

    private fun drawMeatIcon(c: Canvas, x: Float, y: Float) {
        paint.color = ORANGE
        c.drawCircle(x, y, 6f, paint)
        stroke.color = WHITE; stroke.strokeWidth = 2f
        c.drawLine(x + 4f, y + 3f, x + 8f, y + 7f, stroke)
    }

    // ------------------------------------------------------------- overlays

    private fun drawRoundIntro(c: Canvas) {
        dimBox(c)
        val title = when (game.phase) {
            Phase.DESCENT -> "ROUND ${game.level} · THE DESCENT"
            Phase.ASCENT -> "ROUND ${game.level} · THE ASCENT"
            Phase.BONUS -> "BONUS STAGE"
        }
        neonText(c, title, 320f, 225f, 30f, if (game.phase == Phase.BONUS) GOLD else CYAN)
        text.color = DIM; text.textSize = 15f
        val hint = when (game.phase) {
            Phase.DESCENT -> "they drop toward your cage — shoot them down!"
            Phase.ASCENT -> "seven at the cliff and they crush you — stop them!"
            Phase.BONUS -> "meat only — chain the pack for the piglets!"
        }
        c.drawText(hint, 320f, 258f, text)
    }

    private fun drawLifeLost(c: Canvas) {
        dimBox(c)
        neonText(c, game.deathCause, 320f, 225f, 28f, RED)
        text.color = DIM; text.textSize = 16f
        c.drawText(if (game.lives > 0) "${game.lives} left" else "", 320f, 258f, text)
    }

    private fun drawRoundClear(c: Canvas) {
        dimBox(c)
        neonText(c, if (game.phase == Phase.BONUS) "BONUS COMPLETE" else "ROUND CLEAR", 320f, 218f, 30f, GREEN)
        if (game.phase == Phase.ASCENT) {
            text.color = PINK; text.textSize = 17f
            c.drawText("a piglet is rescued! ♥", 320f, 252f, text)
        }
        text.color = GOLD; text.textSize = 16f
        c.drawText("+1000", 320f, 280f, text)
    }

    private fun drawPause(c: Canvas) {
        dimBox(c)
        neonText(c, "PAUSED", 320f, 225f, 34f, WHITE)
        text.color = DIM; text.textSize = 15f
        c.drawText("tap or double-tap to resume", 320f, 258f, text)
    }

    private fun drawGameOver(c: Canvas) {
        dimBox(c)
        neonText(c, "GAME OVER", 320f, 200f, 40f, MAGENTA)
        text.color = GOLD; text.textSize = 22f
        c.drawText("SCORE ${game.score.toString().padStart(6, '0')}", 320f, 244f, text)
        if (game.score >= highScore && game.score > 0) {
            neonText(c, "★ NEW HIGH SCORE ★", 320f, 278f, 20f, GREEN)
        }
        if ((t * 2f).toInt() % 2 == 0) {
            text.color = WHITE; text.textSize = 17f
            c.drawText("tap for title", 320f, 320f, text)
        }
    }

    // ------------------------------------------------------------- helpers

    private fun dimBox(c: Canvas) {
        paint.color = 0xB3000000.toInt()
        c.drawRect(60f, 165f, 580f, 300f, paint)
        stroke.color = DIM; stroke.strokeWidth = 2f
        c.drawRect(60f, 165f, 580f, 300f, stroke)
    }

    private fun neonText(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int) {
        text.textSize = size
        text.color = color
        text.alpha = 70
        c.drawText(s, x + 1.5f, y + 1.5f, text)
        text.alpha = 255
        c.drawText(s, x, y, text)
    }

    private fun glowLine(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        stroke.color = color; stroke.alpha = 46
        val w = stroke.strokeWidth
        stroke.strokeWidth = w * 3f
        c.drawLine(x1, y1, x2, y2, stroke)
        stroke.strokeWidth = w; stroke.alpha = 255
    }

    private fun glowCircle(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        stroke.color = color; stroke.alpha = 42
        val w = stroke.strokeWidth
        stroke.strokeWidth = w * 3f
        c.drawCircle(x, y, r, stroke)
        stroke.strokeWidth = w; stroke.alpha = 255
    }

    private fun glowRect(c: Canvas, l: Float, tp: Float, r: Float, b: Float, color: Int) {
        stroke.color = color; stroke.alpha = 40
        val w = stroke.strokeWidth
        stroke.strokeWidth = w * 3f
        c.drawRect(l, tp, r, b, stroke)
        stroke.strokeWidth = w; stroke.alpha = 255
    }
}
