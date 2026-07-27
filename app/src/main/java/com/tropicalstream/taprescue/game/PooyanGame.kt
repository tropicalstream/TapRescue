package com.tropicalstream.taprescue.game

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * TapRescue — an original, from-scratch remake of the classic 1982 "wolves on
 * balloons" fixed shooter, tuned for the RayNeo X3 Pro's temple pad.
 *
 * A level = two rounds + a bonus stage:
 *
 *  ROUND 1 — THE DESCENT. Wolves leap off the top ledge and float DOWN on
 *  balloons. Pop the balloon and the wolf plummets. Any wolf that lands runs
 *  to the ladders under Mama's lift and climbs; if a climber draws level with
 *  the lift, Mama is EATEN (life lost — round 1 only). Airborne wolves hurl
 *  rocks at the lift; a rock hit costs a life; arrows shatter rocks.
 *
 *  ROUND 2 — THE ASCENT. Wolves inflate balloons and rise from the den toward
 *  the high cliff where a boulder rests. Every wolf that reaches the cliff
 *  joins the pusher gang; when SEVEN gather they shove the boulder over the
 *  edge — it thunders along the top and drops straight down the lift shaft,
 *  CRUSHING Mama (life lost — round 2 only). Rocks still fly.
 *
 *  BONUS — wolves rise in formation; only meat works; chain them for points.
 *
 *  MEAT: earned every 8 balloon kills (max 3). Thrown in an arc; every wolf
 *  it passes grabs at it, loses its balloon and falls — chains multiply.
 *
 * Pure logic — no Android imports. The view renders this state; MainActivity
 * owns input and sound (via the event callbacks at the bottom).
 */
class PooyanGame {

    companion object {
        const val W = 640f
        const val H = 480f

        // Lift geometry (right side). The shaft is where everything hunts Mama.
        const val LIFT_X = 596f
        const val LIFT_MIN_Y = 104f
        const val LIFT_MAX_Y = 416f
        const val LIFT_HALF = 24f

        // Terrain rows.
        const val LEDGE_Y = 78f       // round-1 jump ledge / round-2 boulder cliff
        const val GROUND_Y = 442f

        // Round-2 boulder party.
        const val PUSHERS_NEEDED = 7

        const val MEAT_MAX = 3
        const val KILLS_PER_MEAT = 8

        const val EXTRA_LIFE_FIRST = 20_000
        const val EXTRA_LIFE_EVERY = 50_000
        const val LIVES_MAX = 5

        /** Length of the opening abduction cutscene. Tap skips it. */
        const val STORY_SECS = 7.4f
    }

    enum class State { ATTRACT, STORY, ROUND_INTRO, PLAYING, LIFE_LOST, ROUND_CLEAR, GAME_OVER, PAUSED }
    enum class Phase { DESCENT, ASCENT, BONUS }
    enum class WolfMode { BALLOON, FALLING, WALKING, CLIMBING, PUSHER, ESCAPED }

    class Wolf(
        var x: Float, var y: Float,
        var mode: WolfMode = WolfMode.BALLOON,
        var hp: Int = 1,                 // balloon hits left (special wolves: 2)
        val special: Boolean = false,
        var vx: Float = 0f, var vy: Float = 0f,
        var sway: Float = Random.nextFloat() * 6.28f,
        var rockTimer: Float = 1.5f + Random.nextFloat() * 3f,
        var grabbed: Boolean = false     // already lured by the current meat
    )

    class Arrow(var x: Float, var y: Float)
    class Rock(var x: Float, var y: Float, var vx: Float, var vy: Float)
    class Meat(var x: Float, var y: Float, var vx: Float, var vy: Float, var chain: Int = 0)

    // ---- public game state (the view reads these) ----
    var state = State.ATTRACT; private set
    var phase = Phase.DESCENT; private set
    var level = 1; private set
    var score = 0; private set
    var lives = 5; private set
    var meat = 2; private set
    var liftY = 260f; private set
    var stateTimer = 0f; private set
    var pushers = 0; private set
    var boulderActive = false; private set
    var boulderX = 110f; private set
    var boulderY = LEDGE_Y - 26f; private set
    var pigletsFreed = 0; private set
    var deathCause = ""; private set

    val wolves = ArrayList<Wolf>()
    val arrows = ArrayList<Arrow>()
    val rocks = ArrayList<Rock>()
    val meats = ArrayList<Meat>()

