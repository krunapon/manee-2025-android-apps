# TSLGovApp — Change Guide (Backup → Current Version)

This document describes every change made between the backup version and the current version.
Students should apply these changes to their backup code to get the latest features and fixes.

---

## Overview of Changes

1. **New sign word added: "ไม่สบาย" (Sick/Not feeling well)** — A new single-hand gesture
2. **Bug fix: IndexOutOfBoundsException crash** — Fixed crash when 2 hands detected but only 1 hand's landmarks present
3. **Improved gesture recognition accuracy** — Better distinction between similar gestures (headache vs sick, toilet vs airplane)
4. **Video rotation handling** — Front camera videos now rotate correctly during template creation
5. **Code cleanup** — Removed unused imports and test code

---

## Files Changed

| # | File | Type of Change |
|---|------|---------------|
| 1 | `CameraActivitiy.kt` | New template loading + bug fix |
| 2 | `LearnActivity.kt` | Added new word to learn list |
| 3 | `MainActivity.kt` | Removed unused imports + test code |
| 4 | `SignLanguageAnalyzer.kt` | New validation + threshold adjustments |
| 5 | `SignLanguageConfig.kt` | New template data + word entry |
| 6 | `VideoProcessor.kt` | Major: new gesture checks + recognition rewrite + video rotation |

### New Resource Files

Copy these 3 video files into `app/src/main/res/raw/`:
- `sick_main.mp4`
- `sick_test1.mp4`
- `sick_test2.mp4`

---

## 1. CameraActivitiy.kt

### Change 1.1: Add import for RequiresApi annotation

**Method:** (top-level imports)
**After line 18** (`import android.widget.Toast`), **add:**

```kotlin
import androidx.annotation.RequiresApi
```

**Reason:** Required for the `@RequiresApi` annotation added in Change 1.2.

---

### Change 1.2: Add @RequiresApi annotation to onCreate

**Method:** `onCreate`
**Before line 89** (`override fun onCreate(savedInstanceState: Bundle)`), **add:**

```kotlin
    @RequiresApi(Build.VERSION_CODES.DONUT)
```

**Reason:** Explicitly declares the minimum API level requirement for the method, improving Android Lint compliance.

---

### Change 1.3: Add template creation for "ไม่สบาย"

**Method:** `loadAllTemplates`
**After line 156** (the closing `)` of the "ช่วย" `createTemplateFromVideos` call), **add:**

```kotlin
            videoProcessor.createTemplateFromVideos("ไม่สบาย",
                listOf(Uri.parse("android.resource://$packageName/${R.raw.sick_main}"),
                Uri.parse("android.resource://$packageName/${R.raw.sick_test1}"),
                Uri.parse("android.resource://$packageName/${R.raw.sick_test2}")), 1)
```

**Reason:** Creates recognition templates from the sick gesture training videos. The `1` at the end means this is a single-hand sign.

---

### Change 1.4: Fix Thai Locale for Text-to-Speech

**Method:** `onInit`
**At line 541**, **replace:**

```kotlin
            val result = textToSpeech?.setLanguage(Locale("th", "TH"))
```

**with:**

```kotlin
            val result = textToSpeech?.setLanguage(Locale.forLanguageTag("th-TH"))
```

**Reason:** `Locale.forLanguageTag("th-TH")` is the modern BCP 47 standard way to create locales. `Locale("th", "TH")` is deprecated and may not work correctly on some Android versions.

---

## 2. LearnActivity.kt

### Change 2.1: Add "ไม่สบาย" to the learn words list

**Method:** (top-level property `learnWords`)
**At line 89** (after the closing `)` of the last `SignWord(` entry for "ห้องน้ำ"), **replace:**

```kotlin
        )
    )
```

**with:**

```kotlin
        ),
        SignWord(
            word = "ไม่สบาย",
            meaning = "รู้สึกไม่สบาย/มีไข้",
            videoFileName = "sick_main.mp4",
            category = "โรงพยาบาล"
        )
    )
```

**Reason:** Adds the new "sick" sign word so it appears in the learn page with its video.

---

## 3. MainActivity.kt

### Change 3.1: Remove unused imports

**Method:** (top-level imports)
**At lines 11–16**, **remove these 6 lines:**

```kotlin
import th.ac.kkw.tslgovapp.CameraActivity
import th.ac.kkw.tslgovapp.LearnActivity
import th.ac.kkw.tslgovapp.model.Point3D
import th.ac.kkw.tslgovapp.model.HandLandmarkData
import th.ac.kkw.tslgovapp.model.SignWord
import th.ac.kkw.tslgovapp.model.RecognitionResult
```

**Reason:** These imports are unused in MainActivity. The Activity classes are referenced via Intent strings, and the model classes are only used in the deleted test function.

---

### Change 3.2: Remove testModelClasses() call from onCreate

**Method:** `onCreate`
**At lines 35**, **remove:**

```kotlin
        testModelClasses()
```

**Reason:** This was a development-time test function that is no longer needed.

---

### Change 3.3: Remove the entire testModelClasses() function

