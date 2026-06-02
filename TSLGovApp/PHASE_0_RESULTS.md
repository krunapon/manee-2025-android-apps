# Phase 0 Results

## Summary

| Metric | Value |
|---|---|
| Words working before Phase 0 | 4 |
| Words working after Phase 0 | 6 |
| Net gain | +2 |

---

## Before / After

| Word | Before | After | Notes |
|---|---|---|---|
| ปวดหัว | ✅ | ✅ | No change |
| ไม่สบาย | ✅ | ✅ | No change |
| แจ้งความ | ✅ | ✅ | No change |
| เครื่องบิน | ✅ | ✅ | No change |
| ห้องน้ำ | ❌ | ✅ | Fixed wristY threshold (0.40) + inPosition adaptive threshold (0.92/0.90/0.88) |
| ช่วย | ❌ | ✅ | Fixed validateHelpGesture: relaxed handsHighEnough to 0.75, removed hasVerticalSeparation and isMoreVerticalThanHorizontal conditions |
| บัตรประชาชน | ❌ | ❌ | See diagnosis below |
| เจ็บคอ | ❌ | ❌ | See diagnosis below |
| หนังสือเดินทาง | ❌ | ❌ | See diagnosis below |
| หาย | ❌ | ❌ | See diagnosis below |

---

## Diagnosis of failing words

**บัตรประชาชน, เจ็บคอ, หนังสือเดินทาง**
Only 1 training video template each. Insufficient for reliable matching.
Target: Phase 3 — record additional training videos (3–5 per word, multiple signers).

**หาย**
Motion-based two-hand gesture (hands move apart). Static template matching cannot
capture the motion component. Template distance is high even when hand shape is close.
Target: Phase 4 — DTW for motion-based signs.

---

## Code changes made in Phase 0

| File | Change |
|---|---|
| `CameraActivitiy.kt` | Uncommented `help_main.mp4` as ช่วย template source (B.1) |
| `SignLanguageConfig.kt` | Deleted dead `HELP_SIGN_TEMPLATE` and `SICK_SIGN_TEMPLATE` constants (B.2) |
| `SignLanguageConfig.kt` | Removed stale comments from `sick_main` entry (B.3) |
| `VideoProcessor.kt` | Added `&& requiredHands >= 2` guard to two-hand block in `checkGestureCharacteristics` |
| `VideoProcessor.kt` | Toilet validator: set `wristY > 0.40f` lower bound |
| `SignLanguageAnalyzer.kt` | `validateHelpGesture`: relaxed `handsHighEnough` to 0.75f, removed `hasVerticalSeparation` and `isMoreVerticalThanHorizontal` |
| `SignLanguageAnalyzer.kt` | `isHandIntentionallyShown`: raised adaptive thresholds to 0.92/0.90/0.88 |

---

## Phase 1 targets

Based on Phase 0 evidence, Phase 1 should focus on:

1. **Template quality** — toilet templates show curled fingers (0/5 extended) but the
   gesture requires extended fingers. Template averaging is producing poor representatives.
   All 10 words need the multi-template fix before accuracy can improve.

2. **Motion-based signs** — ช่วย and หาย require capturing hand movement over time,
   not a single averaged frame. DTW (Phase 4) is the proper fix; Phase 1 multi-template
   may provide marginal improvement.

3. **Thin-template words** — บัตรประชาชน, เจ็บคอ, หนังสือเดินทาง need more training
   videos before Phase 1 changes will have any effect on them.
