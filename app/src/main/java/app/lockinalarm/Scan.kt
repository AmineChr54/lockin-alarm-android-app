package app.lockinalarm

import android.app.Activity
import android.content.Context

/** A scanned tag, wherever it was scanned. */
fun Activity.scanned(uid: String): Step = Engine.dispatch(applicationContext, Event.Tag(uid))

/** What to tell the person, null if nothing. */
fun said(ctx: Context, st: Step): String? = when (st.said) {
    Said.NOTHING -> null
    Said.NOT_RINGING -> "No alarm ringing"
    Said.WRONG_TAG -> "Wrong tag. Find the right one!"
    Said.WRONG_ORDER -> "Not that one. Next: ${st.session.need.firstOrNull()?.let { Store.tagName(ctx, it) } ?: "another tag"}"
    Said.NEXT_TAG -> "Good. Next: ${st.session.need.firstOrNull()?.let { Store.tagName(ctx, it) } ?: "another tag"}"
    Said.STOPPED -> if (st.session.phase == Phase.AWAKE_WAIT) "Good morning! I'll check on you in ${(((st.session.until - System.currentTimeMillis()) + 59_000) / 60_000)} min" else "Good morning!"
    Said.SNOOZED -> "Snoozed"
    Said.SCAN_TO_SNOOZE -> "Scan a tag within 10 s to snooze"
    Said.AWAKE -> "Nice. Go get the day!"
    Said.OVER -> "Alarm ended"
}
