# Third-party notices

This project is released under **The Unlicense** (see `LICENSE`), which puts our
own work in the public domain. It is built on the work below. Those parts stay
under their own licenses — MIT in every case here, which is compatible with a
public-domain release — and their notices are reproduced because MIT requires
that the copyright and permission notice travel with the software.

---

## Lipwise — MIT

Portions of this app are derived from **Lipwise**, a desktop dictation app, and
some of its files are used verbatim:

- `app/src/main/assets/catalog.json` is a **byte-identical copy** of Lipwise's
  `src-tauri/src/catalog.json` (md5 `1346fe0f267d65934d3ce98833c84b4c`).
- The speech contract (16 kHz mono, silence threshold, minimum clip length,
  vocabulary handling), the model catalog and download verification, the AI
  provider abstraction and the prompt personalisation are ports of Lipwise's
  behaviour into Kotlin.

```
MIT License

Copyright (c) 2026 Nenad Lukic

https://github.com/lnenad/lipwise

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## llama.cpp — MIT

Compiled by `scripts/build-native.sh` into `libllamaserver.so` and `libproc_jni.so`.
Not vendored in this repository.

```
MIT License

Copyright (c) 2023-2026 The ggml authors

https://github.com/ggml-org/llama.cpp

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

llama.cpp in turn bundles its own dependencies, under permissive terms:

| Component | License | Used by |
| --- | --- | --- |
| [yhirose/cpp-httplib](https://github.com/yhirose/cpp-httplib) | MIT | `llama-server`'s HTTP server |
| [nlohmann/json](https://github.com/nlohmann/json) | MIT | tools and examples |
| [nothings/stb](https://github.com/nothings/stb) | Public domain | image decoding |
| [mackron/miniaudio](https://github.com/mackron/miniaudio) | Public domain | audio decoding |
| [sheredom/subprocess.h](https://github.com/sheredom/subprocess.h) | Public domain | process launching |

---

## transcribe.cpp — MIT

Compiled by `scripts/build-native.sh` into `libtranscribe_jni.so`. Not vendored
in this repository.

```
MIT License

Copyright (c) 2026 The transcribe.cpp authors

https://github.com/handy-computer/transcribe.cpp

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## ggml — MIT

The tensor library underneath both of the above. Each project vendors its own
copy, so two independent copies are compiled here (which is why transcribe.cpp
must be built as its own CMake project — their target names collide).

```
MIT License

Copyright (c) 2023-2026 The ggml authors

https://github.com/ggml-org/ggml

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## Models — not ours, not bundled, and not covered by The Unlicense

No model weights are redistributed here. The app downloads GGUF files from
Hugging Face at runtime and each one is governed by its own license. The
catalogs record what Hugging Face publishes:

| Model | License |
| --- | --- |
| `handy-computer/parakeet-tdt-0.6b-v3-gguf` (default speech model) | CC-BY-4.0 |
| `handy-computer/parakeet-unified-en-0.6b-gguf`, `canary-180m-flash`, `canary-1b-v2`, `canary-qwen-2.5b` | CC-BY-4.0 |
| `handy-computer/cohere-transcribe-03-2026-gguf`, `whisper-medium-gguf`, `Qwen3-ASR-0.6B-gguf`, `Voxtral-Mini-4B-Realtime-2602-gguf` | Apache-2.0 |
| `handy-computer/gigaam-v3-*-gguf` | MIT |
| `handy-computer/canary-1b-gguf` | **CC-BY-NC-4.0 — non-commercial** |
| `handy-computer/nemotron-3.5-asr-streaming-0.6b-gguf`, `Fun-ASR-*-2512-gguf` | "other" — check the model card |
| `unsloth/gemma-4-E4B-it-qat-GGUF` | Apache-2.0 per its Hugging Face metadata, whose card also links Google's [Gemma 4 license](https://ai.google.dev/gemma/docs/gemma_4_license) |
| `LiquidAI/LFM2.5-2.6B-GGUF` | "other" — the [LFM Open License v1.0](https://huggingface.co/LiquidAI/LFM2.5-2.6B-GGUF/blob/main/LICENSE), a custom license with restrictions, not an OSI-approved one |

If you bundle weights into your own build, that model's license governs them and
The Unlicense does not apply to them. Read the model card before shipping,
particularly for `canary-1b` (non-commercial) and the LFM and Gemma terms.