**Method:** (standalone private function at bottom of file)
**At lines 134–142**, **remove everything from `// ใน เพิ่ม test function` through the end of the file:**

```kotlin
// ใน เพิ่ม test function สำหรับ handlandmarkdata
private fun testModelClasses() {
    val testPoint = Point3D(0.5f, 0.5f, 0.0f)
    val testLandmarks = HandLandmarkData(
        landmarks = listOf(testPoint)
    )
    val testWord = SignWord("ช่วย", category = "โรงพยาบาล", templateLandmarks = testLandmarks)
    val testResult = RecognitionResult("ช่วย", 0.85f, 0.15f)

    Log.d("ModelTest", "✅ Models work: ${testWord.word}")
}
```

**Reason:** Cleanup — this test function was only used during initial development to verify model classes work.

---

## 4. SignLanguageAnalyzer.kt

### Change 4.1: Tighten wrist edge detection threshold

**Method:** `isValidHand`
**At line 348**, **replace:**

```kotlin
        if (wrist.x < 0.03f || wrist.x > 0.97f || wrist.y < 0.03f || wrist.y > 0.97f) {
```

**with:**

```kotlin
        if (wrist.x < 0.1f || wrist.x > 0.90f || wrist.y < 0.1f || wrist.y > 0.90f) {
```

**Reason:** The old threshold (0.03/0.97) was too loose — it allowed hands very close to the frame edges where hand landmark detection is unreliable. The new threshold (0.1/0.90) rejects hands that are too close to edges, reducing false detections from partially-visible hands.

---

### Change 4.2: Add "ไม่สบาย" case to validateWithAngleFeatures

**Method:** `validateWithAngleFeatures`
**At line 606** (after `"เจ็บคอ" -> validateSoreThroatGesture(landmarks, fingerStates)`), **replace:**

```kotlin


            // สถานีตำรวจ (Police) - 3 words
```

**with:**

```kotlin
            "ไม่สบาย" -> validateSickGesture(landmarks, fingerStates)


            // สถานีตำรวจ (Police)
```

**Reason:** Routes the "ไม่สบาย" sign through its dedicated validation function.

---

### Change 4.3: Add the validateSickGesture() method

**Method:** (new private method)
**After line 619** (the closing `}` of `validateWithAngleFeatures`), **before** the comment `// GESTURE-SPECIFIC VALIDATION FUNCTIONS`, **add:**

```kotlin
    /**
     * Validate "ไม่สบาย" (Sick) gesture
     * ลักษณะ: มือข้างเดียว ฝ่ามือเปิด แตะที่หน้าผาก (เช็คว่ามีไข้)
     *
     * Distinguishing factors:
     * - vs ปวดหัว: นิ้ว "กระจาย" ไม่กระจุก (ปวดหัวคือนิ้วทุกนิ้วรวมกันแตะหน้าผาก)
     * - vs แจ้งความ: ใช้นิ้วหลายนิ้ว ไม่ใช่ชี้นิ้วเดียว
     * - vs ห้องน้ำ: มืออยู่สูง (ใกล้หน้าผาก) ไม่ใช่อยู่ระดับอกหรือกลางลำตัว
     */
    private fun validateSickGesture(landmarks: HandLandmarkData, fingerStates: IntArray): Boolean {
        if (landmarks.landmarks.size < 21) {
            Log.v(TAG, "   ไม่สบาย: not enough landmarks")
            return false
        }

        val wrist = landmarks.landmarks[0]
        val wristY = wrist.y

        // 1) มือต้องอยู่ระดับสูง (ใกล้หน้าผาก)
        val handHighEnough = wristY < 0.55f
        if (!handHighEnough) {
            Log.d(TAG, "   ไม่สบาย: hand not high enough (wristY=$wristY)")
            return false
        }

        // 2) นิ้วต้องยืดอย่างน้อย 3 นิ้ว (ฝ่ามือเปิด ไม่ใช่กำมือ และไม่ใช่ชี้นิ้วเดียว)
        val nonThumbExtended = if (fingerStates.size >= 5) {
            fingerStates[1] + fingerStates[2] + fingerStates[3] + fingerStates[4]
        } else 0
        if (nonThumbExtended < 3) {
            Log.d(TAG, "   ไม่สบาย: only $nonThumbExtended/4 fingers extended (need ≥3)")
            return false
        }

        // 3) นิ้วต้องกระจาย (เพื่อแยกจาก "ปวดหัว" ที่นิ้วรวมเป็นกลุ่มเดียว)
        val indexTip = landmarks.landmarks[8]
        val middleTip = landmarks.landmarks[12]
        val ringTip = landmarks.landmarks[16]
        val pinkyTip = landmarks.landmarks[20]
        val handSize = sqrt(
            (middleTip.x - wrist.x).pow(2) +
            (middleTip.y - wrist.y).pow(2)
        )
        val cx = (indexTip.x + middleTip.x + ringTip.x + pinkyTip.x) / 4.0
        val cy = (indexTip.y + middleTip.y + ringTip.y + pinkyTip.y) / 4.0
        val avgSpread = (
            sqrt((indexTip.x - cx).pow(2)  + (indexTip.y - cy).pow(2)) +
            sqrt((middleTip.x - cx).pow(2) + (middleTip.y - cy).pow(2)) +
            sqrt((ringTip.x - cx).pow(2)   + (ringTip.y - cy).pow(2)) +
            sqrt((pinkyTip.x - cx).pow(2)  + (pinkyTip.y - cy).pow(2))
        ) / 4.0
        val spreadRatio = (avgSpread / handSize).toFloat()
        if (spreadRatio < 0.10f) {
            Log.d(TAG, "   ไม่สบาย: fingers too clustered, looks like ปวดหัว (spreadRatio=$spreadRatio)")
            return false
        }

        Log.d(TAG, "   ✅ ไม่สบาย: open palm at forehead " +
                "(wristY=$wristY, fingers=$nonThumbExtended/4, spread=$spreadRatio)")
        return true
    }
```

