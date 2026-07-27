package com.tropicalstream.x3pooyan.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.HandlerThread
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthesized SFX bank for X3Pooyan — every sound is generated at startup
 * (no audio binaries ship): arrow twangs, balloon pops, falling-wolf whistles,
 * rock thunks, meat whooshes with chain chimes, the boulder rumble-and-crash,
 * and little fanfares. Same SoundPool-on-a-worker pattern as TapPong /
 * TapMeteors (SoundPool binder calls stay off the render thread).
 */
class Sfx(private val context: Context) {

    companion object {
        const val SHOOT = 0        // arrow loosed
        const val POP = 1          // balloon burst
        const val POP_TOUGH = 2    // special balloon absorbs a hit
        const val FALL = 3         // wolf falling whistle
        const val THUD = 4         // wolf lands / splash
        const val ROCK_THROW = 5   // wolf hurls a rock
        const val ROCK_BREAK = 6   // arrow shatters a rock
        const val MEAT = 7         // meat thrown (arcing whoosh)
        const val CHAIN = 8        // wolf grabs at meat and drops
        const val CLIMB = 9        // ladder tick
        const val DEATH = 10       // mama hit (rock / eaten)
        const val RUMBLE = 11      // boulder pushed, rolling
        const val CRUSH = 12       // boulder lands on the lift
        const val CLEAR = 13       // round clear fanfare
        const val BONUS = 14       // bonus stage jingle
        const val LIFE = 15        // extra life
        const val OVER = 16        // game over
        const val START = 17       // game start fanfare
        const val TICK = 18        // menu tick
        const val RESCUE = 19      // piglet freed
        private const val BANK = 20
        private const val RATE = 22050
    }

    private val pool = SoundPool.Builder()
        .setMaxStreams(10)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    private val ids = IntArray(BANK)
    @Volatile private var loaded = false
    @Volatile var volume = 0.65f
    private val rng = Random(11)

    // SoundPool calls are binder calls; keep them off the render thread.
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    fun loadAsync() {
        thread = HandlerThread("pooyan-sfx").apply { start() }
        handler = Handler(thread!!.looper)
        handler?.post {
            runCatching {
                val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
                // Arrow: taut string twang — bright square snapping down.
                ids[SHOOT] = load(dir, "shoot", buf(120) { t ->
                    sq(900f - 500f * t, t) * exp(-t * 22f) * 0.4f
                })
                // Balloon pop: white-noise burst with a sine "bip" heart.
                ids[POP] = load(dir, "pop", buf(140) { t ->
                    (noise() * 0.6f * exp(-t * 40f)) + sine(660f, t) * exp(-t * 28f) * 0.3f
                })
                // Tough balloon absorbing a hit: dull rubbery boing.
                ids[POP_TOUGH] = load(dir, "poptough", buf(160) { t ->
                    sine(220f + 60f * sin(2f * PI.toFloat() * 18f * t), t) * exp(-t * 14f) * 0.5f
                })
                // Falling wolf: descending slide-whistle.
                ids[FALL] = load(dir, "fall", buf(520) { t ->
                    sine(1150f - 900f * t, t) * exp(-t * 3.2f) * 0.32f
                })
                ids[THUD] = load(dir, "thud", buf(150) { t ->
                    (sine(90f, t) * 0.7f + noise() * 0.25f) * exp(-t * 24f)
                })
                ids[ROCK_THROW] = load(dir, "rockthrow", buf(140) { t ->
                    saw(180f - 60f * t, t) * exp(-t * 18f) * 0.4f
                })
                ids[ROCK_BREAK] = load(dir, "rockbreak", buf(180) { t ->
                    noise() * exp(-t * 26f) * 0.55f + sq(300f, t) * exp(-t * 35f) * 0.2f
                })
                // Meat: airy rising-falling whoosh.
                ids[MEAT] = load(dir, "meat", buf(420) { t ->
                    noise() * 0.28f * exp(-t * 4f) * (0.4f + sin(PI.toFloat() * t / 0.42f)) +
                        sine(500f + 300f * sin(PI.toFloat() * t / 0.42f), t) * 0.12f * exp(-t * 4f)
                })
                ids[CHAIN] = load(dir, "chain", buf(120) { t ->
                    sine(880f, t) * exp(-t * 18f) * 0.4f + sine(1320f, t) * exp(-t * 22f) * 0.2f
                })
                ids[CLIMB] = load(dir, "climb", buf(60) { t -> sq(340f, t) * exp(-t * 45f) * 0.3f })
                // Mama hit: sad two-step slide down.
                ids[DEATH] = load(dir, "death", buf(1000) { t ->
                    val f = if (t < 0.45f) 520f - t * 360f else 340f - (t - 0.45f) * 260f
                    (sine(f, t) * 0.5f + saw(f * 0.5f, t) * 0.25f) * exp(-t * 2.4f)
                })
                // Boulder: low grinding rumble.
                ids[RUMBLE] = load(dir, "rumble", buf(1400) { t ->
                    (sine(55f + 12f * sin(2f * PI.toFloat() * 7f * t), t) * 0.55f +
                        noise() * 0.22f * (0.5f + 0.5f * sin(2f * PI.toFloat() * 13f * t))) * exp(-t * 1.1f)
                })
                ids[CRUSH] = load(dir, "crush", buf(700) { t ->
                    (noise() * 0.7f + sine(60f, t) * 0.6f) * exp(-t * 6f)
                })
                ids[CLEAR] = load(dir, "clear", arpeggio(intArrayOf(523, 659, 784, 1046), 85, 0.8f))
                ids[BONUS] = load(dir, "bonus", arpeggio(intArrayOf(659, 784, 988, 1318, 988, 1318), 65, 0.7f))
                ids[LIFE] = load(dir, "life", arpeggio(intArrayOf(784, 988, 1175, 1568), 60, 0.8f))
                ids[OVER] = load(dir, "over", arpeggio(intArrayOf(659, 523, 392, 330, 262), 130, 0.7f))
                ids[START] = load(dir, "start", arpeggio(intArrayOf(392, 523, 659, 784, 1046), 75, 0.75f))
                ids[TICK] = load(dir, "tick", buf(70) { t -> sine(990f, t) * exp(-t * 30f) * 0.3f })
                ids[RESCUE] = load(dir, "rescue", arpeggio(intArrayOf(1046, 1318, 1568, 2093), 55, 0.7f))
                loaded = true
            }
        }
    }

