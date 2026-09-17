# WhisperNote

Offline-first Android audio notes built with Kotlin, Jetpack Compose Material 3, and whisper.cpp v1.8.3 (commit `2eeeba56e9edd762b4b38467bab96c2517163158`).

## Run

Open this directory in Android Studio. Use JDK 17 or newer, Android SDK 36.1, NDK 28.2.13676358, and CMake 3.22.1. Build `:app:assembleDebug`. APK: `app/build/outputs/apk/debug/app-debug.apk`.

Android 7+ is supported on arm64 and x86_64. The APK contains the native engine but no model. Open **Models**, download Tiny, Base, or Small in Standard or Compact (Q5) format, then choose the same model and format under **New transcription**. All six variants are multilingual; your import selection is remembered. After the model download, local-file transcription needs no network. New model downloads are pinned to Hugging Face repository revision `5359861c739e955e79d9a303bcbc70fb988958b1` and verified against exact byte counts and upstream LFS SHA-256 hashes before publication. Existing standard-model files retain their original filenames and remain usable.

## Features

- Adaptive note grid, title/content search, jump to a matching transcript segment.
- Storage Access Framework imports with persistent URI access; a private copy is made only when the provider cannot grant persistent access.
- Direct HTTPS media downloads with progress, redirects restricted to HTTPS, timeouts, partial-file cleanup, and a 4 GB download cap.
- MediaExtractor/MediaCodec decoding, mono float PCM at 16 kHz, streaming resampling to temporary disk storage, native mmap input.
- Foreground processing queue, progress notification opening the note, failure messages, retry preserving audio, interrupted-job recovery on app restart.
- Finalized segments stream to SQLite and the UI during inference, with atomic millisecond resume checkpoints, automatic language detection, and title/segment autosave.
- Playback, seeking, ±10 seconds, tap-to-play timestamps, active-segment highlighting, optional follow playback.
- TXT, Markdown, SRT exports to a chosen document destination or Android sharing.
- Confirmation before deletion and optional deletion of app-owned audio. External source files are never deleted.
- System light/dark theme. Cloud backup and device migration of private app data are disabled.

## Architecture

- `MainActivity.kt`: Compose navigation, library, import/model sheets, detail view.
- `NotesViewModel.kt`: UI operations and ordered autosave commands.
- `core/Notes.kt`: immutable notes/segments, transactional SQLite repository with StateFlow, formatting/export. SQLite stores note documents; audio and model files live outside the database. This deliberately avoids annotation-processing coupling and can be replaced with Room behind the same repository boundary.
- `core/Audio.kt`: independently reusable downloader and decoder/resampler.
- `core/WhisperEngine.kt`, `cpp/bridge.cpp`: narrow JNI boundary and streaming inference.
- `core/ModelManager.kt`: Standard/Q5 catalog, persistent import preference, serialized downloads, integrity checks, atomic model publication.
- `core/ProcessingService.kt`: serialized foreground jobs, persistent status, platform timeout handling.
- `core/AudioPlayer.kt`: independent playback lifecycle and StateFlow.
- `cpp/whisper`: pinned upstream source; upstream MIT license and notices are retained.

## Verification

```
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
```

The connected test copies the upstream JFK WAV sample, decodes it, downloads Tiny once, performs real native inference, and checks language, timestamps, and recognized content. It requires internet for the first model download. Re-running with the model installed exercises inference without a download.

## Practical limitations

- Search currently scans local note metadata and transcript text; large libraries would benefit from SQLite FTS and paged queries.
- Media formats depend on the Android device's installed decoders. DRM, web pages, streaming playlists, and video-site extraction are unsupported.
- Large recordings require temporary disk space (~230 MB/hour of PCM) and sufficient RAM for the selected model. Small can be impractical on low-memory phones. Processing speed and accuracy depend on the device, language, and recording.
- Android can terminate work because of force-stop, low memory, or foreground-service time limits. Audio and finalized segments are preserved. Interrupted notes show the saved audio timestamp and a Resume button. Resume decodes the source again, reloads the model, and infers only the audio after the last saved segment. Previously saved text and edits are retained. An unfinished segment is recomputed; progress percentages are never used as checkpoints.
- Model downloads restart after interruption rather than resume. No background boot auto-start is attempted.
- Recording, diarization, word timestamps, sync, summaries, and tags remain extension points.

### Verified on 2026-09-15

Debug builds for arm64-v8a and x86_64, JVM tests, and lint completed successfully. The connected instrumentation suite passed on an A015 running Android 16, including actual Tiny-model transcription of the JFK sample. Other devices, languages, very long recordings, and all supported container formats still need broader device testing.

## Incremental output and resume