**Reason:** This new validation method checks three conditions specific to the "sick" gesture:
1. **Hand height** — must be near forehead level (wristY < 0.55)
2. **Finger extension** — at least 3 non-thumb fingers extended (open palm, not a fist)
3. **Finger spread** — fingers must be spread apart (distinguishes from "headache" where fingers cluster together)

---

### Change 4.4: Relax cluster spread threshold for headache

**Method:** `validateHeadacheGesture` (inside the `validateWithAngleFeatures` when block — actually inside `checkGestureCharacteristics` in VideoProcessor)

> **Note:** This change is actually in **VideoProcessor.kt**, not SignLanguageAnalyzer.kt. See Change 6.5.

---

## 5. SignLanguageConfig.kt

### Change 5.1: Add SICK_SIGN_TEMPLATE synthetic template

**Method:** (top-level property, after HEADACHE_SIGN_TEMPLATE)
**After line 47** (closing `)` of HEADACHE_SIGN_TEMPLATE), **add:**

```kotlin

    // 🩺 Template สังเคราะห์สำหรับท่า "ไม่สบาย"
    // ลักษณะท่า: มือข้างเดียว ฝ่ามือเปิด แตะที่หน้าผาก (เช็คว่ามีไข้)
    // - ข้อมือ (wrist) อยู่ใกล้ระดับศีรษะ (Y ~0.30 = อยู่ส่วนบนของกรอบภาพ)
    // - นิ้วทุกนิ้วยืดขึ้น (tip Y < MCP Y) ไม่กำมือ
    // - นิ้วกระจายออก (ไม่กระจุก) เพื่อแยกจากท่า "ปวดหัว"
    // ☝️ เมื่อได้ไฟล์วิดีโอจริง ให้แทนที่ template นี้ด้วยการเรียก
    //    videoProcessor.createTemplateFromVideos("ไม่สบาย", listOf(...), numHands = 1)
    val SICK_SIGN_TEMPLATE = HandLandmarkData(
        landmarks = listOf(
            // ข้อมือยกอยู่ระดับหน้าผาก (Y = 0.30 = ส่วนบนของเฟรม)
            Point3D(0.50f, 0.45f, 0.00f),  // 0: WRIST
            // นิ้วโป้ง (กางออกด้านข้าง)
            Point3D(0.42f, 0.42f, -0.02f), // 1: THUMB_CMC
            Point3D(0.38f, 0.38f, -0.04f), // 2: THUMB_MCP
            Point3D(0.36f, 0.34f, -0.05f), // 3: THUMB_IP
            Point3D(0.34f, 0.30f, -0.06f), // 4: THUMB_TIP
            // นิ้วชี้ (ยืดขึ้น)
            Point3D(0.46f, 0.36f, 0.00f),  // 5: INDEX_MCP
            Point3D(0.45f, 0.30f, -0.02f), // 6: INDEX_PIP
            Point3D(0.44f, 0.25f, -0.04f), // 7: INDEX_DIP
            Point3D(0.43f, 0.20f, -0.05f), // 8: INDEX_TIP
            // นิ้วกลาง (ยืดขึ้น สูงสุด)
            Point3D(0.50f, 0.36f, 0.00f),  // 9: MIDDLE_MCP
            Point3D(0.50f, 0.29f, -0.02f), // 10: MIDDLE_PIP
            Point3D(0.50f, 0.23f, -0.04f), // 11: MIDDLE_DIP
            Point3D(0.50f, 0.18f, -0.05f), // 12: MIDDLE_TIP
            // นิ้วนาง (ยืดขึ้น)
            Point3D(0.54f, 0.36f, 0.00f),  // 13: RING_MCP
            Point3D(0.55f, 0.30f, -0.02f), // 14: RING_PIP
            Point3D(0.56f, 0.25f, -0.04f), // 15: RING_DIP
            Point3D(0.57f, 0.20f, -0.05f), // 16: RING_TIP
            // นิ้วก้อย (ยืดขึ้น)
            Point3D(0.58f, 0.38f, 0.00f),  // 17: PINKY_MCP
            Point3D(0.60f, 0.32f, -0.02f), // 18: PINKY_PIP
            Point3D(0.62f, 0.27f, -0.04f), // 19: PINKY_DIP
            Point3D(0.64f, 0.22f, -0.05f)  // 20: PINKY_TIP
        )
    )
```

