package com.example.speechbridge.engine

import com.example.speechbridge.joinText

/** Default transform: just appends each utterance. Swap for an LLM-backed implementation. */
class PassthroughTransformEngine : TransformEngine {
    override suspend fun apply(request: TransformRequest): TransformResult =
        TransformResult(joinText(request.sessionText, request.utterance))
}
