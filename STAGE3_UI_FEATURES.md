# Stage 3 — UI Features: Provider Availability, Language Sync, Spacebar Model Label

Date: 2026-09-24. All changes in this file are UI-level additions requested directly by the user.
They build on Stage 1 (`BASIC_VOICE_TYPING.md`), the composing-ownership bugfix
(`BUGFIX_COMPOSING_OWNERSHIP.md`), and Stage 2 (`STAGE2_REALTIME_PARITY.md`) — none of which are
modified here.

---

## 0. The grey-text question, answered (no code change needed)

The user asked whether option 2 ("Show the text while I speak") is now simply option 3
("Show the text only when I stop") **plus an on-screen view**. Answer: **yes, exactly.**

- Option 3 never creates a composing region; it commits the settled text at stop. It was never
  affected by the accumulation bug.
- Option 2 streams the same recognition through the field's **composing region** (the grey,
  temporary text). The composing-ownership fix (`BUGFIX_COMPOSING_OWNERSHIP.md`) is what made it
  usable: while a dictation session is writing, nothing else can finish or re-claim the region, so
  each partial replaces the previous one in place — the engine can keep revising earlier words as
  context arrives — and the whole region turns final in one frame at stop.
- So the two options now differ **only** in whether the in-progress text is visible. Internally
  both commit identically, and both benefit from the Stage-2 tail-only recovery.

What "grey" means, precisely:
1. It is Android's standard composing region — "this text is still being edited".
2. The writer may replace it wholesale on every update; earlier words can still change.
3. The commit points are exactly the region's release points (stop / cancel / final).
4. If the session dies unexpectedly, the region is simply finalized as-is — no text is lost.

---

## 1. Basic Voice Typing: icon + description (user request)

- `ProviderIcons.kt`: the `"basic"` branch now returns `Icons.Default.RecordVoiceOver` (a speaking
  mouth/voice icon) instead of falling through to the generic cloud. On-device stays the phone.
- `strings.xml` `dictate__basic_provider_summary` now reads:
  *"Phone's built-in voice recognition — instant, free, no key. Requires Google app"*

No behavior change anywhere else; the icon/description are the same values the row and dialog use.

---

## 2. Feature 1 — Dynamic Transcription Provider availability (user request)

**File: `DictateProvidersScreen.kt`.** One new helper, `isTranscriptionProviderAvailable(id,
accounts)`, is the single source of truth, and it reads the **same state the provider rows on the
screen already show** (no duplicated configuration logic, nothing hardcoded):

| Provider | Available when |
|---|---|
| Basic Voice Typing (`BASIC`) | always |
| On-device (offline) (`LOCAL`) | always — regardless of downloaded models, per request |
| Dictate Cloud (`CLOUD`) | wallet exists (`account.hasWallet`) |
| Custom endpoints | key set **or** base URL set (matches the row's "unconfigured" state) |
| Keyed presets (OpenAI, Groq, OpenRouter, Gemini, …) | `!requiresCredential \|\| hasKey` — i.e. "Key set" ⇒ selectable, "No key" ⇒ muted |

In the Transcription dialog:
- Unavailable providers stay **visible but muted** (`alpha 0.38`) and **non-selectable** — the
  RadioButton is disabled and the row ignores clicks. This also matches the existing behavior where
  an already-selected provider can never become un-selectable: if the current selection is itself
  unavailable (e.g. the key was just removed), its row still shows the selection, so OK simply
  re-affirms the state instead of silently switching providers.
- Ordering: `orderedEntries` sorts available-first (stable sort keeps registry order within each
  group). Add or remove a key and the dialog re-gates and re-orders automatically — it is all
  derived from the keyring, which is already observed state.
- The helper is used by the dialog only; the manage-list rows, the editor and the keyring are
  untouched.

---

## 3. Feature 2 — Auto Switch Dictate Language (user request)

**Pref** (`AppPrefs.kt`, dictate section): `autoSwitchLanguage` (`dictate__auto_switch_language`,
default **off**, persisted).

**Toggle UI** (`DictateLanguagesScreen.kt`): a Switch row directly below the "Active language"
item and above the language list — "Auto Switch Dictate Language" /
"Switch the Voice Typing language with the keyboard language automatically" (the user's wording,
kept). It only writes the pref; everything else on the screen is untouched.

**Sync** (`KeyboardManager.kt`): `syncDictationLanguageToKeyboard()` maps the keyboard's
`activeSubtype.primaryLocale` to a dictation language via the existing
`DictateLanguages.matchDevice(Locale)` (full BCP-47 first, then base language; never "detect") and
writes it to `activeInputLanguage`. It is called:
- on every `activeSubtypeFlow` change — the keyboard language change path, so the switch happens
  immediately, live, with no settings reopen; and
- once when the toggle itself turns on, so enabling it under a Hindi keyboard immediately points
  dictation at Hindi.

Guarantees:
- Toggle off ⇒ the function returns immediately; behavior is exactly as before.
- Manual selection keeps working: it always wins until the *next* keyboard-language change (same
  contract as Gboard's own per-language voice typing).
- No match (keyboard language without a dictation entry) ⇒ nothing is written; "Detect
  automatically" and the checkbox list are untouched.

---

## 4. Feature 3 — Spacebar label: Current transcription model (user request)

- `SpaceBarMode.kt`: new `TRANSCRIPTION_MODEL` value (existing three untouched).
- `EnumDisplayEntries.kt`: fourth radio entry "Current transcription model" — same
  Default/Cancel/OK dialog as the rest (`KeyboardScreen.kt` needed no change; it renders the enum
  entries generically).
- `TextKeyboardLayout.kt` space-key branch: when selected, the label is the **active transcription
  provider's display name** — `ProviderRegistry.byId(id)?.displayName` for built-ins ("Google
  Gemini", "Groq", "Basic Voice Typing", "On-device (offline)"), falling back to a custom
  endpoint's own `displayName`, then the raw id. Both `transcriptionProviderId` and
  `providerAccounts` are collected as Compose state inside the layout, so the label updates
  automatically the moment the model is changed — no restart, no reopen.

---

## Files touched

| File | Change |
|---|---|
| `app/.../app/settings/dictate/ProviderIcons.kt` | `"basic"` → `RecordVoiceOver` icon |
| `app/.../res/values/strings.xml` | basic summary + 3 new strings |
| `app/.../app/settings/dictate/DictateProvidersScreen.kt` | availability helper, dialog gating, ordering |
| `app/.../app/AppPrefs.kt` | `autoSwitchLanguage` pref |
| `app/.../app/settings/dictate/DictateLanguagesScreen.kt` | auto-switch toggle row |
| `app/.../ime/keyboard/KeyboardManager.kt` | language-sync calls + helper |
| `app/.../ime/keyboard/SpaceBarMode.kt` | `TRANSCRIPTION_MODEL` |
| `app/.../app/EnumDisplayEntries.kt` | 4th radio entry |
| `app/.../ime/text/keyboard/TextKeyboardLayout.kt` | spacebar label rendering |