**Reason:** Provides a synthetic (hand-crafted) template for the "sick" gesture. This serves as a fallback template before real video templates are loaded. The template represents an open palm at forehead level with all fingers extended and spread apart.

---

### Change 5.2: Add "ไม่สบาย" SignWord to ALL_WORDS list

**Method:** (top-level property `ALL_WORDS`)
**At line 151** (after the closing `)` of the last SignWord entry for "แจ้งความ"), **replace:**

```kotlin
        )
    )
```

**with:**

```kotlin
        ),


        SignWord(
            word = "ไม่สบาย",
            meaning = "รู้สึกไม่สบาย/มีไข้",
            category = "โรงพยาบาล",
            mainVideoFile = "sick_main.mp4",
            testVideoFiles = listOf<String>("sick_test1.mp4", "sick_test2.mp4"),

            priority = 1,
            expectedAccuracy = 80,
            numHands = 1
        )
    )
```

**Reason:** Registers the new "ไม่สบาย" sign in the global word list so it's available throughout the app. Priority 1 means it's a high-priority word for testing.

---

## 6. VideoProcessor.kt

> **Note:** Many changes in this file are code formatting improvements (breaking long Log.d lines into multi-line format). Only the **functional changes** are listed below.

### Change 6.1: Add debug frame logging in createTemplateFromVideos

**Method:** `createTemplateFromVideos`
**After line 397** (`val frames = extractKeyFramesFromVideo(videoPath)`), **add:**

```kotlin
                    Log.d(TAG, "📁 '$label': Temp file path = $videoPath")
                    if (videoPath != null) {
                        Log.d(TAG, "🎬 '$label': Extracted ${frames.size} frames from video")

                        // Debug: check first frame properties
                        if (frames.isNotEmpty()) {
                            val sample = frames[0]
                            val pixel = sample.getPixel(sample.width / 2, sample.height / 2)
                            val r = (pixel shr 16) and 0xFF
                            val g = (pixel shr 8) and 0xFF
                            val b = pixel and 0xFF
                            Log.d(
                                TAG, "🖼️ '$label' frame[0]: ${sample.width}x${sample.height}, " +
                                        "center pixel RGB=($r,$g,$b)"
                            )
                            // Save first frame to Downloads for visual inspection
                            try {
                                val debugFile = java.io.File(
                                    android.os.Environment.getExternalStoragePublicDirectory(
                                        android.os.Environment.DIRECTORY_DOWNLOADS
                                    ),
                                    "debug_${label}_${System.currentTimeMillis()}.jpg"
                                )
                                java.io.FileOutputStream(debugFile).use { fos ->
                                    sample.compress(
                                        android.graphics.Bitmap.CompressFormat.JPEG,
                                        90,
                                        fos
                                    )
                                }
                                Log.d(TAG, "🖼️ Debug frame saved to: ${debugFile.absolutePath}")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to save debug frame", e)
                            }
                        }
```

Also **remove** line 407 (`Log.d(TAG, "checking template $label: $detectedHands hand(s)")`).

**Reason:** Adds debug logging that saves the first extracted frame to the Downloads folder so you can visually verify the video frames being used for templates. Also logs frame dimensions and pixel data to detect blank/corrupt frames.

---

### Change 6.2: Video rotation handling and dynamic frame interval

**Method:** `extractKeyFramesFromVideo`
**At line 809** (`val interval = 500L`), **replace the entire block through line 814:**

```kotlin
            // Extract frames at regular intervals (e.g., every 500ms)
            val interval = 500L // milliseconds
            for (time in 0 until duration step interval) {
                val bitmap = retriever.getFrameAtTime(time * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                bitmap?.let { frames.add(it) }
            }
```

**with:**

```kotlin
            // Get video rotation — front camera videos often have 90° or 270° metadata
            val rotationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val rotation = rotationStr?.toIntOrNull() ?: 0

            val interval = if (duration < 5000) 100L else 500L
            for (time in 0 until duration step interval) {
                val bitmap = retriever.getFrameAtTime(time * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bitmap != null) {
                    val rotated = if (rotation != 0) {
                        val matrix = android.graphics.Matrix()
                        matrix.postRotate(rotation.toFloat())
                        android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    } else {
                        bitmap
                    }
                    frames.add(rotated)
                }
            }
```

Also **after** the `retriever.release()` line, add:

```kotlin
            Log.d(TAG, "extractKeyFrames: $videoPath rotation=$rotation, ${frames.size} frames")
```

**Reason:** Two fixes:
1. **Video rotation**: Front camera videos often have rotation metadata (90°/270°). Without handling this, the hand landmarks are extracted from rotated frames, producing incorrect templates.
2. **Dynamic frame interval**: Short videos (< 5 seconds) now extract frames every 100ms instead of 500ms, giving more training data from short clips.

---

### Change 6.3: Add early rejections for ปวดหัว (headache) gesture

