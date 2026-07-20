package cloud.wafflecommons.pixelbrainreader.data.ai

/**
 * Single source of truth for the language every on-device generation must reply in.
 *
 * The app is **French-first**: all Gemini Nano output (chat, briefing, oracle, folder
 * insight, journal assist, web-import summary…) must be in French, regardless of the
 * language of the user's notes or of the incoming question. Prompts are additionally
 * written in French prose — the surrounding language is the strongest steer for Nano —
 * but every generation prompt also carries one of these explicit directives so the
 * "answer in French" contract lives in exactly one greppable place.
 */
object AiLanguage {
    /** Short directive appended to a one-shot generation prompt. */
    const val DIRECTIVE = "Réponds toujours en français."

    /** Emphatic directive for personas / system prompts that must not drift language. */
    const val SYSTEM_DIRECTIVE =
        "Tu dois TOUJOURS répondre en français, quelle que soit la langue de la question ou des notes de référence."
}
