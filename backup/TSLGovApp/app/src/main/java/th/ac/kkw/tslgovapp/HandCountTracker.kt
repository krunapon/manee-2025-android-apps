package th.ac.kkw.tslgovapp

import android.util.Log

/**
 * ============================================================================
 * TEMPORAL HAND COUNT TRACKER
 * ============================================================================
 *
 * Purpose: Tracks hand detections over time using temporal modeling.
 *
 * Problem: MediaPipe may detect 2 hands in some frames and 1 hand in others
 * during the same gesture (e.g., when one hand moves out of frame or becomes
 * occluded). This causes the recognition system to try matching different
 * gesture types mid-gesture, leading to false positives or missed detections.
 *
 * Solution: Maintain a sliding window of recent hand detections and use a
 * voting mechanism to determine the "true" hand count. This provides:
 * - Stability: Prevents flickering between 1 and 2 hands
 * - Accuracy: Uses temporal context to make better decisions
 * - Hysteresis: Requires strong evidence to change the locked hand count
 *
 * Usage:
 * 1. Call addFrame() each time a new frame is processed
 * 2. Call getCurrentHandCount() to get the stable hand count
 * 3. Call reset() when starting a new gesture
 *
 * ============================================================================
 */

class HandCountTracker {
    companion object {
        private const val TAG = "HandCountTracker"

        // Number of recent frames to keep in history
        // Larger = more stable but slower to react to changes
        private const val HISTORY_SIZE = 3

        // Minimum percentage of frames that must agree before locking a hand count
        // 0.6 = 60% of frames must have the same hand count
        private const val VOTE_THRESHOLD = 0.6f

        // Higher confidence required to change from an already-locked state
        // This prevents flickering when the hand count is ambiguous
        private const val CHANGE_THRESHOLD = 0.8f  // 80% to change locked value
    }
        /**
         * History of hand detections.
         * Each entry: Pair(handCount, timestamp)
         * - handCount: Number of hands detected in that frame (0, 1, or 2)
         * - timestamp: When the frame was captured (for potential time-based filtering)
         */
        private val history = mutableListOf<Pair<Int, Long>>()

        /**
         * The current stable hand count.
         * null = Not enough data yet to make a decision
         * 1 = Locked to single-hand gesture
         * 2 = Locked to two-hand gesture
         */
        private var stableHandCount: Int? = null

        /**
         * Add a new frame's hand count to the history and compute the stable hand count.
         *
         * @param detectedHands Number of hands detected in this frame (0, 1, or 2)
         * @return The stable hand count after considering temporal history, or null if
         *         there isn't enough data yet (need at least 3 frames)
         */
        fun addFrame(detectedHands: Int): Int? {
            val timestamp = System.currentTimeMillis()

            // Add new frame to history
            history.add(Pair(detectedHands, timestamp))

            // Remove old entries to maintain sliding window
            cleanHistory()

            // Need at least 3 frames to make a statistically significant decision
            if (history.size < 3) {
                Log.v(TAG, "   ⏳ Not enough frames yet (${history.size}/$HISTORY_SIZE)")
                return null
            }

            // Count votes for each hand count (how many frames detected 0, 1, or 2 hands)
            val votes = mutableMapOf<Int, Int>()
            for ((count, _) in history) {
                votes[count] = votes.getOrDefault(count, 0) + 1
            }

            // Find the most common hand count (the winner)
            val winningCount = votes.maxByOrNull { it.value }?.key ?: return null
            val winCount = votes[winningCount]!!
            val winPercentage = winCount.toFloat() / history.size

            Log.v(TAG, "   📊 Votes: 0h=${votes[0] ?: 0}, 1h=${votes[1] ?: 0}, 2h=${votes[2]
                ?: 0}, " +
                    "winner=$winningCount (${String.format("%.0f%%", winPercentage * 100)})")

            // Only update stable count if it meets the threshold
            if (winPercentage >= VOTE_THRESHOLD) {
                if (stableHandCount == null) {
                    // First time locking a hand count
                    stableHandCount = winningCount
                    Log.d(TAG, "🔒 Initial hand count locked to: $stableHandCount " +
                            "(confidence=${String.format("%.0f%%", winPercentage * 100)})")
                } else if (stableHandCount != winningCount) {
                    // Apply hysteresis: require higher confidence to change from locked state
                    // This prevents rapid switching between 1 and 2 hands
                    if (winPercentage >= CHANGE_THRESHOLD) {
                        Log.d(TAG, "🔄 Hand count changed: $stableHandCount -> $winningCount "
                                +
                                "(confidence=${String.format("%.0f%%", winPercentage *
                                        100)})")
                        stableHandCount = winningCount
                    } else {
                        Log.v(TAG, "   ⚠️ Insufficient confidence to change hand count " +
                                "($winningCount @ ${String.format("%.0f%%", winPercentage *
                                        100)} < " +
                                "${String.format("%.0f%%", CHANGE_THRESHOLD * 100)})")
                    }
                } else {
                    // Same hand count, confidence is good
                    Log.v(TAG, "   ✅ Hand count stable: $stableHandCount")
                }
            }

            return stableHandCount
        }

        /**
         * Remove entries older than HISTORY_SIZE from history to maintain a sliding window.
         * This ensures we only consider recent frames when determining the hand count.
         */
        private fun cleanHistory() {
            while (history.size > HISTORY_SIZE) {
                history.removeAt(0)
            }
        }

        /**
         * Reset the tracker state.
         * Call this when:
         * - Starting a new gesture (in SignLanguageAnalyzer.startDetection())
         * - All hands are lost from the frame
         * - The user explicitly restarts detection
         */
        fun reset() {
            history.clear()
            stableHandCount = null
            Log.d(TAG, "🔄 Tracker reset")
        }

        /**
         * Get the current stable hand count without adding a new frame.
         * Useful for logging and debugging.
         *
         * @return The locked hand count (1 or 2), or null if not determined yet
         */
        fun getCurrentHandCount(): Int? = stableHandCount

        /**
         * Get the current history size (for debugging).
         */
        fun getHistorySize(): Int = history.size

        /**
         * Get detailed history information (for debugging).
         */
        fun getHistoryInfo(): String {
            if (history.isEmpty()) return "empty"
            val counts = history.map { it.first }
            return "[$counts] -> $stableHandCount"
        }
    }