**Method:** `checkGestureCharacteristics` → `"ปวดหัว"` case
**At line 599** (after the comment about scale-invariant clustering), **before** `val wrist = landmarks.landmarks[0]`, **add:**

```kotlin
                    if (wristY > 0.70f) {
                        Log.d(TAG, " ❌ ปวดหัว: hand too low, not near forehead (wristY=${wristY}")
                        return false
                    }

                    // Headache = curled fingers (fist); Sick = extend fingers (open palm)
                    val nonThumbExtended = index + middle + ring + pinky
                    if (nonThumbExtended >= 2) {
                        Log.d(TAG, "❌ ปวดหัว: fingers should be curled, nonThumbExtended=$nonThumbExtended")
                        return false
                    }
```

**Reason:** Two early-exit checks before the expensive clustering computation:
1. If the hand is too low (wristY > 0.70), it's not at the forehead → not headache
2. If 2+ non-thumb fingers are extended, it's an open palm (sick/toilet) → not headache (headache uses a fist with fingers clustered)

---

### Change 6.4: Add height guard for เครื่องบิน (airplane) gesture

**Method:** `checkGestureCharacteristics` → `"เครื่องบิน"` case
**At line 658** (at the start of the `"เครื่องบิน" ->` block), **after** the opening `{`, **add:**

```kotlin
                    if (wristY < 0.25) {
                        return false
                    }
```

**Reason:** Prevents airplane from matching when the hand is very high up (Y < 0.25), which would be at the very top of the frame and likely a different gesture. The airplane gesture is typically made at chest/waist level.

---

### Change 6.5: Relax cluster spread threshold for headache

**Method:** `checkGestureCharacteristics` → `"ปวดหัว"` case
**At line 737**, **replace:**

```kotlin
        if (clusterSpreadRatio > 0.16f) {
            Log.d(TAG, "   ❌ ปวดหัว: fingertips too spread out, might be toilet or airplane")
```

**with:**

```kotlin
        if (clusterSpreadRatio > 0.50f) {
            Log.d(TAG, "   ❌ ปวดหัว: fingertips too spread out, clusterRatio is $clusterSpreadRatio")
```

**Reason:** The old threshold (0.16) was too strict — real headache gestures with slightly spread fingers were being rejected. Increased to 0.50 to allow more tolerance while still rejecting clearly spread gestures (toilet, sick).

---

### Change 6.6: Rewrite ห้องน้ำ (toilet) gesture check with position-based filtering

**Method:** `checkGestureCharacteristics` → `"ห้องน้ำ"` case
**At line 710** (the entire `"ห้องน้ำ" -> { ... }` block), **replace the whole block:**

```kotlin
                "ห้องน้ำ" -> {
                    // Toilet: Open hand gesture - detect by finger spread, not binary extended/curl
                    // Use RATIOS to work regardless of hand size or camera distance

                    // Calculate hand size (wrist to middle fingertip distance)
                    val wrist = landmarks.landmarks[0]
                    val middleTip = landmarks.landmarks[12]
                    val handSize = sqrt(
                        (middleTip.x - wrist.x).pow(2) +
                        (middleTip.y - wrist.y).pow(2)
                    )

                    // Check 1: Middle and ring fingers are extended (not curled like airplane)
                    // Use ratio: extension length / hand size
                    val middleTipMCP = sqrt(
                        (middleTip.x - landmarks.landmarks[9].x).pow(2) +
                        (middleTip.y - landmarks.landmarks[9].y).pow(2)
                    )
                    val ringTipMCP = sqrt(
                        (landmarks.landmarks[16].x - landmarks.landmarks[13].x).pow(2) +
                        (landmarks.landmarks[16].y - landmarks.landmarks[13].y).pow(2)
                    )

                    val middleExtensionRatio = middleTipMCP / handSize
                    val ringExtensionRatio = ringTipMCP / handSize
```

**with:**

```kotlin
                "ห้องน้ำ" -> {
                    // Toilet: Open hand gesture at waist level
                    // KEY DISTINCTION: Toilet is made at WAIST (high Y), not at HEAD (low Y like headache)

                    val wrist = landmarks.landmarks[0]
                    val wristY = wrist.y

                    // Check 1: Hand must be low enough (waist level, not forehead level)
                    // The real toilet gesture should be at waist level (Y ≈ 0.40–0.90).
                    // Headache: wrist Y ~0.2-0.3 (high up)
                    // Toilet: wrist Y ~0.4+ (lower down)
                    val handLowEnough = wristY > 0.40f && wristY < 0.90f

                    if (!handLowEnough) {
                        Log.v(
                            TAG,
                            "ห้องน้ำ: hand too high, looks like headache gesture (wristY=$wristY)"
                        )
                        return false
                    }

                    // Check 2: Middle and ring fingers are extended (not curled like airplane)
                    // Use ratio: extension length / hand size
                    val middleTip = landmarks.landmarks[12]
                    val handSize = sqrt(
                        (middleTip.x - wrist.x).pow(2) +
                                (middleTip.y - wrist.y).pow(2)
                    )

                    val middleTipMCP = sqrt(
                        (middleTip.x - landmarks.landmarks[9].x).pow(2) +
                                (middleTip.y - landmarks.landmarks[9].y).pow(2)
                    )
                    val ringTipMCP = sqrt(
                        (landmarks.landmarks[16].x - landmarks.landmarks[13].x).pow(2) +
                                (landmarks.landmarks[16].y - landmarks.landmarks[13].y).pow(2)
                    )

                    val middleExtensionRatio = middleTipMCP / handSize
                    val ringExtensionRatio = ringTipMCP / handSize
```