`whisper_full` invokes `new_segment_callback` as finalized segments become available. JNI forwards them immediately to the service, which commits the segment and `checkpointMs` together in one SQLite row write. The repository StateFlow updates the detail screen without navigation. Output arrives in segments (after Whisper has decoded a window), not token by token.

A fresh app process loads the database and marks unfinished jobs Interrupted before permitting new imports or processing. Retry uses the persisted checkpoint to slice the prepared PCM at that exact sample position, preserves the detected language, and adds the offset back to native timestamps. Saved segments are append-only during inference, so edits to the existing text survive resume. A changed source duration is rejected rather than applying a checkpoint to a different-length recording.

Leaving the screen normally does not interrupt the foreground service. Force-stop or process death drops only work not yet committed; the next launch offers manual resume. Model RAM state is not persisted, and temporary PCM is regenerated. Long silence since the last emitted segment may be processed again. Recognizer timestamps are estimates: restarting at a segment boundary can change recognition of subsequent words compared with uninterrupted inference. This is not a bit-for-bit snapshot of decoder state, and it does not fix Whisper hallucinations or speed problems.

Existing saved notes remain compatible; older records without a checkpoint default to zero. Work performed by older versions without streaming persistence cannot be recovered retroactively.

`CheckpointTest` covers checkpoint invariants and edit preservation. `ResumeTranscriptionTest` interrupts real native inference after a saved segment, reopens the SQLite database, checks interrupted recovery, and resumes the remaining audio. `TranscriptUiTest` checks the incomplete banner/resume timestamp and live detail updates. Run device tests on a test device; Gradle's connected-test task may uninstall test applications. To preserve an installed development app's data, install both APKs with `adb install -r` and invoke the instrumentation runner directly.

### Checkpoint verification on 2026-09-16

The native streaming/interruption/resume regression passed on a Pixel 6a. The test cancels from the first finalized-segment callback, reopens the database, checks Interrupted recovery, edits saved text, and resumes the remaining PCM with absolute timestamps. This exercises real inference and persistence but is not a benchmark or exhaustive long-recording accuracy test. At this checkpoint-validation stage the native optimization issue was still outstanding; the performance update below resolves it.

Final verification: 9 JVM tests passed; Android lint completed with no errors; debug APKs built for arm64-v8a and x86_64. Four device tests passed on the Pixel 6a: standard transcription, native streaming/interruption/resume, incomplete-note UI, and live-segment UI. The updated main APK was installed with replacement preserving existing app data. Native tests took approximately 370 seconds in this unoptimized debug build; this is functional verification, not a performance improvement claim.

## Native performance and Compact models (2026-09-16)

The native CMake project now applies `-O3` to both C and C++ compilation before adding whisper.cpp and GGML. This optimizes the actual inference kernels in Debug as well as Release builds, while retaining debugging symbols. The Android APK may still be labeled Debug; its inference engine is optimized. No `-ffast-math`, device-specific instruction-set requirement, thread-count change, or GPU backend was introduced.

After building, run `python3 scripts/check_native_optimization.py` to verify the generated compiler commands for Whisper/GGML and JNI. Tests cover catalog compatibility, variant selection, and rejecting truncated or corrupted model downloads without replacing existing files.

| Multilingual model | Standard download | Compact Q5_1 download |
| --- | ---: | ---: |
| Tiny | 77.7 MB | 32.2 MB |
| Base | 148.0 MB | 59.7 MB |
| Small | 487.6 MB | 190.1 MB |

Compact is an optional lower-precision model. It reduces download/storage and weight memory; overall RAM use includes additional working buffers. Speed depends on the device and accuracy can differ. It is not a separate engine and does not fix hallucinations. Existing notes keep their recorded model ID for consistent resume; choosing a different model/format applies to new imports.

Downloads are staged separately from inference-ready `.bin` files. The UI shows Verifying after downloading; only files with the expected size and SHA-256 are renamed into place. An interrupted download is not shown as ready. Existing standard-model downloads are retained.

### Reproducible on-device benchmark

`PerformanceBenchmarkTest` is opt-in and uses the bundled 11-second JFK WAV, explicit English, four native threads, and fresh model contexts per run. Download time is excluded. It reports PCM decode time, context/model load time, inference time, total native-call time, first-segment time, and Android's thermal status. It does not write library notes. Install main/test APKs with `adb install -r` rather than uninstalling the user's app.

```
adb shell am instrument -w \
  -e class app.naz.whispernote.PerformanceBenchmarkTest \
  -e models tiny,tiny-q5_1,base-q5_1,small-q5_1 \
  -e repetitions 2 -e label optimized \
  app.naz.whispernote.test/androidx.test.runner.AndroidJUnitRunner
```

