package com.example.speechbridge.engine

/**
 * Plug-in point #2: the LLM that interprets each utterance in context, so it can decide whether
 * the user is dictating new text or issuing an edit ("no, I said A L A N A").
 */
interface TransformEngine {
    suspend fun apply(request: TransformRequest): TransformResult
}

/**
 * @param committedBefore text already in the field before this session started (may be empty).
 * @param sessionText text produced so far in this session (not yet committed by the keyboard).
 * @param utterance the new transcribed utterance.
 */
class TransformRequest(
    val committedBefore: String,
    val sessionText: String,
    val utterance: String,
)

/**
 * @param sessionText the full replacement text for this session (what the keyboard should show).
 * @param committedEdit optional fix to text that was committed in an earlier session. It can only
 *   be applied when the accessibility field bridge is enabled; otherwise it is ignored.
 */
class TransformResult(
    val sessionText: String,
    val committedEdit: CommittedEdit? = null,
)

class CommittedEdit(val find: String, val replace: String)