    // ---- events (MainActivity wires sound/particles) ----
    var onShoot: (() -> Unit)? = null
    var onPop: ((x: Float, y: Float, special: Boolean) -> Unit)? = null
    var onToughHit: ((x: Float, y: Float) -> Unit)? = null
    var onWolfFall: (() -> Unit)? = null
    var onWolfLand: ((x: Float) -> Unit)? = null
    var onSplash: ((x: Float, y: Float) -> Unit)? = null
    var onRockThrow: (() -> Unit)? = null
    var onRockBreak: ((x: Float, y: Float) -> Unit)? = null
    var onMeatThrow: (() -> Unit)? = null
    var onChainGrab: ((x: Float, y: Float, chain: Int) -> Unit)? = null
    var onClimbTick: (() -> Unit)? = null
    var onDeath: ((cause: String) -> Unit)? = null
    var onBoulderPushed: (() -> Unit)? = null
    var onCrush: (() -> Unit)? = null
    var onRoundClear: (() -> Unit)? = null
    var onBonusStart: (() -> Unit)? = null
    var onExtraLife: (() -> Unit)? = null
    var onGameOver: (() -> Unit)? = null
    var onStart: (() -> Unit)? = null
    var onRescue: (() -> Unit)? = null

    private var spawnQueue = 0
    private var spawnTimer = 0f
    private var shootCooldown = 0f
    private var killsTowardMeat = 0
    private var nextLifeAt = EXTRA_LIFE_FIRST
    private var boulderVX = 0f
    private var boulderFalling = false
    private var rescuedThisLevel = false
    private val rng = Random(System.nanoTime())

    // ------------------------------------------------------------------ input

    fun moveLiftBy(dy: Float) {
        if (state != State.PLAYING) return
        liftY = (liftY + dy).coerceIn(LIFT_MIN_Y, LIFT_MAX_Y)
    }

    /** Tap: start from attract/game-over, else fire an arrow (meat in bonus). */
    fun onTap() {
        when (state) {
            State.ATTRACT -> startGame()
            State.STORY -> beginPhase(Phase.DESCENT)     // tap to skip the cutscene
            State.GAME_OVER -> { state = State.ATTRACT; stateTimer = 0f }
            State.PAUSED -> state = State.PLAYING
            State.PLAYING -> if (phase == Phase.BONUS) throwMeat() else shoot()
            else -> {}
        }
    }

    /** Horizontal flick: hurl a meat slab (round play only; bonus taps do it). */
    fun onMeatGesture() {
        if (state == State.PLAYING && phase != Phase.BONUS) throwMeat()
    }

    fun togglePause() {
        state = when (state) {
            State.PLAYING -> State.PAUSED
            State.PAUSED -> State.PLAYING
            else -> return
        }
    }

    /** Debug/test hook (adb only): start a fresh game directly in [p]. */
    fun debugStart(p: Phase) {
        level = 1; score = 0; lives = 5; meat = 2
        killsTowardMeat = 0; nextLifeAt = EXTRA_LIFE_FIRST; pigletsFreed = 0
        onStart?.invoke()
        beginPhase(p)
    }

    // ------------------------------------------------------------------ flow

    private fun startGame() {
        level = 1; score = 0; lives = 5; meat = 2
        killsTowardMeat = 0; nextLifeAt = EXTRA_LIFE_FIRST; pigletsFreed = 0
        onStart?.invoke()
        // The abduction plays first: the player should SEE the wolves take the
        // piglets, so round 1 is a rescue they already care about rather than
        // an abstract shooting gallery.
        state = State.STORY
        stateTimer = 0f
    }

    private fun beginPhase(p: Phase) {
        phase = p
        wolves.clear(); arrows.clear(); rocks.clear(); meats.clear()
        pushers = 0
        boulderActive = false; boulderFalling = false
        boulderX = 110f; boulderY = LEDGE_Y - 26f
        rescuedThisLevel = false
        liftY = 260f
        spawnQueue = when (p) {
            Phase.DESCENT -> 10 + level * 2
            Phase.ASCENT -> 12 + level * 2
            Phase.BONUS -> 10
        }
        spawnTimer = 0.8f
        if (p == Phase.BONUS) { meat = MEAT_MAX; onBonusStart?.invoke() }
        state = State.ROUND_INTRO
        stateTimer = 0f
    }

    private fun phaseCleared() {
        state = State.ROUND_CLEAR
        stateTimer = 0f
        addScore(1000)
        onRoundClear?.invoke()
        if (phase == Phase.ASCENT && !rescuedThisLevel) {
            rescuedThisLevel = true
            pigletsFreed++
            onRescue?.invoke()
        }
    }

