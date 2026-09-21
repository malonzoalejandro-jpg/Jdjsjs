# Jarvis — Voice Assistant (built via GitHub, installed on your Android phone)

**What it does:**
- Themed dark/cyan HUD-style app with a live activity feed showing what
  Jarvis hears and says
- Listens in the background for the word **"Jarvis"** (using Android's
  own built-in speech recognizer — no external account needed for this
  part), and understands the command even if said in the same breath
- Replies **"Yes, sir."** out loud (Fish Audio TTS), and talks back
  naturally to anything — never a flat "I didn't understand"
- Understands natural spoken commands via **Gemini** (Google's AI)
- Handles: alarms, timers, the time, playing a song on Spotify (opens
  search, one tap to play), opening apps, the flashlight, volume, web
  searches, Wi-Fi/Bluetooth settings shortcuts, calling/texting a contact,
  downloading a direct file link, and sharing your most recent photo/video
  to any app you pick
- Falls back to a simpler offline command parser when there's no internet
  (see "About offline mode" below)
- Original app icon included (no copied artwork)

**Three free accounts needed**: Gemini, Fish Audio, and (only if you want
it) nothing else — Spotify and GitHub need no paid account at all.

---

## How to build it (via GitHub — no Android Studio needed)

1. **Create a free GitHub account** at github.com if you don't have one.
2. **Create a new repository** — click the "+" in the top right → New
   repository. **Make it Private** (important, since it holds your real
   keys). Don't add a README/gitignore when prompted (this download
   already has one).
3. **Get a free Gemini API key**: go to https://aistudio.google.com/apikey,
   sign in with any Google account, click "Create API key." No payment, no
   company email, no premium tier needed — this is the easiest signup of
   everything we've used.
4. Open `local.properties` (in this download) and paste your Gemini key in
   place of `PASTE_YOUR_GEMINI_API_KEY_HERE`. Fish Audio is already filled in.
5. **Upload these files**: on your new repo's page, click
   "uploading an existing file," then drag the entire contents of this
   download (everything inside the folder) into the browser window,
   `local.properties` included. Commit the upload.
6. **Run the build**: go to the **Actions** tab in your repo → click
   "Build Jarvis APK" on the left → **Run workflow** button → Run workflow.
   Wait 2–5 minutes for it to finish (green checkmark).
7. **Download the app**: click the finished run → scroll to **Artifacts**
   → download `jarvis-debug-apk` (a zip containing `app-debug.apk`).
8. **Install it on your phone**: transfer the `.apk` to your phone (email
   it to yourself, or upload to Google Drive and download it there), tap
   it, and allow "install from this source" when prompted.

---

## About "it can do anything" and "fully offline AI"
Two honest limits worth knowing about, since they came up:

- **A true offline AI** (one that understands any phrasing without
  internet) means running a language model directly on your phone instead
  of calling Gemini over the internet. That's a real, much bigger project
  — it needs a multi-gigabyte model file bundled into the app and enough
  free RAM to run it, and quality is noticeably worse than a cloud model.
  What this build does instead: a offline fallback that handles the most
  common commands (alarms, flashlight, volume, opening apps, Wi-Fi/
  Bluetooth settings, downloads) using simple keyword matching — no
  internet needed for those, just less flexible about phrasing. Say the
  word if you want to explore the full local-AI route anyway.
- **"Anything on your phone"**: I keep adding real, concrete abilities
  (this round added downloading files and sharing your latest photo/video
  to any app), but I won't build things that bypass a platform's own rules
  — e.g. pulling videos out of YouTube/Instagram/TikTok isn't something
  those platforms allow third-party apps to do, so "download a video" here
  means a direct file link, and "upload" means opening the share sheet so
  you pick where it goes, not silently auto-posting to a specific app.

## About the logo
The icon is original vector art I designed for this project — a cyan
HUD-style ring with a stylized "J" — not a copy of the Iron Man movie
graphic sent earlier. It's already wired in as the adaptive icon.

## Notes on the pieces
- **Wake word**: Android's built-in speech recognizer, listening in a loop
  for the word "jarvis." Slightly more battery use than a dedicated
  wake-word engine, and a brief gap while it restarts between listens —
  the trade-off for needing zero extra account.
- **Fish Audio voice**: already set to the ID you gave me. If it ever goes
  silent, a toast will now show the actual error instead of failing quietly.
- **Spotify**: no signup needed — "play music" opens Spotify's search for
  the song directly (a tap away from playing). Make sure the Spotify app
  is installed on your phone.
- **Wi-Fi/Bluetooth**: Android blocks third-party apps from silently
  toggling these (a privacy rule since Android 10), so these commands open
  the settings screen instead — one tap away.
- **Staying alive in the background**: the app restarts itself if its task
  gets swiped away from Recents. Some phone brands (Xiaomi, Samsung,
  Huawei, OnePlus, Oppo, Vivo especially) still aggressively kill
  background apps beyond what code can prevent — if it keeps going quiet,
  check that battery use is set to "Unrestricted" for this app, and search
  "[your phone brand] auto-start" for the brand-specific switch.
- On first launch, grant the microphone, notification, call, text,
  contacts, and photo/video permissions, and allow the battery-optimization
  exemption.

## Notes & known rough edges
- None of this was compiled or run in the environment I built it in — I
  have no Android SDK or device here to test on. Paste me any build or
  runtime error and I'll fix it directly.
- Regenerate your Fish Audio key from its dashboard, since it was shared
  in this chat — doubly worth doing now that it's also sitting in your
  GitHub repo.

## Extending it
Add new actions in two places:
1. `GeminiInterpreter.kt` — add the new action shape to the system prompt.
2. `CommandProcessor.kt` — add a `when` branch in `dispatch()` to handle it
   (and, ideally, a matching offline fallback branch in `processLocally()`).

Each new phone capability needs its own Android permission in
`AndroidManifest.xml`.
