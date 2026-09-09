# The goal surface: what the chat shows while a goal or research runs

*2026-09-07. Why the goal, plan and question cards left the bottom of the chat screen, what
replaced them, and the rule that decides where something about a running goal may appear.*

## What was wrong

The chat screen is one column: a pinned top bar, the transcript with the only flexible
height, then a stack of blocks each sized to its content: the status strip, the goal card, the
plan card, the question card, the tool approval, the composer. Those blocks are measured
first and the transcript gets what is left.

During `/deep-research` on a 6.7 inch phone the goal card carried the state, the task, the
current step, a note, a three-line text field with a label and a supporting line, and two
buttons; the plan card carried a title and five checkbox rows; and the composer sat under
both, disabled. Together they took the whole screen. The transcript, and with it the reply
being generated for the current step, had no height at all. The "jump to latest" pill needed
700 px of hidden tail to appear, which a viewport of zero pixels never has. Screenshots from
the Poco X8 Pro on 2026-09-07 show exactly this, and a halted run kept the same three blocks
on screen afterwards.

`/goal` behaves the same. `/plan` never shows the goal card and keeps the composer enabled,
so it had one text field, but it pinned the same plan card.

## The rule

**The transcript is the screen.** Anything about a running goal appears in one of three
places, and only these:

1. **One pinned strip** above the composer, 48 dp, never taller: the state ("Working on
   goal", "Goal needs attention"), one line with the step ("Step 3 of 12: who created the
   family", ellipsised, or the note when the goal stopped), a Stop button while it runs or a
   Dismiss button when it has finished, and a chevron. `GoalStrip.kt`.
2. **The transcript**, as the last items of the list: the plan with its checkboxes, and the
   model's question with its option chips. They scroll with the messages and, because the
   list follows its tail while a goal runs, they stay in view without pinning. `PlanCard.kt`,
   `QuestionCard.kt`, rendered from `Transcript` in `ChatScreen.kt`.
3. **A sheet** opened from the strip, for everything that does not fit a line: the task in
   full, the plan, the note, and what typing does while the goal runs. `GoalSheet` in
   `GoalStrip.kt`.

Nothing else about a goal is pinned. The status strip keeps its one fixed slot, and the tool
approval keeps its place, both as before.

## One text field

The composer is the only text field on the screen, in every state. While a goal runs it
stays enabled even though a step is generating, its hint reads "Adjust the next step", and
what is sent goes to the goal's steering queue, applied at the next step boundary as before.
While the model has asked a question, the hint reads "Type your answer" and what is sent
answers it; the question card's own text field is gone and its chips remain. Outside a goal
the composer behaves exactly as it did.

While steering or answering, the send button is a send button; stopping the goal is the
strip's job. Outside a goal the button is Stop during a reply, as before.

## Seeing the reply

Two changes keep the live reply on screen. At every step boundary (a change in the goal's
step count or in the transcript's length while the goal runs) the transcript jumps to its
tail, so a reader who scrolled up to check a previous step is brought back for the next. And
the "jump to latest" threshold is a fifth of the viewport rather than a fixed 700 px, so the
way back is offered in a short list too.

## Only on its own conversation

The board is one object for the app and is restored across a process death, so an
interrupted goal comes back halted for a person to look at. Restored onto the wrong screen
it was noise: an app swiped away or a phone restarted loses the saved-state handle that
reopens the last chat, and the strip then sat on an empty new chat describing a task that
chat never asked for (Poco X8 Pro, 2026-09-08). The view model now shows a goal only when
its conversation is the one on screen, or when it has no conversation yet because it
started on an empty chat. Reopening the goal's own conversation still shows it.

## What the plan says

Steps are read out of the model's list with markdown emphasis removed and, when they run
past sixty characters, cut at the last word rather than mid-word: `**What is the LFM2
architecture?** - To understand the techn` is now `What is the LFM2 architecture? - To
understand the`. The same text goes back to the model in the status block.

A goal or research started on an empty chat titles the conversation with the task. It used
to be titled with the first turn the loop sent, which is the planning prompt, so the drawer
read "Break this into a short numbered list of…" for every one of them.

## Getting into plan mode

`/plan` takes no message, by design and by test: "/plan the trip" is a sentence. But the
notice for it read "/plan is not a recognised command. Did you mean /plan?", which
contradicts itself. It now says the command switches the mode and takes no message, the
button reads "Use /plan", and accepting it switches the mode and leaves the rest of the
text in the composer to send.

## Why a research step searched and never fetched

Seen while verifying on the phone, and not part of this change: after a search, a fetch of
an address the model chose asks for approval even in Auto mode. That is the egress rule in
`AgentRunner` (untrusted text has been read, and the tool sends where the model says), and
it holds during a goal, so a research step pauses on "Run fetch_url?" until it is tapped.
The card sits in its usual pinned slot above the composer.

Since 2026-09-09 there is a plainer reason a fresh install's research never fetches:
`fetch_url` starts switched off, like every tool but `web_search` (see `Tool.defaultsOn`).
A research step then works from search results alone until the person turns page fetching
on in the Tools tab. The planner is told which tools the execution turn will have, so its
steps are written for what is on.

The halts that started this work had a different cause, in the engine: every step's prompt
with the tool prefix was refused by the ExecuTorch runtime's prefill bound and retried
without tools, so the model could not search at all. See
[executorch.md](../research/executorch.md), "Three things only the device said".

## What did not change

- Steering semantics: bounded to 16 messages of 500 characters, drained at step boundaries,
  persisted with the goal (`GoalBoard`).
- The plan itself: five steps at most, ticked by the model through `advance` or by the reader
  by tapping a row, one way only.
- A long reply written just before a goal halted still collapses to a short preview with
  "Show the rest".
- `/plan` mode: the plan is now in the transcript instead of pinned; everything else is as it
  was.

## Tests

`ChatScreenTest`: a question is answered through the composer; a question during a goal
routes the composer to the answer and not to steering; a running goal with a five-step plan
and a generating step keeps the reply on screen, shows the plan, shows the strip, and steers
from the composer. `CompactLandscapeChatScreenTest` still holds. `ComposerTest`: a mode
command with a message after it switches the mode and keeps the message. `TaskPlanTest`:
emphasis comes off and long steps cut at a word. `ExecuTorchEngineTest`: a long prompt is
fed ahead in pieces and generate gets only the tail; the piece size follows the export's
prefill bound.

Verified on the Poco X8 Pro with LFM2.5-2.6B at 32k on 2026-09-08: `/deep-research`
plans, searches, fetches and reports with the reply, the plan and the strip all on screen;
steering from the composer, the sheet, Stop and Dismiss all work; a fresh launch no longer
shows another chat's goal.