    private fun advancePhase() {
        when (phase) {
            Phase.DESCENT -> beginPhase(Phase.ASCENT)
            Phase.ASCENT -> beginPhase(Phase.BONUS)
            Phase.BONUS -> { level++; beginPhase(Phase.DESCENT) }
        }
    }

    private fun loseLife(cause: String) {
        deathCause = cause
        lives--
        state = State.LIFE_LOST
        stateTimer = 0f
        onDeath?.invoke(cause)
        if (lives <= 0) {
            // linger in LIFE_LOST briefly; update() rolls into GAME_OVER
        }
    }

    private fun respawn() {
        // Clear immediate threats; the wave itself continues.
        arrows.clear(); rocks.clear(); meats.clear()
        boulderActive = false; boulderFalling = false
        boulderX = 110f; boulderY = LEDGE_Y - 26f
        // Climbers and pushers scatter after a kill — one fresh chance.
        wolves.removeAll { it.mode == WolfMode.CLIMBING || it.mode == WolfMode.WALKING || it.mode == WolfMode.PUSHER }
        pushers = 0
        liftY = 260f
        state = State.PLAYING
    }

    private fun addScore(points: Int) {
        score += points
        if (score >= nextLifeAt) {
            nextLifeAt = if (nextLifeAt == EXTRA_LIFE_FIRST) EXTRA_LIFE_EVERY else nextLifeAt + EXTRA_LIFE_EVERY
            if (lives < LIVES_MAX) { lives++; onExtraLife?.invoke() }
        }
    }

    // ------------------------------------------------------------------ combat

    private fun shoot() {
        if (shootCooldown > 0f) return
        shootCooldown = 0.26f
        arrows.add(Arrow(LIFT_X - 26f, liftY))
        onShoot?.invoke()
    }

    private fun throwMeat() {
        if (meat <= 0) return
        meat--
        meats.add(Meat(LIFT_X - 20f, liftY - 6f, -250f, -130f))
        onMeatThrow?.invoke()
    }

    // ------------------------------------------------------------------ update

    fun update(dt: Float) {
        stateTimer += dt
        when (state) {
            State.STORY -> if (stateTimer > STORY_SECS) beginPhase(Phase.DESCENT)
            State.ROUND_INTRO -> if (stateTimer > 2.0f) { state = State.PLAYING; stateTimer = 0f }
            State.ROUND_CLEAR -> if (stateTimer > 2.4f) advancePhase()
            State.LIFE_LOST -> if (stateTimer > 2.2f) {
                if (lives <= 0) { state = State.GAME_OVER; stateTimer = 0f; onGameOver?.invoke() }
                else respawn()
            }
            State.PLAYING -> updatePlaying(dt)
            else -> {}
        }
    }

    private fun updatePlaying(dt: Float) {
        if (shootCooldown > 0f) shootCooldown -= dt
        spawnWolves(dt)
        updateWolves(dt)
        updateArrows(dt)
        updateRocks(dt)
        updateMeats(dt)
        updateBoulder(dt)

        // Wave complete: queue empty and nothing threatening left in flight.
        // Pushers who never mustered the full gang just flee at round end, so
        // they don't count; neither does a rolling boulder mid-drop.
        val active = wolves.count {
            it.mode == WolfMode.BALLOON || it.mode == WolfMode.FALLING ||
                it.mode == WolfMode.WALKING || it.mode == WolfMode.CLIMBING
        }
        if (spawnQueue == 0 && active == 0 && !boulderActive) phaseCleared()
    }

    private fun spawnWolves(dt: Float) {
        if (spawnQueue <= 0) return
        spawnTimer -= dt
        if (spawnTimer > 0f) return
        val interval = (when (phase) {
            Phase.DESCENT -> 3.0f
            Phase.ASCENT -> 2.8f
            Phase.BONUS -> 1.0f
        } - level * 0.08f).coerceAtLeast(1.3f)
        spawnTimer = interval * (0.75f + rng.nextFloat() * 0.5f)
        spawnQueue--

        val special = phase != Phase.BONUS && rng.nextFloat() < (0.06f + level * 0.02f).coerceAtMost(0.3f)
        when (phase) {
            Phase.DESCENT -> wolves.add(Wolf(
                x = 60f + rng.nextFloat() * 380f, y = LEDGE_Y + 10f,
                hp = if (special) 2 else 1, special = special,
                vy = (17f + level * 2f) * (0.9f + rng.nextFloat() * 0.4f)
            ))
            Phase.ASCENT -> wolves.add(Wolf(
                x = 70f + rng.nextFloat() * 360f, y = GROUND_Y - 8f,
                hp = if (special) 2 else 1, special = special,
                vy = -(20f + level * 2f) * (0.9f + rng.nextFloat() * 0.4f)
            ))
            Phase.BONUS -> wolves.add(Wolf(
                x = 140f + (spawnQueue % 5) * 70f, y = GROUND_Y - 8f,
                vy = -46f
            ))
        }
    }