Then, **after** the existing `fingersExtended` check that returns `false`, **add** before the final `return true`:

```kotlin
                    // Reject if looks like airplane (pinky/index much more extended than middle/ring)
                    val indexTipMCP = sqrt(
                        (landmarks.landmarks[8].x - landmarks.landmarks[5].x).pow(2) +
                                (landmarks.landmarks[8].y - landmarks.landmarks[5].y).pow(2)
                    )
                    val pinkyTipMCP = sqrt(
                        (landmarks.landmarks[20].x - landmarks.landmarks[17].x).pow(2) +
                                (landmarks.landmarks[20].y - landmarks.landmarks[17].y).pow(2)
                    )
                    val indexExtensionRatio = indexTipMCP / handSize
                    val pinkyExtensionRatio = pinkyTipMCP / handSize
                    if (pinkyExtensionRatio > middleExtensionRatio + 0.08f ||
                        indexExtensionRatio > middleExtensionRatio + 0.08f) {
                        Log.d(TAG, "   ห้องน้ำ: looks like airplane (indexRatio=$indexExtensionRatio, middleRatio=$middleExtensionRatio, pinkyRatio=$pinkyExtensionRatio)")
                        return false
                    }
```

**Reason:** Three improvements to toilet recognition:
1. **Position-based filtering**: Toilet is only valid when hand is at waist level (Y > 0.40), preventing confusion with headache/sick which are at head level
2. **Height bounds**: Also rejects if hand is too low (Y > 0.90), which would be at frame bottom
3. **Airplane rejection**: If pinky/index extend much more than middle/ring, it's an airplane wing shape, not toilet

---

### Change 6.7: Add "ไม่สบาย" gesture check

**Method:** `checkGestureCharacteristics`
**Before** the final `return true` at the end of the `when` block (around line 770), **add a new case:**

```kotlin
                "ไม่สบาย" -> {
                    // 🩺 ไม่สบาย: มือเดียว ฝ่ามือเปิด แตะที่หน้าผาก (เช็คว่ามีไข้)
                    //
                    // จุดต่างจากท่าใกล้เคียง:
                    //   - ปวดหัว: นิ้วทั้งหมดรวม "กระจุก" กันแตะหน้าผาก   → fingertip cluster แคบ
                    //   - แจ้งความ: ชี้นิ้วเดียว (index)                  → นิ้วเดียวยืด
                    //   - ไม่สบาย: ฝ่ามือ "แบ" แตะหน้าผาก                 → นิ้วยืดหลายนิ้ว และกระจาย

                    val wrist = landmarks.landmarks[0]
                    val wristY = wrist.y

                    // 1) มือต้องอยู่ระดับสูง (ใกล้หน้าผาก)
                    val handHighEnough = wristY < 0.55f
                    if (!handHighEnough) {
                        Log.d(TAG, "   ไม่สบาย: hand not high enough (wristY=$wristY)")
                        return false
                    }

                    // 2) นิ้วต้องยืดอย่างน้อย 2 นิ้ว (ฝ่ามือเปิด ไม่ใช่กำมือเหมือนปวดหัว)
                    val nonThumbExtended = index + middle + ring + pinky
                    if (nonThumbExtended < 2) {
                        Log.d(TAG, "   ไม่สบาย: only $nonThumbExtended/4 fingers extended (need ≥2)")
                        return false
                    }

                        // 3) ต้องไม่ใช่ "ปวดหัว" — เช็ค fingertip cluster ว่ากระจาย ไม่กระจุก
                    val indexTip = landmarks.landmarks[8]
                    val middleTip = landmarks.landmarks[12]
                    val ringTip = landmarks.landmarks[16]
                    val pinkyTip = landmarks.landmarks[20]
                    val handSize = sqrt(
                        (middleTip.x - wrist.x).pow(2) +
                                (middleTip.y - wrist.y).pow(2)
                    )
                    val cx = (indexTip.x + middleTip.x + ringTip.x + pinkyTip.x) / 4.0
                    val cy = (indexTip.y + middleTip.y + ringTip.y + pinkyTip.y) / 4.0
                    val avgSpread = (
                            sqrt((indexTip.x - cx).pow(2) + (indexTip.y - cy).pow(2)) +
                                    sqrt((middleTip.x - cx).pow(2) + (middleTip.y - cy).pow(2)) +
                                    sqrt((ringTip.x - cx).pow(2) + (ringTip.y - cy).pow(2)) +
                                    sqrt((pinkyTip.x - cx).pow(2) + (pinkyTip.y - cy).pow(2))
                            ) / 4.0
                    val spreadRatio = (avgSpread / handSize).toFloat()

                    Log.d(TAG, "   🩺 ไม่สบาย: wristY=$wristY, handHighEnough=$handHighEnough")
                    Log.d(TAG, "   🩺 ไม่สบาย: spreadRatio=$spreadRatio (threshold=0.10)")

                    if (spreadRatio < 0.10f) {
                        Log.d(
                            TAG,
                            "   ไม่สบาย: fingers too clustered, looks like ปวดหัว (spreadRatio=$spreadRatio)"
                        )
                        return false
                    }

                    return true
                }
```

