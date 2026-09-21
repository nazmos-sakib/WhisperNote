---
layout: default
title: Privacy policy
permalink: /privacy/
description: How WhisperNote handles audio, transcripts, translation, downloads and sharing.
---
# Privacy policy

**Last updated: 21 September 2026**

This policy describes WhisperNote, the Android app maintained through the [nazmos-sakib/WhisperNote project](https://github.com/nazmos-sakib/WhisperNote), and this project website. For privacy questions or requests, contact the maintainer at [s.nazmos.sakib@gmail.com](mailto:s.nazmos.sakib@gmail.com).

## Audio and text processing

WhisperNote transcribes audio on your device using whisper.cpp. Built-in translation processes selected transcript text on your device using Google ML Kit. The app does not upload your audio or transcripts to a developer-operated transcription server. According to Google, ML Kit does not send its input text or translated output to Google servers.

Once the required models are downloaded, these processing features work offline. This does not mean that every app feature or third-party SDK is free of network activity; downloads, diagnostics and optional sharing are described below.

## Information stored on your device

WhisperNote stores note titles, transcripts, timestamps, labels, processing status and preferences in private app storage. It also stores listening positions, downloaded model files, and temporary processing files. Audio may be accessed through the file provider you choose or copied into the app’s private storage. Importing a complete note creates a local audio copy. A selected file provider may itself use cloud storage.

Built-in translation results are temporary. They are not saved into notes, text exports or complete-note archives. Leaving the note or losing the app process clears them; dismissing a word lookup discards that lookup. Background transcription and audio playback can continue while you use other apps. Translation clients are deactivated after five continuous minutes in the background, subject to Android scheduling and process lifetime.

## Downloads and network services

Whisper models are downloaded from Hugging Face and its delivery infrastructure. Translation packs are downloaded through Google ML Kit. If you import a direct HTTPS audio URL, the app contacts the host specified by that URL. Those providers receive ordinary connection information, such as your IP address and the requested resource, and handle requests under their own policies.

Relevant policies: [Hugging Face privacy policy](https://huggingface.co/privacy) and [Google Privacy Policy](https://policies.google.com/privacy).

## Google ML Kit diagnostics

Google ML Kit sends diagnostic and usage metrics to Google. These can include device and app information, SDK identifiers, performance measurements, API configuration, input/output sizes, configured source and destination languages, model events and error codes. Google uses these metrics to operate, maintain and improve its APIs and detect misuse. ML Kit may also contact Google for model updates, fixes and compatibility information. This diagnostic information is distinct from the actual text and translation output, which are processed on-device.

See [ML Kit terms and privacy](https://developers.google.com/ml-kit/terms) and [ML Kit data disclosures](https://developers.google.com/ml-kit/android-data-disclosure). Google describes transmission of the listed metrics as encrypted using HTTPS.

## Sharing, exports and the clipboard

Sharing is initiated by you. **Share** and **Translate with another app** pass the selected text to the destination you choose. Exported transcripts contain note text; complete-note archives contain audio and transcript metadata. Copies saved to another provider or received by another app are subject to that service’s policies. Copy actions place text on Android’s clipboard.

WhisperNote cannot delete copies already exported, shared or copied into another app. Notification and lock-screen playback controls may display the playing note’s title, depending on your device’s notification settings.

## Retention, deletion and backup

Saved notes remain in private app storage until you delete them or clear that storage. Deleting a note removes its saved transcript and allows you to delete audio owned by the app. External source files are not deleted. Downloaded packs can be removed from the model screens. Temporary files may remain in app cache until cleaned up by the app or Android.

Automatic backup and device migration of private app data are disabled. Uninstalling WhisperNote or clearing its storage removes its private notes and files. Export complete notes to a separate location if you want a backup. Files exported elsewhere remain there until you delete them separately.

The maintainer does not hold a server-side copy of your local note library and cannot retrieve it for you. Privacy requests concerning information you send directly to the maintainer can be made using the contact email above. Please do not send sensitive recordings unless necessary for your request.

## This website

This website is hosted on GitHub Pages. It does not add analytics scripts, advertising trackers, embedded videos or a contact form. Fonts, styling and screenshots are served with the site. GitHub may process visitors’ IP addresses and other technical information to provide and secure its hosting service, as described in the [GitHub Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).

Following an external link takes you to a service with its own privacy practices. If you email the maintainer, your email address and the information you include are used to handle your request and may be retained for necessary follow-up. Avoid posting private information in public GitHub issues.

## Changes and questions

This policy will be updated when the app’s data practices change. The date above identifies the latest revision. This page and the in-app Privacy screen should be read together; contact [s.nazmos.sakib@gmail.com](mailto:s.nazmos.sakib@gmail.com) if you notice a discrepancy or have a privacy question.
