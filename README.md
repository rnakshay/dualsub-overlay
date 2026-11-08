# DualSub Overlay

An Android TV app that puts an English translation above the German subtitles
of **any** app — Prime Video, YouTube, anything that draws its subtitles as
plain white text.

It reads the subtitle off the screen, translates it with DeepL, and draws the
English in yellow just above the German. A faint `● dualsub` in the top-left
corner shows it is on.

## How it works

1. **Capture** — a MediaProjection stream of the screen. Protected video (Prime)
   comes through black, but the app's subtitles do not: ideal for OCR.
2. **Detect** — every 150 ms a sparse sample of the bottom 30% is checked; only
   when the near-white text in it changes is the band converted in full.
3. **OCR** — the text's bounding box is split into lines, and the lines are read
   in parallel by two Tesseract engines (German `tessdata_fast` model).
   Low-confidence reads — fading subtitles, the player's own controls — are
   dropped unless two reads in a row agree.
4. **Translate** — DeepL, over a kept-alive connection, with a cache for lines
   that recur. Two speakers (`- …` / `- …`) stay on separate lines; a sentence
   wrapped over two lines is translated as one.
5. **Overlay** — the English sits above the tallest German seen so far, so a
   new two-line subtitle never runs into it, and stays 2.5 s after the German
   goes. Only near-white pixels count as subtitle, so the yellow overlay is
   never read back in.

Measured on a TCL Android 11 TV (Realtek, 4× Cortex-A55 @ 1.3 GHz, 32-bit):
OCR median ~0.5 s per subtitle; the English typically appears ~1 s after the
German. About 70 MB of RAM; CPU near idle between lines, two to three cores
for about half a second when a new line is read.

Why Tesseract and not ML Kit: ML Kit's OCR library dies with `SIGILL` on this
TV's 32-bit kernel.

## Build and install

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# skip the screen-capture consent dialog and allow drawing over other apps
adb shell appops set de.dualsub.overlay PROJECT_MEDIA allow
adb shell appops set de.dualsub.overlay SYSTEM_ALERT_WINDOW allow
```

The DeepL key lives in the app's private storage, `files/deepl_key`. Put it
there without it ever appearing as a command-line argument, e.g. from a file
already on the TV:

```
adb shell 'run-as de.dualsub.overlay mkdir -p files;
           cat /path/on/tv/key.txt | run-as de.dualsub.overlay sh -c "cat > files/deepl_key"'
```

(`run-as` works because this is a debug build.)

## Use

Open **DualSub Overlay** and press **Turn on** — it returns to whatever was
playing. Open it again to turn it off. From adb:

```
adb shell am start -n de.dualsub.overlay/.StartActivity --es action start
adb shell am start -n de.dualsub.overlay/.StartActivity --es action stop
adb logcat -s DualSubOcr          # every line, with its timings
```

## Benchmark

Same OCR as live, on saved screenshots in `files/bench/*.png`, so settings can
be compared on identical input:

```
adb shell am start -n de.dualsub.overlay/.StartActivity \
    --es action bench --ei omp 2 --ei engines 2
adb logcat -s DualSubOcr | grep BENCH
```

`omp` is OpenMP threads per engine. 2 engines × 2 threads (the default) was
fastest on the TV above; 4 threads per engine, the library default, was nearly
twice as slow.

## Credits

German OCR model: `deu.traineddata` from
[tesseract-ocr/tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast)
(Apache 2.0). OCR via
[Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android).