Also, **replace** the final `return true` at the end of `checkGestureCharacteristics` with:

```kotlin
        Log.d(TAG, "   ❌ '$word': no matching gesture check for $actualHands hands")
        return false
```

**Reason:** Adds the gesture characteristics check for the new "sick" sign. The checks are:
1. **Height**: hand must be at forehead level (wristY < 0.55)
2. **Finger extension**: at least 2 non-thumb fingers must be extended (open palm)
3. **Finger spread**: fingertips must be spread apart (spreadRatio ≥ 0.10), distinguishing from "headache" where fingers cluster together

The `return false` at the end ensures that unknown words are rejected rather than passing through.

---

### Change 6.8: Rewrite recognizeSign() with flexible hand count matching

**Method:** `recognizeSign`
**At line 836** (after the `signTemplates.isEmpty()` check), **replace everything from line 837** (`Log.d(TAG, "===...`) **through line 895** (`bestConfidence = max(...)`):

Old code to **remove**:
```kotlin
        Log.d(TAG, "========================================")
        Log.d(TAG, "🔍 Detected hands: $numDetectedHands")

        // Normalize current input
        val normalizedCurrent = normalizeHandLandmarks(currentGestureLandmarks)

        var bestMatchLabel: String? = null
        var bestConfidence: Float = 0f

        // Iterate through all signs
        for ((label, templates) in signTemplates) {
            // Skip if hand count doesn't match
            val requiredHands = templates.firstOrNull()?.numHands ?: 1
            if (requiredHands != numDetectedHands) {
                Log.d(TAG, "   ❌ '$label' skipped: needs $requiredHands hands, got $numDetectedHands")
                continue
            }

            // Check gesture characteristics (hand position, alignment, etc.)
            if (!checkGestureCharacteristics(currentGestureLandmarks, label)) {
                Log.d(TAG, "   ❌ '$label' skipped: gesture characteristics don't match")
                continue
            }

            // Trim current landmarks to match template
            val requiredLandmarks = requiredHands * 21
            val currentToCompare = if (currentGestureLandmarks.landmarks.size > requiredLandmarks) {
                HandLandmarkData(currentGestureLandmarks.landmarks.take(requiredLandmarks))
            } else {
                currentGestureLandmarks
            }
            val normalizedCurrentForCompare = normalizeHandLandmarks(currentToCompare)

            // Find the BEST matching template for this sign
            var bestDistanceForSign = Float.MAX_VALUE
            for ((index, template) in templates.withIndex()) {
                if (normalizedCurrentForCompare.landmarks.size != template.landmarks.landmarks.size) {
                    continue
                }

                val normalizedTemplate = normalizeHandLandmarks(template.landmarks)
                val distance = calculateEuclideanDistance(normalizedCurrentForCompare, normalizedTemplate)

                if (distance < bestDistanceForSign) {
                    bestDistanceForSign = distance
                }
            }

            Log.d(TAG, "   '$label': best distance = ${String.format("%.4f", bestDistanceForSign)} (from ${templates.size} templates)")

            // Compare with overall best
            if (bestDistanceForSign < minDistance) {
                minDistance = bestDistanceForSign
                bestMatchLabel = label

                val maxDistance = if (requiredHands == 2) 3.5f else 2.0f
                bestConfidence = max(0.0f, (1.0f - minDistance / maxDistance) * 100)
            }
        }
```

