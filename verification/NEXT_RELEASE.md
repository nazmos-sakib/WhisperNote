# Version 1.1 verification

Implementation date: 2026-10-12.

The working tree already contained local playback, labels, model lifecycle and native-engine changes. Those were retained. The new release adds recording, bookmarks, library archives, incoming media sharing, saved translations, transcript find navigation and cooperative long-audio scheduling.

Local verification:

```
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --offline
```

New JVM coverage: paused states, translation invalidation on source edits, exclusion of stale translations from exports, and bookmark preservation when transcription resumes. New device-test coverage (compiled, not executed): complete-library round trip with fresh segment IDs and retained bookmarks/translations/empty labels; cleanup of staged audio when a later archive entry is invalid.

Device tests were not run: only a physical A015 was connected and no AVD was available. No APK was installed, and no personal app data was changed. Existing UI tests compile but have not been rerun against the new UI.

Release gates still requiring a disposable emulator or dedicated test device:

- Record with microphone allowed/denied, lock the screen, pause/resume, stop, and verify usable audio plus queued transcription. Check interruptions, low storage and app process termination.
- Run a representative 60–90 minute recording on Tiny/Base/Small presets. Measure decode time, inference time, first segment, total duration, heat and battery. Compare optional VAD on/off with quiet speech and long silences.
- During transcription, enqueue a short note, prioritize it, pause/resume the long note, and verify timestamps, no duplicate text, retained edits and other queued jobs. Repeat during audio preparation and after process restart.
- Verify downloads run while transcription is busy and that notifications remain usable.
- Run LibraryBackupTest and NoteOrganizationTest, plus existing playback, translation, checkpoint/resume and detail UI suites.
- Inspect Find controls, bookmark dialog, saved translations and exports on small and large screens.

No measured transcription speedup or release readiness is asserted by a successful build. The optional speech detector defaults off and uses a pinned, SHA-256-verified upstream model. VAD may omit quiet speech and increases memory use. Recording uses AAC/M4A; abrupt process death can leave an unfinished recording file.
