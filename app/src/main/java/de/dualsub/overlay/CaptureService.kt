package de.dualsub.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Reads the subtitle another app is showing, translates it, and draws the
 * translation above it.
 *
 * Capture the bottom of the screen every 300 ms -> keep only near-white pixels
 * (subtitle text) -> if the set of text cells changed, OCR the text's bounding
 * box with Tesseract -> DeepL on a separate thread -> Overlay.
 *
 * `adb logcat -s DualSubOcr` shows every line with its timings.
 */
class CaptureService : Service() {

    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private val net = Executors.newSingleThreadExecutor()
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    // One Tesseract per core in use: subtitle lines are OCR'd in parallel.
    private var ocrPool = Executors.newFixedThreadPool(DEFAULT_ENGINES)
    private var engines: List<TessBaseAPI> = emptyList()
    // English engines, loaded in the background the first time an English line
    // turns up, so German-only viewing never pays for them.
    @Volatile private var enginesEn: List<TessBaseAPI> = emptyList()
    private var englishLoading = false
    private var engineCount = DEFAULT_ENGINES
    private lateinit var translator: Translator
    private lateinit var overlay: Overlay

    private var screenH = 0
    // Highest top edge any German subtitle has reached, in screen pixels. The
    // English always sits above it, so a taller next subtitle never runs into it.
    private var anchorTopPx = 0
    private var scale = 1f
    private var width = 0
    private var height = 0
    private var bandTop = 0
    private var lastSignature = 0L
    private var lastQuick = 0L
    @Volatile private var lastGerman = ""     // the last line read, whichever language
    private var lastSource = "DE"
    // A middling-confidence read, accepted only if the next read agrees.
    private var pendingGerman = ""
    private var lastWasEmpty = true
    @Volatile private var generation = 0
    private var lines = 0
    // Rejected reads in a row. Menus and home screens produce nothing but
    // these; past JUNK_STREAK the band is checked only once a second.
    private var junkStreak = 0
    private var retrySig = 0L
    private var retries = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        Diag.init(this)
        Direction.load(this)
        if (intent == null || projection != null) return START_NOT_STICKY

        scale = intent.getFloatExtra(EXTRA_SCALE, 1f).coerceIn(0.25f, 1f)
        // OpenMP threads per Tesseract engine. Two engines x 2 threads fills the
        // four cores; the default (4 each) makes them fight.
        val omp = intent.getIntExtra(EXTRA_OMP, DEFAULT_OMP)
        if (omp > 0) android.system.Os.setenv("OMP_NUM_THREADS", omp.toString(), true)
        engineCount = intent.getIntExtra(EXTRA_ENGINES, DEFAULT_ENGINES).coerceIn(1, 4)
        ocrPool.shutdown()
        ocrPool = Executors.newFixedThreadPool(engineCount)

        if (intent.getBooleanExtra(EXTRA_BENCH, false)) {
            worker = HandlerThread("ocr").also { it.start() }
            handler = Handler(worker.looper)
            handler.post {
                try { bench(omp, engineCount) } catch (e: Exception) { Diag.error("Benchmark failed", e) }
                stopSelf()
            }
            return START_NOT_STICKY
        }
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent = intent.getParcelableExtra(EXTRA_DATA) ?: return START_NOT_STICKY
        running = true

        worker = HandlerThread("ocr").also { it.start() }
        handler = Handler(worker.looper)
        translator = Translator(filesDir)
        net.execute { translator.warmUp() }     // open the TLS connection before the first line
        overlay = Overlay(this).also { it.attach() }
        if (!translator.ready) Diag.error("No DeepL key (files/deepl_key): nothing will be translated")

        val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        screenH = bounds.height()
        anchorTopPx = (screenH * DEFAULT_ANCHOR).toInt()
        width = (bounds.width() * scale).toInt()
        height = (bounds.height() * scale).toInt()
        bandTop = (height * BAND_FROM).toInt()
        Diag.resetSession()
        Diag.info("Started (${Direction.label()}): capture ${width}x$height, every ${INTERVAL_MS} ms, " +
                "$engineCount OCR engine(s) x $omp threads")

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(code, data) ?: run {
            Diag.error("Screen capture not granted"); stopSelf(); return START_NOT_STICKY
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)

        val ir = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = ir
        display = mp.createVirtualDisplay("dualsub-ocr", width, height,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir.surface, null, handler)

