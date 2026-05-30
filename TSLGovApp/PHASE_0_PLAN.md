# Phase 0 — Quick Wins (Detailed Step-by-Step Plan)

**Goal:** Establish a clean baseline of which configured words actually translate today, with zero architectural changes. Estimated effort: **2–4 hours**.

**Out of scope for Phase 0:** template-averaging fix (Phase 1), per-word validator cleanup (Phase 2), new word recording (Phase 3), DTW (Phase 4).

---

## Pre-flight: understand what's actually happening

Before you touch anything, know these facts about the current code:

1. **Templates are cached.** `CameraActivity.loadSignLanguageTemplates()` at `CameraActivitiy.kt:115` calls `videoProcessor.loadTemplatesFromCache()` first. If a cache file exists (`cached_sign_templates.dat` in the app's `cacheDir`), the videos are NEVER re-processed. **You must clear app data after every code change that affects template loading**, otherwise your changes do nothing.

2. **The matcher uses hardcoded `R.raw.X` references in CameraActivity, NOT the `mainVideoFile`/`testVideoFiles` strings in `SignLanguageConfig`.** Those config fields are metadata only — they don't drive runtime behavior. So config "filename mismatches" are not functional bugs, only documentation drift.

3. **`HELP_SIGN_TEMPLATE` (SignLanguageConfig.kt:28) and `SICK_SIGN_TEMPLATE` (SignLanguageConfig.kt:56) are dead code.** Verified by grep — they have no callers. Safe to delete.

4. **The 5 confirmed-working words are all static, single-hand.** Motion-based and most two-hand words won't actually translate yet — this is a Phase 1 problem (template averaging), not Phase 0.

---

## Step 0.A — Baseline measurement (BEFORE any code change)

**Why first?** So you know which words work today and can detect regressions in later phases.

### A.1 — Build and install the app on a real device

```bash
cd /Users/kandasaikaew/courses/android/manee-2025-android-apps/TSLGovApp
./gradlew installDebug
```

### A.2 — Clear app data once (to force fresh template load from videos)

On the phone: **Settings → Apps → TSLGovApp → Storage → Clear cache + Clear data**

### A.3 — Open the app, go to camera mode, and perform each word ONCE

Try all 10 words in `SignLanguageConfig.ALL_WORDS`:

| # | Word | numHands | Notes |
|---|---|---|---|
| 1 | ปวดหัว | 1 | known working |
| 2 | ไม่สบาย | 1 | known working |
| 3 | แจ้งความ | 1 | known working |
| 4 | ห้องน้ำ | 1 | known working |
| 5 | เครื่องบิน | 1 | known working |
| 6 | ช่วย | 2 | motion (apart → together) |
| 7 | บัตรประชาชน | 2 | only 1 template loaded |
| 8 | เจ็บคอ | 2 | only 1 template loaded |
| 9 | หนังสือเดินทาง | 2 | only 1 template loaded |
| 10 | หาย | 2 | 3 templates loaded |

### A.4 — Capture logcat while testing

In a terminal:

```bash
adb logcat -s VideoProcessor SignLanguageAnalyzer CameraActivity > phase0_baseline.log
```

Sign each word 3 times. Note:
- Did the app translate it? (Yes / No / Wrong word)
- If wrong, which word did it translate to?
- For "No": what does logcat say — confidence too low? hand count wrong? validator rejected?

### A.5 — Record baseline results

Append to this file or create `PHASE_0_BASELINE.md` with a table:

```
| Word | Attempt 1 | Attempt 2 | Attempt 3 | Best confidence | Notes |
```

**Exit criterion for Step 0.A:** you have a table showing how many of the 10 words translate today. Likely outcome: 5–7 work consistently, ~3–5 do not.

---

## Step 0.B — Code changes (minimal, safe)

Three small, low-risk changes. All can be done in ~30 minutes.

### Change B.1 — Uncomment `help_main.mp4` template source

**File:** `app/src/main/java/th/ac/kkw/tslgovapp/CameraActivitiy.kt`
**Line:** 153

**Before:**
```kotlin
videoProcessor.createTemplateFromVideos("ช่วย", listOf(
  //  Uri.parse("android.resource://$packageName/${R.raw.help_main}"),
    Uri.parse("android.resource://$packageName/${R.raw.help_test1}"),
    Uri.parse("android.resource://$packageName/${R.raw.help_test2}"),
    Uri.parse("android.resource://$packageName/${R.raw.help_test3}"),
    Uri.parse("android.resource://$packageName/${R.raw.help_test4}")),2
)
```

**After:**
```kotlin
videoProcessor.createTemplateFromVideos("ช่วย", listOf(
    Uri.parse("android.resource://$packageName/${R.raw.help_main}"),
    Uri.parse("android.resource://$packageName/${R.raw.help_test1}"),
    Uri.parse("android.resource://$packageName/${R.raw.help_test2}"),
    Uri.parse("android.resource://$packageName/${R.raw.help_test3}"),
    Uri.parse("android.resource://$packageName/${R.raw.help_test4}")),2
)
```

**Why:** Adds the canonical `help_main.mp4` as a 5th template, giving the matcher one more reference. May or may not help (template is still an averaged blob — Phase 1 is the real fix).

---

### Change B.2 — Delete dead synthetic template constants

**File:** `app/src/main/java/th/ac/kkw/tslgovapp/SignLanguageConfig.kt`

**Delete lines 25–86** (both `HELP_SIGN_TEMPLATE` and `SICK_SIGN_TEMPLATE`).

The block to delete starts with the comment:
```kotlin
    // สร้างข้อมูลเทมเพลตสำหรับท่าทาง "ช่วย" (แทนที่วิดีโอ)
```
…and ends right before:
```kotlin
    // 🎯 รายการคำศัพท์ทั้งหมด พร้อมระบุประเภทมือ
    val ALL_WORDS = listOf(
```

**Verification (already done):** `grep -rn "HELP_SIGN_TEMPLATE\|SICK_SIGN_TEMPLATE" --include="*.kt"` returns only the definitions themselves — no callers.

**Why:** These were placeholders from before the real videos existed. Both videos now exist (`help_main.mp4`, `sick_main.mp4`). Deleting prevents future confusion.

---

### Change B.3 — Sync stale metadata comment in `SignLanguageConfig.kt:197`

**File:** `app/src/main/java/th/ac/kkw/tslgovapp/SignLanguageConfig.kt`
**Line:** 197

**Before:**
```kotlin
mainVideoFile = "sick_main.mp4",                           // ยังไม่มีไฟล์วิดีโอ — ใช้ SICK_SIGN_TEMPLATE แทน
testVideoFiles = listOf<String>("sick_test1.mp4", "sick_test2.mp4"),            // ยังไม่มีไฟล์วิดีโอทดสอบ
```

**After:**
```kotlin
mainVideoFile = "sick_main.mp4",
testVideoFiles = listOf("sick_test1.mp4", "sick_test2.mp4"),
```

**Why:** The comments now contradict reality (videos exist, SICK_SIGN_TEMPLATE will be deleted). Hygiene only.

---

## Step 0.C — Rebuild and clear cache

This is critical. Without it, the changes won't take effect.

### C.1 — Build

```bash
cd /Users/kandasaikaew/courses/android/manee-2025-android-apps/TSLGovApp
./gradlew installDebug
```

### C.2 — Clear app data (forces template re-extraction)

On the phone: **Settings → Apps → TSLGovApp → Storage → Clear cache + Clear data**

**Why both?** The template cache is in `cacheDir/cached_sign_templates.dat`. "Clear cache" alone should be enough but "Clear data" guarantees it.

### C.3 — Verify in logcat that templates re-loaded from videos (not from cache)

```bash
adb logcat -s VideoProcessor CameraActivity | grep -E "cache|template"
```

Expected output:
```
D/CameraActivity: No cache found, loading templates from videos...
D/VideoProcessor: Creating templates for 'เจ็บคอ' from 1 videos
...
D/VideoProcessor: Templates cached successfully (10 words)
```

If you see `Templates loaded from cache` instead, the cache wasn't cleared — repeat C.2.

---

## Step 0.D — Re-test all 10 words (post-change baseline)

Repeat Step 0.A.3 and 0.A.4. Same protocol, same word list, fresh log file:

```bash
adb logcat -s VideoProcessor SignLanguageAnalyzer CameraActivity > phase0_after.log
```

Add a second column to the results table:

```
| Word | Before (out of 3) | After (out of 3) | Notes |
```

---

## Step 0.E — Document results

Create `PHASE_0_RESULTS.md` with:
1. The before/after table
2. For each word that still fails: a one-line diagnosis from logcat (e.g., "ช่วย: validateHelpGesture rejects because hasVerticalSeparation=false")
3. Identified candidates for Phase 1 (motion-based, need multi-template fix)
4. Identified candidates for additional video recording (1-template two-hand words: บัตรประชาชน, เจ็บคอ, หนังสือเดินทาง)

---

## Phase 0 Exit Criteria

You can declare Phase 0 done when:
- [ ] Steps B.1, B.2, B.3 are committed (one commit each, with clear messages)
- [ ] `PHASE_0_RESULTS.md` exists with before/after numbers
- [ ] You know with logcat evidence WHY each non-working word fails (template match too low / validator rejection / no hand detected)
- [ ] The 5 known-working words still work (no regression from B.1–B.3)
- [ ] You have a clear, evidence-based list of which words to target in Phase 1

---

## What you should NOT do in Phase 0

- ❌ Do not modify `validateXxxGesture()` functions (that's Phase 2)
- ❌ Do not change `calculateAverageLandmarks()` or `createTemplateFromVideos()` (that's Phase 1)
- ❌ Do not add new words yet (that's Phase 3)
- ❌ Do not record new videos for existing words (defer until Phase 1 decision is made — multi-template fix may need different recordings)
- ❌ Do not delete the per-word validators yet, even if they look unused — verify against logcat first

---

## Expected outcome of Phase 0

Likely baseline you'll measure:

| Word | Likely status after Phase 0 |
|---|---|
| ปวดหัว, ไม่สบาย, แจ้งความ, ห้องน้ำ, เครื่องบิน | ✅ Still working |
| ช่วย | ⚠️ Maybe slightly better with help_main added, likely still fails (motion issue, needs Phase 1) |
| บัตรประชาชน, เจ็บคอ, หนังสือเดินทาง | ❌ Likely still fail (only 1 template each, motion issue) |
| หาย | ❌ Likely still fails (motion issue) |

**Net gain in Phase 0:** 0 to +1 new working word (ช่วย, if you're lucky). The real value is the **diagnostic baseline** that makes Phase 1 effective.

---

## Files touched in Phase 0

| File | Change | Lines |
|---|---|---|
| `app/src/main/java/th/ac/kkw/tslgovapp/CameraActivitiy.kt` | uncomment help_main | 153 |
| `app/src/main/java/th/ac/kkw/tslgovapp/SignLanguageConfig.kt` | delete dead templates | 25–86 |
| `app/src/main/java/th/ac/kkw/tslgovapp/SignLanguageConfig.kt` | clean stale comments | 197–198 |
| `PHASE_0_RESULTS.md` (new) | document baseline | — |

---

## Next: when you finish Phase 0

Bring `PHASE_0_RESULTS.md` to the conversation and we'll plan Phase 1 (multi-template refactor) using your actual measured starting point, not estimates.