    private fun updateWolves(dt: Float) {
        val it = wolves.iterator()
        while (it.hasNext()) {
            val w = it.next()
            when (w.mode) {
                WolfMode.BALLOON -> {
                    w.sway += dt * 2.2f
                    w.x += sin(w.sway) * 14f * dt
                    w.y += w.vy * dt
                    if (phase != Phase.BONUS) maybeThrowRock(w, dt)
                    when (phase) {
                        Phase.DESCENT -> if (w.y >= GROUND_Y - 14f) {
                            w.mode = WolfMode.WALKING; w.y = GROUND_Y - 14f
                            onWolfLand?.invoke(w.x)
                        }
                        Phase.ASCENT, Phase.BONUS -> if (w.y <= LEDGE_Y + 16f) {
                            if (phase == Phase.ASCENT && w.x < 220f) {
                                w.mode = WolfMode.PUSHER
                                pushers++
                                if (pushers >= PUSHERS_NEEDED && !boulderActive) pushBoulder()
                            } else {
                                // drifted past the cliff (or bonus): escapes off the top
                                w.mode = WolfMode.ESCAPED
                                it.remove()
                            }
                        }
                    }
                    // ascenders drift toward the cliff as they climb the sky
                    if (phase == Phase.ASCENT && w.y < 260f && w.x > 180f) w.x -= 26f * dt
                }
                WolfMode.FALLING -> {
                    w.vy += 500f * dt
                    w.y += w.vy * dt
                    if (w.y >= GROUND_Y - 6f) {
                        onSplash?.invoke(w.x, GROUND_Y - 6f)
                        it.remove()
                    }
                }
                WolfMode.WALKING -> {
                    w.x += 75f * dt
                    if (w.x >= LIFT_X - 6f) { w.mode = WolfMode.CLIMBING; w.x = LIFT_X - 6f; w.sway = 0f }
                }
                WolfMode.CLIMBING -> {
                    w.y -= (15f + level * 1.5f) * dt
                    w.sway += dt
                    if (w.sway > 0.55f) { w.sway = 0f; onClimbTick?.invoke() }
                    // Drew level with the lift → Mama is eaten (round 1 death).
                    if (abs(w.y - liftY) < LIFT_HALF + 6f) {
                        loseLife("EATEN BY A WOLF")
                        return
                    }
                    if (w.y <= LIFT_MIN_Y - 30f) { it.remove() }   // leapt off the top, gone
                }
                WolfMode.PUSHER, WolfMode.ESCAPED -> { /* parked at the boulder / gone */ }
            }
        }
    }

    private fun maybeThrowRock(w: Wolf, dt: Float) {
        w.rockTimer -= dt
        if (w.rockTimer > 0f) return
        w.rockTimer = 5.0f + rng.nextFloat() * 3.5f - level * 0.15f
        if (rng.nextFloat() > (0.22f + level * 0.04f).coerceAtMost(0.6f)) return
        // Lob a rock at where Mama is right now.
        val dx = LIFT_X - w.x; val dy = liftY - w.y
        val d = hypot(dx, dy).coerceAtLeast(1f)
        val speed = 115f + level * 7f
        rocks.add(Rock(w.x + 10f, w.y, dx / d * speed, dy / d * speed - 30f))
        onRockThrow?.invoke()
    }