        handler.post {
            val t = SystemClock.elapsedRealtime()
            engines = List(engineCount) { initTesseract() }
            Diag.info("OCR ready in ${SystemClock.elapsedRealtime() - t} ms")
            handler.postDelayed(tick, INTERVAL_MS)
        }
        return START_NOT_STICKY
    }

    private val tick = object : Runnable {
        override fun run() {
            try { poll() } catch (e: Exception) { Diag.error("Reading the screen failed", e) }
            handler.postDelayed(this, if (junkStreak >= JUNK_STREAK) IDLE_INTERVAL_MS else INTERVAL_MS)
        }
    }

    private fun poll() {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val image = reader?.acquireLatestImage() ?: return   // screen unchanged
        val frameNanos = image.timestamp
        val bandH = height - bandTop
        // Cheap look first: every 4th pixel of every 4th row. Only when that
        // changes is the band converted in full and OCR'd.
        val quick = try { quickSignature(image, bandH) } catch (e: Exception) { image.close(); throw e }
        if (quick == lastQuick) { image.close(); return }
        lastQuick = quick
        val pixels = IntArray(width * bandH)
        var bright = 0
        var minX = Int.MAX_VALUE; var maxX = -1; var minY = Int.MAX_VALUE; var maxY = -1
        val gridW = (width + CELL_W - 1) / CELL_W
        val cells = IntArray(gridW * ((bandH + CELL_H - 1) / CELL_H))
        val rowText = IntArray(bandH)
        try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val row = ByteArray(rowStride)
            for (y in 0 until bandH) {
                buf.position((bandTop + y) * rowStride)
                buf.get(row, 0, minOf(rowStride, buf.remaining()))
                var i = 0
                for (x in 0 until width) {
                    val a = row[i + 3].toInt() and 0xFF
                    // Prime's capture is transparent where the video is: flatten onto black.
                    val r = (row[i].toInt() and 0xFF) * a / 255
                    val g = (row[i + 1].toInt() and 0xFF) * a / 255
                    val b = (row[i + 2].toInt() and 0xFF) * a / 255
                    i += pixelStride
                    val isText = isSubtitlePixel(r, g, b)
                    val v = if (isText) 0 else 255     // Tesseract wants dark on light
                    pixels[y * width + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                    if (isText) {
                        bright++
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                        cells[(y / CELL_H) * gridW + x / CELL_W]++
                        rowText[y]++
                    }
                }
            }
        } finally {
            image.close()
        }
        val tCopy = SystemClock.elapsedRealtimeNanos()

        // Subtitles are centred; menu rows and channel banners mostly are not.
        val centred = bright >= MIN_BRIGHT &&
                kotlin.math.abs((minX + maxX) / 2 - width / 2) <= width * CENTRE_TOLERANCE
        if (bright >= MIN_BRIGHT && !centred) junkStreak++
        // A clear band is what video looks like between two subtitles; menus
        // almost never have one. Back to full speed, so the first line after
        // browsing is caught at once -- an empty band costs only the sparse check.
        if (bright < MIN_BRIGHT) junkStreak = 0
        if (!centred) {
            if (!lastWasEmpty) {
                overlay.clearAfter(LINGER_MS)
                generation++
            }
            lastWasEmpty = true
            lastSignature = 0
            lastQuick = 0
            lastGerman = ""
            return
        }
        var sig = 1125899906842597L
        for ((n, c) in cells.withIndex()) if (c > CELL_ON) sig = sig * 31 + n
        if (sig == lastSignature) return
        lastSignature = sig
        lastWasEmpty = false
        // The German is changing. The old English stays up until the new
        // translation replaces it.
        generation++

        if (engines.isEmpty()) return
        val rows = textLines(rowText, minY, maxY)
        // Read with the model for the language expected: the fixed direction, or
        // in Auto the previous line's. Only when the read says otherwise is it
        // read again with the other model -- once per switch, not per line.
        val wantEnglish = when (Direction.mode) {
            Direction.EN_TO_DE -> true
            Direction.DE_TO_EN -> false
            else -> lastSource == "EN"
        }
        if (wantEnglish) ensureEnglish()
        var english = wantEnglish && enginesEn.isNotEmpty()
        var results = ocrLines(pixels, width, bandH, minX, maxX, rows, if (english) enginesEn else engines)
        if (Direction.mode == Direction.AUTO) {
            when (Direction.detect(results.joinToString(" ") { it.first })) {
                "EN" -> if (!english) {
                    ensureEnglish()
                    if (enginesEn.isNotEmpty()) {
                        results = ocrLines(pixels, width, bandH, minX, maxX, rows, enginesEn)
                        english = true
                    }
                }
                "DE" -> if (english) {
                    results = ocrLines(pixels, width, bandH, minX, maxX, rows, engines)
                    english = false
                }
            }
        }
        val confidence = results.filter { it.first.isNotEmpty() }.minOfOrNull { it.second } ?: 0
        val tOcr = SystemClock.elapsedRealtimeNanos()

        val germanLines = results.map { it.first }.filter { it.isNotEmpty() }
        val german = germanLines.joinToString("\n")
        if (german.isEmpty()) return
        // Fading subtitles and the player's own controls (progress bar, clock)
        // come out as low-confidence or mostly-symbol junk.
        val letters = german.count { it.isLetter() }
        val mostlyLetters = letters >= german.count { !it.isWhitespace() } * MIN_LETTER_SHARE
        // Clean lines score 91-96. Fade-in/out frames score lower but never read
        // the same twice, so a middling score counts once two reads agree --
        // which rescues real lines Tesseract is merely unsure of ("'tschuldigung").
        val confirmed = confidence >= MIN_CONFIDENCE_TWICE && german == pendingGerman
        // English reads score lower than German ones (82-86 through the German
        // model, before the English one has loaded). A line made of real
        // English words gets a lower bar; garbled fade frames rarely contain any.
        val minConfidence = if (Direction.detect(german) == "EN") MIN_CONFIDENCE_EN else MIN_CONFIDENCE
        val plausible = mostlyLetters && (confidence >= minConfidence || confirmed)
        if (!plausible) {
            Diag.skippedRead()
            Diag.info("? skipped, ${confidence}% sure: ${german.replace("\n", " / ").take(70)}")
            pendingGerman = if (mostlyLetters) german else ""
            junkStreak++
            // While watching, say "something is there, couldn't read it" rather
            // than leave the previous line's English up. Not on menus: once
            // they have tipped it into slow mode, show nothing.
            // A blurred read of the line already up (it is fading out) leaves
            // its English alone.
            if (junkStreak < JUNK_STREAK) {
                if (!resembles(german, lastGerman)) {
                    overlay.show(UNCLEAR, englishBottom((bandTop + minY) / scale))
                }
            } else if (junkStreak == JUNK_STREAK) {
                overlay.clear()
            }
            if (sig == retrySig) retries++ else { retrySig = sig; retries = 1 }
            // A fading subtitle reads cleanly a frame or two later, so look
            // again -- but only twice: a static banner would otherwise be
            // re-read every tick forever.
            if (retries <= MAX_RETRIES) {
                lastSignature = 0
                lastQuick = 0
            }
            return
        }
        pendingGerman = ""
        junkStreak = 0
        retries = 0
        if (german == lastGerman) {
            // Same line as before (e.g. a fade glitch in between): put it back.
            translator.cached(forTranslation(germanLines), lastSource)?.let {
                overlay.show(it, englishBottom((bandTop + minY) / scale))
            }
            return
        }
        lastGerman = german
        val gen = ++generation
        lines++
        val n = lines

        val bottomPx = englishBottom((bandTop + minY) / scale)

        val ocrMs = ms(tCopy, tOcr)
        val copyMs = ms(t0, tCopy)
        val shown = german.replace('\n', '|')
        val query = forTranslation(germanLines)
        val source = Direction.sourceOf(query, lastSource)
        lastSource = source
        val dir = if (source == "EN") "EN→DE" else "DE→EN"
        translator.cached(query, source)?.let { english ->
            overlay.show(english, bottomPx)
            val age = ageMs(frameNanos)
            Diag.shown(age)
            Diag.info("#$n ${secs(age)} $dir (cached)  ${shown.replace("|", " / ")}  →  ${english.replace('\n', ' ')}")
            return
        }
        if (net.isShutdown) return
        net.execute {
            val tNet = SystemClock.elapsedRealtimeNanos()
            val english = translator.translate(query, source)
            val deeplMs = ms(tNet, SystemClock.elapsedRealtimeNanos())
            // A newer generation is fine if it is still the same German line.
            if (english == null || (gen != generation && german != lastGerman)) {
                if (english == null) Diag.warn("#$n not translated: ${shown.replace("|", " / ")}")
                else Diag.info("#$n dropped, screen moved on before its English arrived: " +
                        shown.replace("|", " / "))
                return@execute
            }
            overlay.show(english, bottomPx)
            val age = ageMs(frameNanos)
            Diag.shown(age)
            Diag.info("#$n ${secs(age)} $dir (ocr ${secs(ocrMs)}, deepl ${secs(deeplMs)})  " +
                    "${shown.replace("|", " / ")}  →  ${english.replace('\n', ' ')}")
        }
    }

    /** Start loading the English engines, once, off the OCR thread. */
    private fun ensureEnglish() {
        if (enginesEn.isNotEmpty() || englishLoading) return
        englishLoading = true
        Thread {
            val t = SystemClock.elapsedRealtime()
            val loaded = try {
                List(engineCount) { initTesseract("eng") }
            } catch (e: Exception) {
                Diag.error("English OCR failed to load", e); emptyList()
            }
            handler.post {
                if (running) {
                    enginesEn = loaded
                    if (loaded.isNotEmpty()) Diag.info("English OCR ready in ${SystemClock.elapsedRealtime() - t} ms")
                } else {
                    loaded.forEach { it.recycle() }
                }
                englishLoading = false
            }
        }.start()
    }

    /** OCR each subtitle line, spread over the engines in parallel. */
    private fun ocrLines(pixels: IntArray, width: Int, bandH: Int, minX: Int, maxX: Int,
                         rows: List<IntRange>,
                         engines: List<TessBaseAPI> = this.engines): List<Pair<String, Int>> {
        val pad = (12 * width / 1920).coerceAtLeast(4)
        val x0 = (minX - pad).coerceAtLeast(0)
        val boxW = (maxX + pad).coerceAtMost(width - 1) - x0 + 1
        val jobs: List<Future<List<Pair<String, Int>>>> = engines.indices.map { e ->
            ocrPool.submit<List<Pair<String, Int>>> {
                rows.withIndex().filter { it.index % engines.size == e }.map { (_, r) ->
                    val y0 = (r.first - pad).coerceAtLeast(0)
                    val h = (r.last + pad).coerceAtMost(bandH - 1) - y0 + 1
                    val bmp = Bitmap.createBitmap(pixels, y0 * width + x0, width, boxW, h,
                        Bitmap.Config.ARGB_8888)
                    val api = engines[e]
                    api.setImage(bmp)
                    val text = (api.utF8Text ?: "").replace('\n', ' ').trim()
                    val conf = api.meanConfidence()
                    api.clear()
                    text to conf
                }
            }
        }
        val perEngine = jobs.map { it.get() }
        return rows.indices.map { i -> perEngine[i % engines.size][i / engines.size] }
    }

    /**
     * Same OCR as live, on saved screenshots in files/bench/: settings can be
     * compared on identical input instead of whatever happens to be on screen.
     */
    private fun bench(omp: Int, engineCount: Int) {
        engines = List(engineCount) { initTesseract() }
        val files = File(filesDir, "bench").listFiles { f -> f.name.endsWith(".png") }
            ?.sortedBy { it.name } ?: emptyList()
        val all = ArrayList<Long>()
        for (f in files) {
            val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath) ?: continue
            val w = bmp.width
            val top = (bmp.height * BAND_FROM).toInt()
            val bandH = bmp.height - top
            val src = IntArray(w * bandH)
            bmp.getPixels(src, 0, w, 0, top, w, bandH)
            val pixels = IntArray(w * bandH)
            val rowText = IntArray(bandH)
            var minX = Int.MAX_VALUE; var maxX = -1; var minY = Int.MAX_VALUE; var maxY = -1
            for (y in 0 until bandH) for (x in 0 until w) {
                val c = src[y * w + x]
                val a = (c ushr 24) and 0xFF
                val r = ((c shr 16) and 0xFF) * a / 255
                val g = ((c shr 8) and 0xFF) * a / 255
                val b = (c and 0xFF) * a / 255
                val isText = isSubtitlePixel(r, g, b)
                pixels[y * w + x] = if (isText) 0xFF000000.toInt() else -1
                if (isText) {
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                    rowText[y]++
                }
            }
            if (maxX < 0) continue
            val rows = textLines(rowText, minY, maxY)
            val times = (0 until BENCH_REPS).map {
                val t = SystemClock.elapsedRealtimeNanos()
                val res = ocrLines(pixels, w, bandH, minX, maxX, rows)
                val ms = ms(t, SystemClock.elapsedRealtimeNanos())
                if (it == 0) Log.i(TAG, "bench ${f.name}: ${rows.size} lines \"" +
                        res.joinToString("|") { p -> p.first } + "\"")
                ms
            }.drop(1)       // first run warms caches
            all += times
            Log.i(TAG, "bench ${f.name}: median ${times.sorted()[times.size / 2]}ms")
        }
        val s = all.sorted()
        if (s.isNotEmpty()) Log.i(TAG, "BENCH omp=$omp engines=$engineCount: median ${s[s.size / 2]}ms " +
                "mean ${s.average().toInt()}ms max ${s.last()}ms over ${s.size} runs")
    }

    private fun quickSignature(image: android.media.Image, bandH: Int): Long {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val gridW = (width + CELL_W - 1) / CELL_W
        val cells = IntArray(gridW * ((bandH + CELL_H - 1) / CELL_H))
        var y = 0
        while (y < bandH) {
            val base = (bandTop + y) * rowStride
            var x = 0
            while (x < width) {
                val i = base + x * pixelStride
                val a = buf.get(i + 3).toInt() and 0xFF
                val r = (buf.get(i).toInt() and 0xFF) * a / 255
                val g = (buf.get(i + 1).toInt() and 0xFF) * a / 255
                val b = (buf.get(i + 2).toInt() and 0xFF) * a / 255
                if (isSubtitlePixel(r, g, b)) {
                    cells[(y / CELL_H) * gridW + x / CELL_W]++
                }
                x += 4
            }
            y += 4
        }
        var sig = 1125899906842597L
        for ((n, c) in cells.withIndex()) if (c > 0) sig = sig * 31 + n * 7 + minOf(c, 6)
        return sig
    }

    /** Distance from the screen bottom for the English, above the highest German seen. */
    private fun englishBottom(germanTopPx: Float): Int {
        anchorTopPx = minOf(anchorTopPx, germanTopPx.toInt())
        return screenH - anchorTopPx + GAP_PX
    }

    /**
     * Rows [minY..maxY] -> one range per subtitle line, split at blank gaps.
     * Short runs (umlaut dots, a stray accent) are folded into the next line.
     */
    private fun textLines(rowText: IntArray, minY: Int, maxY: Int): List<IntRange> {
        val runs = ArrayList<IntRange>()
        var start = -1
        var blank = 0
        for (y in minY..maxY) {
            if (rowText[y] > 0) {
                if (start < 0) start = y
                blank = 0
            } else if (start >= 0) {
                blank++
                if (blank >= LINE_GAP) { runs.add(start until y - blank + 1); start = -1; blank = 0 }
            }
        }
        if (start >= 0) runs.add(start..maxY)
        val merged = ArrayList<IntRange>()
        var carry: Int? = null
        for (r in runs) {
            val first = carry ?: r.first
            if (r.last - r.first + 1 < MIN_LINE_H) { carry = first; continue }
            merged.add(first..r.last)
            carry = null
        }
        if (carry != null) {
            if (merged.isEmpty()) merged.add(carry..maxY)
            else merged[merged.size - 1] = merged.last().first..maxY
        }
        return merged
    }


    /**
     * A sentence wrapped over two lines translates better as one line; two
     * speakers ('- Sorry.' / '- Digga, ...') must stay apart.
     */
    private fun forTranslation(lines: List<String>) =
        if (lines.any { it.startsWith("-") }) lines.joinToString("\n")
        else lines.joinToString(" ")

    /** Do at least 40% of [current]'s words turn up in [read]? */
    private fun resembles(read: String, current: String): Boolean {
        val words = current.lowercase().split(Regex("\\W+")).filter { it.length > 1 }
        if (words.isEmpty()) return false
        val seen = read.lowercase().split(Regex("\\W+")).toSet()
        return words.count { it in seen } >= words.size * 0.4
    }

    private fun secs(ms: Long) = String.format(java.util.Locale.ROOT, "%.1fs", ms / 1000.0)

    private fun ageMs(frameNanos: Long) = (System.nanoTime() - frameNanos) / 1_000_000

    private fun initTesseract(lang: String = "deu"): TessBaseAPI {
        val dir = File(filesDir, "tessdata").apply { mkdirs() }
        val model = File(dir, "$lang.traineddata")
        if (!model.exists()) {
            assets.open("tessdata/$lang.traineddata").use { input ->
                model.outputStream().use { input.copyTo(it) }
            }
        }
        return TessBaseAPI().apply {
            if (!init(filesDir.absolutePath, lang, TessBaseAPI.OEM_LSTM_ONLY)) {
                throw IllegalStateException("tesseract init failed")
            }
            pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
        }
    }

    private fun ms(a: Long, b: Long) = (b - a) / 1_000_000

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("capture", "Subtitle capture", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "capture")
            .setContentTitle("DualSub translating subtitles")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    override fun onDestroy() {
        if (::overlay.isInitialized) overlay.detach()
        net.shutdownNow()
        ocrPool.shutdown()
        // Tear down on the worker: an OCR call may be running there right now,
        // and freeing Tesseract or the ImageReader under it segfaults.
        if (::handler.isInitialized) {
            handler.removeCallbacksAndMessages(null)
            handler.post {
                display?.release()
                reader?.close()
                projection?.stop()
                ocrPool.awaitTermination(3, TimeUnit.SECONDS)
                engines.forEach { it.recycle() }
                engines = emptyList()
                enginesEn.forEach { it.recycle() }
                enginesEn = emptyList()
                val chars = if (::translator.isInitialized) translator.charsSent else 0
                running = false
                Diag.info("Stopped after $lines lines, $chars characters sent to DeepL")
                worker.quitSafely()
            }
        }
        super.onDestroy()
    }

    companion object {
        const val TAG = "DualSubOcr"
        const val EXTRA_RESULT_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_SCALE = "scale"
        const val EXTRA_OMP = "omp"
        const val EXTRA_ENGINES = "engines"
        const val EXTRA_BENCH = "bench"
        private const val DEFAULT_OMP = 2
        @Volatile var running = false
            private set
        private const val NOTIFICATION_ID = 1
        private const val INTERVAL_MS = 200L
        private const val IDLE_INTERVAL_MS = 1000L
        private const val UNCLEAR = "~"
        private const val JUNK_STREAK = 5
        private const val MAX_RETRIES = 2
        private const val CENTRE_TOLERANCE = 0.12f  // text centre within 12% of screen centre
        private const val BAND_FROM = 0.70f     // bottom 30% of the screen
        private const val MIN_BRIGHT = 150      // fewer text pixels = no subtitle
        private const val CHANNEL_ON = 170      // a subtitle colour channel is fully on...
        private const val CHANNEL_OFF = 90      // ...or fully off

        /**
         * Subtitle colours are teletext's pure ones -- white, yellow, cyan, green,
         * magenta: every channel fully on or fully off. ARD colours speakers this
         * way. Our own overlay yellow (FFE27A) has blue at half (0x7A) and so is
         * never read back in; picture content is mostly mixed tones too.
         */
        fun isSubtitlePixel(r: Int, g: Int, b: Int): Boolean {
            if (maxOf(r, maxOf(g, b)) <= CHANNEL_ON) return false
            return (r > CHANNEL_ON || r < CHANNEL_OFF) &&
                    (g > CHANNEL_ON || g < CHANNEL_OFF) &&
                    (b > CHANNEL_ON || b < CHANNEL_OFF)
        }
        private const val CELL_W = 32
        private const val CELL_H = 16
        private const val CELL_ON = 12
        private const val GAP_PX = 16
        private const val LINGER_MS = 2500L       // English stays this long after the German goes
        private const val DEFAULT_ANCHOR = 0.80f   // until seen otherwise: room for two German lines
        private const val MIN_CONFIDENCE = 88      // clean lines score 91-96, fade glitches <85
        private const val MIN_CONFIDENCE_TWICE = 75
        private const val MIN_CONFIDENCE_EN = 80
        private const val DEFAULT_ENGINES = 2     // live, one engine leaves two-line subtitles ~1.7 s behind
        private const val BENCH_REPS = 6
        private const val LINE_GAP = 6             // blank rows that separate two lines
        private const val MIN_LINE_H = 14          // shorter runs are dots/accents
        private const val MIN_LETTER_SHARE = 0.4   // "- Was... Ey..." is ~55% letters; "07:56 AV" far less
    }
}
