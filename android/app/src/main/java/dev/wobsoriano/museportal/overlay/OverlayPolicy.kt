package dev.wobsoriano.museportal.overlay

import dev.wobsoriano.museportal.ask.AskState
import dev.wobsoriano.museportal.ask.inFlight

/**
 * Whether the bar over other apps is wanted: while an ask is recorded, answered or spoken and our own
 * screen isn't in front to show it. Once the answer has been spoken it goes, after [LINGER_MS].
 *
 * Auto-hide: the bar is wanted exactly while the turn is busy — recording, waiting on the answer, or
 * speaking it. The moment the turn goes idle (answer settled and speech finished, and no follow-up is
 * being listened for) the bar stops being wanted and slides away after [LINGER_MS], so it never sits
 * over whatever the answer put on screen (a dashboard show card, say) waiting to be dismissed by hand.
 * A new turn makes it wanted again and cancels the slide-away.
 */
fun overlayWanted(ask: AskState, speaking: Boolean, inFront: Boolean): Boolean = !inFront && (ask.inFlight || speaking)

/**
 * How long the bar stays after it is no longer wanted, so the end of an answer or a failure can be
 * read before it slides away. ~4s: long enough to read a short confirmation, short enough that the
 * bar is gone before anyone goes looking for its ✕.
 */
const val LINGER_MS = 4_000L