    private fun updateArrows(dt: Float) {
        val it = arrows.iterator()
        outer@ while (it.hasNext()) {
            val a = it.next()
            a.x -= 430f * dt
            if (a.x < -20f) { it.remove(); continue }
            // arrows vs rocks — protective shooting
            val rit = rocks.iterator()
            while (rit.hasNext()) {
                val r = rit.next()
                if (hypot(a.x - r.x, a.y - r.y) < 14f) {
                    rit.remove(); it.remove()
                    addScore(50)
                    onRockBreak?.invoke(r.x, r.y)
                    continue@outer
                }
            }
            // arrows vs wolves ON FOOT — walkers and climbers.
            //
            // These used to be invulnerable to everything: arrows and meat both
            // skipped any wolf that was not still on a balloon. A wolf that
            // landed therefore could not be stopped, and it could not be fled
            // either — Mama's ceiling is LIFT_MIN_Y while a climber carries on
            // to LIFT_MIN_Y - 30, so it always passes through her. Every wolf
            // that touched the ground was an unavoidable lost life with no
            // counterplay whatsoever. Shooting one off the ladder is the whole
            // point of the arcade original.
            // Iterate with an explicit iterator: removing from `wolves` inside
            // a for-in over the same list throws ConcurrentModificationException.
            val fit = wolves.iterator()
            while (fit.hasNext()) {
                val w = fit.next()
                if (w.mode != WolfMode.WALKING && w.mode != WolfMode.CLIMBING) continue
                if (hypot(a.x - w.x, a.y - w.y) < 18f) {
                    it.remove()
                    fit.remove()
                    addScore(if (w.mode == WolfMode.CLIMBING) 300 else 200)
                    onPop?.invoke(w.x, w.y, w.special)
                    continue@outer
                }
            }
            // arrows vs balloons
            for (w in wolves) {
                if (w.mode != WolfMode.BALLOON) continue
                val bx = w.x; val by = w.y - 22f   // balloon sits above the wolf
                if (hypot(a.x - bx, a.y - by) < 20f) {
                    it.remove()
                    w.hp--
                    if (w.hp <= 0) popBalloon(w) else onToughHit?.invoke(bx, by)
                    continue@outer
                }
            }
        }
    }

    private fun popBalloon(w: Wolf) {
        w.mode = WolfMode.FALLING
        w.vy = 40f
        // Higher pops (round 1) / longer falls (round 2) score more.
        val heightBonus = (((GROUND_Y - w.y) / (GROUND_Y - LEDGE_Y)) * 300f).toInt().coerceIn(0, 300)
        addScore((if (w.special) 400 else 100) + heightBonus)
        killsTowardMeat++
        if (killsTowardMeat >= KILLS_PER_MEAT) {
            killsTowardMeat = 0
            if (meat < MEAT_MAX) meat++
        }
        onPop?.invoke(w.x, w.y - 22f, w.special)
        onWolfFall?.invoke()
    }

    private fun updateRocks(dt: Float) {
        val it = rocks.iterator()
        while (it.hasNext()) {
            val r = it.next()
            r.vy += 60f * dt
            r.x += r.vx * dt
            r.y += r.vy * dt
            if (r.x > W + 20f || r.y > H + 20f) { it.remove(); continue }
            // rock vs Mama's lift
            if (abs(r.x - LIFT_X) < 20f && abs(r.y - liftY) < LIFT_HALF) {
                it.remove()
                loseLife("HIT BY A ROCK")
                return
            }
        }
    }

    private fun updateMeats(dt: Float) {
        val it = meats.iterator()
        while (it.hasNext()) {
            val m = it.next()
            m.vy += 210f * dt
            m.x += m.vx * dt
            m.y += m.vy * dt
            if (m.x < -30f || m.y > H + 20f) { it.remove(); continue }
            for (w in wolves) {
                if (w.mode != WolfMode.BALLOON || w.grabbed) continue
                if (hypot(m.x - w.x, m.y - w.y) < 52f) {
                    w.grabbed = true
                    m.chain++
                    addScore(500 * m.chain)
                    onChainGrab?.invoke(w.x, w.y, m.chain)
                    popBalloonByMeat(w)
                }
            }
        }
    }

    private fun popBalloonByMeat(w: Wolf) {
        w.mode = WolfMode.FALLING
        w.vy = 30f
        killsTowardMeat++
        if (killsTowardMeat >= KILLS_PER_MEAT) {
            killsTowardMeat = 0
            if (meat < MEAT_MAX) meat++
        }
        onWolfFall?.invoke()
    }

    private fun pushBoulder() {
        boulderActive = true
        boulderVX = 30f
        onBoulderPushed?.invoke()
    }

    private fun updateBoulder(dt: Float) {
        if (!boulderActive) return
        if (!boulderFalling) {
            boulderVX += 160f * dt              // gathering speed along the ledge
            boulderX += boulderVX * dt
            if (boulderX >= LIFT_X) {
                boulderX = LIFT_X
                boulderFalling = true
                boulderVX = 0f
            }
        } else {
            boulderVX += 640f * dt              // reuse as fall speed
            boulderY += boulderVX * dt
            // The boulder sweeps the whole shaft — reaching the lift crushes Mama.
            if (boulderY >= liftY - LIFT_HALF && boulderY < liftY + LIFT_HALF + 30f) {
                onCrush?.invoke()
                loseLife("CRUSHED BY THE BOULDER")
                return
            }
            if (boulderY > H + 40f) {
                boulderActive = false; boulderFalling = false
                boulderX = 110f; boulderY = LEDGE_Y - 26f
                pushers = 0
            }
        }
    }
}
