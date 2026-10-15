# Speech Bridge

An Android speech-recognition provider (`RecognitionService`) that keyboards can use through
`SpeechRecognizer`, plus a Quick Settings tile so it works with keyboards that cannot list it.

Audio is captured on device, transcribed on device, then passed through a local LLM that cleans up
what you actually meant — so "no, I said A L A N A" fixes the earlier text instead of being appended
to it. The result is inserted into the field you were already in, read back to you if you want, and
nothing leaves the phone.

**Status: working on a real phone.** Speech recognition, the LLM editor, field insertion, the tile,
model downloads and the config file are all end-to-end. See [Known limits](#known-limits) for what is
not finished — the largest being that most third-party keyboards cannot be made to list us.

**License: [The Unlicense](LICENSE) (public domain)** — with MIT-licensed components that keep their
own notices. Read [Licenses](#licenses) before you publish a build.

---

## What it does

```
keyboard or tile
   └─> BridgeRecognitionService / DictateActivity
         └─> DictationSession
               ├─ MicCapture        16 kHz PCM, pre-rolled so the first word is not clipped
               ├─ ClipSttEngine     energy VAD cuts utterances; ClipStt → Transcriber (JNI)
               │      └─> transcribe.cpp + a GGUF speech model        [stt/]
               ├─ LlmTransformEngine committed + session + utterance  [ai/]
               │      └─> llama-server child process, HTTP on 127.0.0.1
               │          (chat template from the GGUF; reasoning off; GBNF grammar)
               └─> result
                     ├─ accessibility bridge inserts it into the focused field
                     ├─ partial results so the keyboard can replace its composing text
                     └─ ReadBack speaks it, and the screen stays open so you can correct it
```

Every setting and **every word of the prompt** lives in
[`app/src/main/assets/speechbridge.config.json`](#setup-file), not in the code.

## Requirements

Install these before building. The NDK is the one that is *not* available from apt.

| Thing | Why | How |
| --- | --- | --- |
| **JDK 21** | Gradle runs on it. JDK 25 breaks AGP 8.7.3's lint (see [Building](#building)). | `sdk install java 21.0.12-tem` (SDKMAN), or `apt install openjdk-21-jdk` |
| **Android SDK** + platform-tools (`adb`) | to build and install | Android Studio, or `sdkmanager --install "platform-tools" "platforms;android-34" "build-tools;34.0.0"` |
| **NDK** | cross-compiles the native pieces | `sdkmanager --install "ndk-bundle"` — **not an apt package** |
| **cmake** + **ninja** | build the native pieces | `apt install cmake ninja-build` |
| **Gradle** (9.x) | or use a wrapper | `sdk install gradle` (SDKMAN) |
| **llama.cpp** | the local editor | `git clone https://github.com/ggml-org/llama.cpp ../llama.cpp` |
| **transcribe.cpp** | speech recognition | `git clone https://github.com/handy-computer/transcribe.cpp ../transcribe.cpp` |

Both checkouts are cloned *next to this repository* by default. Neither is vendored here; the build
script compiles them. Tested against llama.cpp `1b70350c9` and transcribe.cpp `f1dcd15`.

Note that transcribe.cpp is a **hard** requirement for a useful app: without it there is no
`libtranscribe_jni.so` and the only recogniser available is `FakeSttEngine`, which returns canned
text. `scripts/build-native.sh` will tell you if it is missing rather than failing cryptically.

## Build and install

```sh
# native pieces -> app/src/main/jniLibs/, then build, install and grant
scripts/build-native.sh --install
```

That is the whole thing. Other forms:

```sh
scripts/build-native.sh            # native pieces only
scripts/build-native.sh --no-stt   # skip speech recognition
scripts/install.sh                 # build + install an existing setup
SKIP_BUILD=1 scripts/install.sh    # install an APK you already built
```

`install.sh` runs Gradle, installs the APK, and then grants the two things Android will not let an
app grant itself — enabling the accessibility service, and `WRITE_SECURE_SETTINGS` so the app can
name itself the system recogniser. **It does this on every run on purpose**: reinstalling an APK
whose accessibility service changed silently clears the enabled-services setting, which looks
exactly like a bug in the app.

## Using it

1. **Grant the microphone** on the main screen.
2. **Speech models** → download one. The first becomes selected.
3. **Custom words** → add the names the recogniser gets wrong, and optionally a command word.
4. **Test dictation** to try the whole path before wiring it into anything.
5. Then, to use it from other apps, either:

   - **Quick Settings tile** (works everywhere) — pull down the shade, edit the tiles, add
     **Dictate**. Tapping it opens a listening screen, edits what you say, inserts it into the app
     you came from and closes. This is the keyboard-independent path.
   - **Make it the system recogniser** — the main screen's button does this now that the permission
     is granted. Only affects keyboards that use the *default* recogniser.

### The screens

| Screen | What it is for |
| --- | --- |
| Main | the order you actually need things in: microphone, models, custom words, test, editor, housekeeping |
| Speech models | download/select the recogniser; labels say **`(allows bias)`** where the model can be primed with your custom words |
| Local AI models | download/select the editor LLM |
| AI editor settings | provider, model, key, whether the editor is on |
| Custom words | a command word, and the list of words that get misheard |
| Free up space | what the models occupy, delete individual ones, remove orphaned folders and stale `.part` files |
| Dictate (tile) | listening screen: what you said, the edited version, "Keep talking" to continue, Close |

Downloads run in a foreground service with a progress notification you can cancel.

## The editor, corrections and the command word

Each turn sends the model three things: the text already committed in the field, the current session
text, and the new utterance. It replies with the whole updated session text, plus — only when you
corrected something from an earlier session — a find/replace pair:

```xml
<session>the whole session, rewritten</session>
<fix><find>Deep Seek</find><replace>DeepSeek</replace></fix>
```

A `<fix>` is applied only through the accessibility bridge, and only if its `<find>` text is really
present in the field.

**Command word** (in Custom words, empty by default). Set it — "Oops" is the intended shape — and an
utterance beginning with it is an edit instruction rather than new dictation: *"Oops, I said
DeepSeek."* With the field empty, corrections are inferred from what you said instead, which is how
"no, I said Alana" works. The word only changes the LLM's instructions; the recogniser hears it like
any other word and it is transcribed normally.

## Custom words, and which models can actually use them

Your custom words are sent to transcribe.cpp as vocabulary/phrase-boosting input. Whether that does
anything depends on the model's architecture — transcribe.cpp declares the capability per family, and
only these can be biased:

**whisper · funasr · granite · moss · qwen3**

The Speech models screen marks those `(allows bias)`, and the Custom words screen tells you whether
the model you have selected can use the words, or whether they will only reach the editor afterwards.
The families are listed in the config (`speech.bias_families`) rather than in the code, because that
list changes as transcribe.cpp grows. The native layer still runs its own capability check before
applying anything, so a wrong guess degrades to words reaching the editor only.

**The default model, `parakeet-tdt-0.6b-v3`, cannot be biased.** It is the default because it is fast
and accurate; if names matter more to you than latency, `whisper-medium` can be biased and is in the
catalog.

## Setup file

`app/src/main/assets/speechbridge.config.json` is copied into the app's data directory on first run
and read from there:

```
/data/user/0/com.example.speechbridge/files/speechbridge.config.json
```

The path is logged at startup under the `AppConfig` tag. Delete the file to get the bundled copy
back. Any missing key falls back to a built-in default, so a partial file works.

```jsonc
{
  "llm": {
    "default_model": "unsloth/gemma-4-E4B-it-qat-GGUF",
    "max_context_tokens": 4096,   // -c: context window; costs RAM and load time
    "threads": 4,                 // -t: generation threads, roughly your big cores
    "max_reply_tokens": 512,
    "temperature": 0.2,
    "reasoning": false,           // false adds --reasoning off --reasoning-budget 0
    "idle_stop_seconds": 600,     // free the model's RAM after this long unused
    "grammar": "root ::= ..."     // GBNF constraining the reply shape
  },
  "speech": {
    "default_model": "handy-computer/parakeet-tdt-0.6b-v3-gguf",
    "language": "en",             // not "auto": auto-detection misread English as Russian here
    "custom_words": ["Alana"],
    "bias_families": ["whisper", "funasr", "granite", "moss", "qwen3"],
    "silence_timeout_ms": 5000,           // ends the whole session
    "end_of_utterance_silence_ms": 1200,  // splits one utterance from the next
    "pre_roll_ms": 1500,                  // audio kept from before you were listened to
    "min_clip_ms": 300,
    "max_segment_ms": 28000,
    "speech_threshold_rms": 0.012,
    "silent_peak": 0.015,
    "no_speech_timeout_ms": 8000
  },
  "prompt": {
    "command_word": "Oops",
    "system": "You are the editing engine inside a voice keyboard. ...",
    "user": "<committed>\n{committed}\n</committed>\n\n<session>\n{session}\n</session>\n\n<utterance>\n{utterance}\n</utterance>",
    "command_word_note": "...\"{command_word}\"...",
    "vocabulary_note": "...",
    "custom_note": "The user's own preferences: {instructions}",
    "committed_max_chars": 1500
  }
}
```

`{committed}`, `{session}` and `{utterance}` are substituted per turn; `{command_word}` and
`{instructions}` come from settings. `scripts/install.sh` pushes this file on every run, so editing
it here is enough — but note that means edits made *on the device* get overwritten.

`reasoning: false` is the default because dictation cannot wait for a scratchpad: on the test model
it cut a reply from 138 tokens to 31, and generation from 11.2 s to 2.5 s.

## Building

Gradle 9.8.1, AGP 8.7.3, Kotlin 2.0.21, `compileOptions`/`kotlinOptions` both pinned to JVM 21.

```sh
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.12+1.1-tem gradle :app:build
```

Use **JDK 21**. `:app:build` runs AGP's lint, whose AOSP lint work action loads a JetBrains helper
(`com.intellij.util.lang.JavaVersion.parse`) that cannot parse JDK 25's version string `25.0.3`. That
is an AGP bug, not a project one. On JDK 25, `:app:assemble` and `:app:compileDebugSources` both
still succeed; only the lint tasks crash, so JDK 25 works if you skip them. Bytecode is JVM 21 either
way, so the APK is identical.

## Native pieces, and why each odd thing was necessary

| Piece | What it is | Why it is like that |
| --- | --- | --- |
| `libproc_jni.so` | fork/exec bridge (`app/src/main/cpp/proc_jni.cpp`) | The JVM cannot spawn a process. `PR_SET_PDEATHSIG` makes the server die with the app. |
| `libllamaserver.so` | llama.cpp's `llama-server`, aarch64 | **It is an executable shipped under a `lib*.so` name.** SELinux forbids executing a file the app wrote into its own data directory, but the extracted native library directory *is* executable, and only `lib*.so` entries get packaged by AGP. Nothing `dlopen`s it. |
| `libtranscribe_jni.so` | transcribe.cpp behind `stt/Transcriber.kt` | Built as its **own CMake project** (`app/src/main/cpp/stt/`) because transcribe.cpp vendors a second ggml whose target names collide with llama.cpp's, and it resolves headers from `${CMAKE_SOURCE_DIR}` so it must be the root project. |

Three build flags are load-bearing:

- `-DGGML_CPU_ARM_ARCH=armv8.6-a+dotprod+i8mm` — without it ggml falls back to scalar kernels for
  quantised matmul. On the test phone: **17 tok/s → 118 tok/s** prompt processing, and a 27 s turn
  became 5 s.
- `-Wl,-z,max-page-size=16384` — the device uses 16 KB pages.
- `-DGGML_OPENMP=OFF` — the NDK's `libomp.so` is not on the app's linker path, so llama-server has to
  be self-contained (libc/libm/libdl only).

`.so` files are loaded with `System.load("<nativeLibraryDir>/libX.so")` rather than
`loadLibrary("X")`: this JVM does not have the app's extracted library directory on its `dlopen`
search path, so the soname lookup fails even when the library is present.

## Known limits

- **Most keyboards cannot list us.** Samsung Keyboard's voice-input menu is curated to Samsung's own
  and Google's, and third-party recognisers cannot appear there even though the system resolves ours
  correctly (`cmd package query-services -a android.speech.RecognitionService` lists it). This is a
  vendor UI limitation, not a declaration problem. The Quick Settings tile is the workaround.
- Keyboards differ in whether they replace their composing text on each partial result. This is the
  biggest unknown.
- The energy VAD is basic. Swap in Silero/WebRTC VAD for noisy rooms.
- No live partials: latency per utterance is VAD end (1.2 s) + transcription + the LLM turn (~5 s).
- API keys sit in plain app-private SharedPreferences. Move them to Keystore-backed storage before
  shipping to anyone else.
- The assistant route (`BridgeVoiceInteractionService`) is a stub — long-press-home does nothing yet.
- GBNF grammar only works with a model that stops at the closing tag. It works with Gemma 4; LFM2.5
  aborts with `Unexpected empty grammar stack after accepting piece`.
- Field insertion needs the accessibility bridge. Without it the text only reaches the clipboard.
- Audio stays on device; if you configure a remote editor provider instead of the local one, each
  utterance and up to 1,500 characters of the field's existing text are sent to it.

## Licenses

**This repository is released under [The Unlicense](LICENSE)** — public domain, no conditions.

It is built on MIT-licensed work that keeps its own notice, so The Unlicense does not erase those
terms. In particular, **portions of this app are derived from [Lipwise](https://github.com/lnenad/lipwise)
(MIT, © 2026 Nenad Lukic)** and `app/src/main/assets/catalog.json` is a byte-identical copy of that
project's catalog. MIT is compatible with a public-domain release, but the copyright and permission
notices must travel with the software.

Full notices for every component — Lipwise, llama.cpp, transcribe.cpp, ggml and llama.cpp's own
bundled dependencies — are in **[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)**, along with the
model licenses.

Two things to keep in mind if you publish a build:

- The compiled `.so` files contain MIT-licensed code and are **not** committed here
  (`app/src/main/jniLibs/` is ignored). If you redistribute an APK, you are redistributing those
  binaries and must include their notices.
- **Model weights are not covered by The Unlicense.** They are downloaded at runtime and governed by
  their own licenses, which are listed in THIRD-PARTY-NOTICES.md. Some are non-commercial
  (`canary-1b` is CC-BY-NC-4.0) and the LFM and Gemma terms are custom licenses, not OSI ones.
