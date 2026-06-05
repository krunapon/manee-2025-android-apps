# Two-Hand Recognition Fix Plan (FIX #1 & #2)

Diagnosed 2026-06-04 from full logcat evidence. The 5 failing two-hand words
(ช่วย, บัตรประชาชน, หนังสือเดินทาง, หาย, เจ็บคอ) fail because of three stacked
bugs. This doc covers FIX #1 and #2 — both required before any two-hand word can win.
(FIX #3 = two-hand template quality, handled separately later.)

---

## FIX #2 — Open the two-hand gate (do this first; simpler)

**File:** `app/src/main/java/th/ac/kkw/tslgovapp/VideoProcessor.kt`
**Function:** `checkGestureCharacteristics`
**Lines:** 663–785 (the entire two-hand `if` block)

### Current structure
```kotlin
if (actualHands >= 2 && landmarks.landmarks.size >= 42 && requiredHands >= 2) {
    val leftWristX = ...                 // wrist vars
    val horizontalDistance = ...
    val verticalDistance = ...
    when (word) {
        "หาย" -> { ...; return result }            // absolute-position checks
        "บัตรประชาชน" -> { ...; return isMoreHorizontal && handsAtWaist }
        "ช่วย" -> { ...; return handsHighEnough && handsAreStacked }
        "เจ็บคอ" -> { ...; return handsHighUp && handsSameLevel }
        "หนังสือเดินทาง" -> { ...; return thumbsWideApart && ... }
    }
}
```

### Change — replace the whole body with a single `return true`
```kotlin
if (actualHands >= 2 && landmarks.landmarks.size >= 42 && requiredHands >= 2) {
    // Hand count is sufficient. Let template matching + the analyzer's
    // validateXxxGesture() validators discriminate — not absolute screen position.
    return true
}
```

### Why it's correct and safe
- Reaching this block already guarantees `actualHands >= 2` and `requiredHands >= 2`,
  and line 616 (`if (actualHands < requiredHands) return false`) already rejected
  too-few-hands. Returning `true` only means "hand count is fine."
- The absolute Y/X thresholds being deleted are exactly what rejected
  เจ็บคอ/หนังสือเดินทาง/ช่วย (neck-level hands at Y≈0.5–0.66 vs hardcoded `Y<0.4`).
  They don't generalize across camera distance.
- `normalizeHandLandmarks` (called right after at ~1261/1267) removes absolute
  position, so template matching handles "where" correctly. The per-word
  `validateXxxGesture()` in `SignLanguageAnalyzer.kt` is a second discrimination layer.

### Caveat to verify after
Two-hand words now compete purely on template distance. Watch for them confusing
*each other* (id card vs passport vs neckache are all "two hands apart"). If that
happens it's bug #3 (template quality) — do NOT re-add absolute-position gating.

---

## FIX #1 — Stop single-hand words hijacking two-hand frames (the big one)

**File:** `app/src/main/java/th/ac/kkw/tslgovapp/VideoProcessor.kt`
**Function:** `recognizeSign`
**Lines:** 1199–1203

### Current code
```kotlin
val requiredHands = templates.firstOrNull()?.numHands ?: 1
if (requiredHands > numDetectedHands) {
    Log.d(TAG, "   ❌ '$label' mismatched num hands: needs $requiredHands hands, got $numDetectedHands")
    continue
}
```

### Change — require EXACT hand-count match (`>` → `!=`)
```kotlin
val requiredHands = templates.firstOrNull()?.numHands ?: 1
if (requiredHands != numDetectedHands) {
    Log.d(TAG, "   ❌ '$label' hand-count mismatch: needs $requiredHands, got $numDetectedHands")
    continue
}
```

### What this does
Today, when you show 2 hands (`numDetectedHands == 2`), single-hand words
(`requiredHands == 1`) pass the `>` filter and fall into the active-hand fallback
at lines 1208–1247, which grabs your most-active hand and matches it against
single-hand templates — scoring 0.25–0.75 and WINNING over the two-hand word
(หาย at 1.25). The `!=` filter excludes single-hand words when 2 hands are present,
so only two-hand words compete. (When you show 1 hand, two-hand words are excluded,
same as before.)

### Consequence
The `if (requiredHands < numDetectedHands)` branch at lines 1208–1247 becomes
unreachable dead code. Either leave it (harmless) or delete 1208–1247 and unwrap
the `else { ... }` at 1248 so the exact-count matching body runs directly.

### Tradeoff to know (don't skip)
The active-hand fallback handled a real case (comment at line 1209): a resting hand
that would otherwise false-match. With exact-match, a ONE-hand sign performed while a
second hand is accidentally in frame would be skipped (count=2, no two-hand word
intended → nothing matches). Low risk in normal use — when you show one hand,
`numDetectedHands` is 1 and the 5 working words still match. Only bites if a stray
second hand is counted as active.

### Supporting fix IF working words regress — `SignLanguageAnalyzer.kt:506`
```kotlin
if (activeHandsCount == 1 && handsToValidate.size == 2) {
    // use only the active (higher) hand
```
This branch is STRUCTURALLY DEAD: `activeHandsCount` is set to `handsToValidate.size`
at line 479, so the condition can never be true. Its intent was to drop a resting
hand to one active hand before counting — but it never fires, so a resting hand
inflates the count to 2. If exact-match causes single-hand regressions, fix this by
computing `activeHandsCount` as the number of genuinely-raised hands (per-hand version
of `areHandsIntentionallyShown`, or hands above an adaptive Y) instead of
`handsToValidate.size`. Only touch this if a single-hand word actually regresses —
don't fix speculatively.

---

## Order and test loop

1. Apply FIX #2 (gate → `return true`).
2. Apply FIX #1 (`>` → `!=`).
3. `./gradlew installDebug`
4. **Clear app data** on the device (templates cached in `cached_sign_templates.dat`;
   without this the changes do nothing).
5. Capture:
   ```bash
   adb logcat -c && adb logcat -s VideoProcessor SignLanguageAnalyzer CameraActivity > after_fix.log
   ```
6. Sign all 10 words, then:
   ```bash
   grep -E "🎯 Best:|best distance|hand-count mismatch|updateResult" after_fix.log
   ```

### What success looks like
During two-hand signs you should see `🎯 Best: เจ็บคอ`/`บัตรประชาชน`/etc. winning
instead of `เครื่องบิน`. If a two-hand word reaches matching but loses to ANOTHER
two-hand word, that's bug #3 (templates) — a later fix. If single-hand words still
work, FIX #1/#2 are done; leave `SignLanguageAnalyzer.kt:506` alone.

### Honest note
FIX #1 + #2 make two-hand words ABLE to win. Whether each clears its confidence
threshold (50% for two-hand) still depends on template quality (#3). หาย especially
(1 template, distance ~1.25) likely needs its template fix first. But id card,
passport, and neckache looked close enough in the data that these two fixes alone
may get them working.
```