    /** Safe from any thread. */
    fun play(id: Int, pitch: Float = 1f, vol: Float = 1f) {
        if (!loaded || id < 0 || id >= BANK) return
        handler?.post {
            val s = ids[id]
            if (s == 0) return@post
            val v = (volume * vol).coerceIn(0f, 1f)
            if (v <= 0f) return@post
            pool.play(s, v, v, 1, 0, pitch.coerceIn(0.5f, 2f))
        }
    }

    fun release() {
        handler?.post { runCatching { pool.release() } }
        thread?.quitSafely()
        thread = null
        handler = null
    }

    // ------------------------------------------------------------ synthesis

    private fun buf(ms: Int, gen: (Float) -> Float): ShortArray {
        val n = RATE * ms / 1000
        return ShortArray(n) { i -> (gen(i.toFloat() / RATE).coerceIn(-1f, 1f) * 30000f).toInt().toShort() }
    }

    private fun sine(f: Float, t: Float) = sin(2.0 * PI * f * t).toFloat()
    private fun saw(f: Float, t: Float): Float { val p = (f * t) % 1f; return 2f * p - 1f }
    private fun sq(f: Float, t: Float) = if ((f * t) % 1f < 0.5f) 1f else -1f
    private fun noise() = rng.nextFloat() * 2f - 1f

    private fun arpeggio(freqs: IntArray, noteMs: Int, amp: Float): ShortArray {
        val total = noteMs * freqs.size + 240
        return buf(total) { t ->
            var v = 0f
            for ((i, f) in freqs.withIndex()) {
                val start = i * noteMs / 1000f
                if (t >= start) {
                    val lt = t - start
                    v += (sine(f.toFloat(), lt) + 0.3f * sine(f * 2f, lt)) * exp(-lt * 5.5f) * amp * 0.4f
                }
            }
            v
        }
    }

    // ------------------------------------------------------------- wav

    private fun DataOutputStream.wInt(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF) }
    private fun DataOutputStream.wShort(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }

    private fun load(dir: File, name: String, pcm: ShortArray): Int {
        val f = File(dir, "$name.wav")
        val dataLen = pcm.size * 2
        DataOutputStream(BufferedOutputStream(FileOutputStream(f))).use { o ->
            o.writeBytes("RIFF"); o.wInt(36 + dataLen); o.writeBytes("WAVE")
            o.writeBytes("fmt "); o.wInt(16); o.wShort(1); o.wShort(1)
            o.wInt(RATE); o.wInt(RATE * 2); o.wShort(2); o.wShort(16)
            o.writeBytes("data"); o.wInt(dataLen)
            for (s in pcm) o.wShort(s.toInt())
        }
        return pool.load(f.absolutePath, 1)
    }
}