New code to **insert**:
```kotlin
        // Clamp to actual hands in landmarks data to prevent IndexOutOfBoundsException
        val actualHands = currentGestureLandmarks.landmarks.size / 21
        val numDetectedHands = minOf(numDetectedHands, actualHands)
        Log.d(TAG, "========================================")
        Log.d(TAG, "🔍 Detected hands: $numDetectedHands")

        var bestMatchLabel: String? = null
        var minDistance = Float.MAX_VALUE
        var bestConfidence: Float = 0f

        // Iterate through all signs
        for ((label, templates) in signTemplates) {
            // Check hand count compatibility
            val requiredHands = templates.firstOrNull()?.numHands ?: 1
            if (requiredHands > numDetectedHands) {
                Log.d(TAG, "   ❌ '$label' mismatched num hands: needs $requiredHands hands, got $numDetectedHands")
                continue
            }

            // Check gesture characteristics and match against templates
            var bestDistanceForSign = Float.MAX_VALUE

            if (requiredHands < numDetectedHands) {
                // Sign needs fewer hands than detected — only use the ACTIVE hand
                // to prevent resting hands from causing false matches (e.g. tall person's
                // resting hand at Y≈0.80 matching toilet when they're making headache gesture)
                var bestHandIdx = 0
                var bestScore = Float.MAX_VALUE
                for (handIdx in 0 until numDetectedHands) {
                    val wrist = currentGestureLandmarks.landmarks[handIdx * 21]
                    // Lower score = more active (higher position, more centered)
                    val score = wrist.y + kotlin.math.abs(wrist.x - 0.5f) * 0.3f
                    if (score < bestScore) {
                        bestScore = score
                        bestHandIdx = handIdx
                    }
                }

                val start = bestHandIdx * 21
                val end = minOf(start + 21, currentGestureLandmarks.landmarks.size)
                if (end - start < 21) continue

                val singleHandLandmarks = HandLandmarkData(
                    currentGestureLandmarks.landmarks.subList(start, end)
                )

                Log.d(TAG, "   🖐️ Single-hand sign '$label': using active hand #$bestHandIdx (wristY=${String.format("%.3f", singleHandLandmarks.landmarks[0].y)})")

                if (!checkGestureCharacteristics(singleHandLandmarks, label)) {
                    Log.d(TAG, "   ❌ '$label' skipped: active hand didn't pass characteristics check")
                    continue
                }

                val normalizedCurrent = normalizeHandLandmarks(singleHandLandmarks)
                for (template in templates) {
                    val normalizedTemplate = normalizeHandLandmarks(template.landmarks)
                    if (normalizedCurrent.landmarks.size != normalizedTemplate.landmarks.size) continue
                    val distance = calculateEuclideanDistance(normalizedCurrent, normalizedTemplate)
                    if (distance < bestDistanceForSign) {
                        bestDistanceForSign = distance
                    }
                }
            } else {
                // Normal case: exact hand count match
                if (!checkGestureCharacteristics(currentGestureLandmarks, label)) {
                    Log.d(TAG, "   ❌ '$label' skipped: gesture characteristics don't match")
                    continue
                }

                val requiredLandmarks = requiredHands * 21
                val currentToCompare = if (currentGestureLandmarks.landmarks.size > requiredLandmarks) {
                    HandLandmarkData(currentGestureLandmarks.landmarks.take(requiredLandmarks))
                } else {
                    currentGestureLandmarks
                }
                val normalizedCurrentForCompare = normalizeHandLandmarks(currentToCompare)

                for ((index, template) in templates.withIndex()) {
                    if (normalizedCurrentForCompare.landmarks.size != template.landmarks.landmarks.size) {
                        continue
                    }
                    val normalizedTemplate = normalizeHandLandmarks(template.landmarks)
                    val distance = calculateEuclideanDistance(normalizedCurrentForCompare, normalizedTemplate)
                    if (distance < bestDistanceForSign) {
                        bestDistanceForSign = distance
                    }
                }
            }

            Log.d(TAG, "   '$label': best distance = ${String.format("%.4f", bestDistanceForSign)} (from ${templates.size} templates)")

            // Compare with overall best
            if (bestDistanceForSign < minDistance) {
                minDistance = bestDistanceForSign
                bestMatchLabel = label

                val maxDistance = if (requiredHands == 2) 3.5f else 3.0f
                bestConfidence = max(0.0f, (1.0f - minDistance / maxDistance) * 100)
            }
        }
```

**Reason — This is the most critical change with three key improvements:**

1. **IndexOutOfBoundsException fix** (lines 1186-1187):
   - Added `val actualHands = currentGestureLandmarks.landmarks.size / 21` and `val numDetectedHands = minOf(numDetectedHands, actualHands)`
   - Prevents crash when `numDetectedHands` (from temporal tracker) says 2 hands, but landmarks only has 21 points (1 hand)
   - This was causing the app to crash with `Index 21 out of bounds for length 21`

2. **Flexible hand count matching** (new `if (requiredHands < numDetectedHands)` branch):
   - Old code: `requiredHands != numDetectedHands` → skip (exact match only)
   - New code: `requiredHands > numDetectedHands` → skip, but `requiredHands < numDetectedHands` → extract active hand
   - When MediaPipe detects 2 hands but the sign only needs 1, the system now picks the most "active" hand (highest position, most centered) instead of skipping the sign entirely
   - This prevents false matches from a person's resting second hand

3. **Single-hand confidence threshold increased** (line 893):
   - Changed `maxDistance` from `2.0f` to `3.0f` for single-hand signs
   - This broadens the acceptance range for single-hand gestures, improving recognition rate

---

## Summary of Key Learning Points

1. **Defensive programming**: Always clamp array indices to actual data size (the `minOf` fix prevents crashes)
2. **Flexible matching**: Instead of requiring exact hand count matches, extract the most relevant hand when extra hands are detected
3. **Gesture disambiguation**: Use multiple signals (height, finger extension, finger spread) together rather than relying on a single check
4. **Video preprocessing**: Handle video rotation metadata from front cameras to get correct landmark data
5. **Synthetic templates**: Hand-crafted landmark data can serve as a starting point before real training videos are available