Raw results are in `verification/benchmark-before.txt` and `verification/benchmark-after.txt`. The baseline main APK was preserved outside the repository before changing the native build. Short-clip results should not be extrapolated to noisy, multilingual, or sustained long recordings without further measurement.

### Measured results and final verification

On the connected Pixel 6a, two runs of the same 11-second clip averaged 71.472 seconds with the prior unoptimized Tiny engine and 5.074 seconds after `-O3` (14.1× faster in this test). Compact Tiny averaged 4.253 seconds, Compact Base 9.396 seconds, and Compact Small 33.076 seconds. These totals include model loading and inference but exclude decoding and download time. See [the performance report](verification/PERFORMANCE.md) for methodology and raw results.

All three Compact variants downloaded with verified hashes and ran real inference. Quantized checkpoint/resume, model-picker UI, and both transcript UI tests passed. Fourteen JVM tests and Android lint passed; generated native commands were checked for optimization. The initial UI test run was blocked by the device keyguard; the test-only window setup was corrected and the UI checks passed on rerun. The production app's lock-screen behavior was not changed.

### Pinned playback and transcript controls

The detail screen keeps playback controls above the transcript viewport. Scrolling collapses the title and animates the player into compact controls; returning to the top restores the expanded layout. Follow playback starts from the transcript heading and moves to the compact player's location icon while enabled. Active segments are centered using measured row and viewport sizes, including the first and last segments. Touching/browsing the transcript, editing, deleting, or scrubbing disables following; the player icon can re-enable it. Play/pause and skip buttons preserve following.

Each segment has an autosaved edit action and a confirmed delete action. Segment IDs are persisted, with backward-compatible IDs for existing notes, so queued edits cannot target a different segment after deletion. Deleting text preserves audio, timestamps of remaining segments, and the processing checkpoint.

Verification: JVM tests and debug lint/build passed. Five detail UI tests passed on Pixel 6a, covering centering, manual interruption, pinned compact playback, confirmed deletion, incomplete transcript state, and live segment updates.

### Labels and portable complete notes

Open the navigation drawer for **All notes**, **Unlabelled**, and saved labels with note counts. A note can belong to one label, like a folder. Create labels in the drawer or label picker; use the label action on a note to move it or remove its label. Label menus support rename and confirmed deletion. Deleting a label leaves its notes and audio intact. Search applies within the selected view. Audio imported while viewing a label inherits that label.

In a note's export dialog, **Save complete note** or **Share** packages the original audio and transcript as a `.whispernote` file. The recipient uses **Import complete note** in the drawer or new-note sheet. Apps preserving the `application/vnd.whispernote` MIME type can also open/share directly into WhisperNote with an import confirmation. If a messenger changes the file's MIME type, save the attachment and use the in-app picker.

The version-1 format is ZIP with exactly `note.json` and `audio`. Metadata preserves title, creation/modification dates, duration, language, model identifier, label, completion/checkpoint state, and edited segments with millisecond timestamps. Device-local file paths and Whisper model binaries are excluded. Imports receive fresh note/segment IDs and an owned audio copy; existing notes are never overwritten. Matching label names are reused case-insensitively. Reading and playback work offline without a model; continuing an imported partial transcript requires its model.

Transfers run off the UI thread with a busy indicator. Archives are limited to 16 MiB of metadata and 4 GiB of audio; unknown entries, unsupported versions and invalid timestamps are rejected. Entry paths are never used for extraction, and failed imports remove their copied audio. Shared files are kept in app cache so receiving applications can finish reading them.

Validation: JVM tests, Android lint (no errors), and APK builds passed. Device coverage includes label persistence/rename/deletion, backwards-compatible unlabelled notes, complete-note audio/text round trips, malformed archive cleanup, label picker and drawer filtering, and the existing detail playback/transcript checks.

### Remember listening position

Each note remembers its last audio position on this device. Reopening seeks to that position after the audio prepares and stays paused. Positions are saved on pause, seek and player disposal, plus every five seconds while listening. Normal navigation saves the latest position; an abrupt process termination can lose the last few seconds. Missing or unprepared audio does not overwrite an existing position. Listening progress is stored separately from transcription checkpoints and note modification dates, is not included in shared archives, and is removed when its note is deleted.

### Playback speed

Tap the speed value in the expanded or compact player to choose 0.5×, 0.75×, 1×, 1.25×, 1.5×, or 2×. Playback uses Android MediaPlayer parameters with pitch fixed at 1.0. Changing speed while paused keeps playback paused. Transcript highlighting and following continue to use the audio's media timestamp. Speed defaults to 1× when opening a note.
