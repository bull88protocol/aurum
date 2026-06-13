package com.sun.aurum.domain.hmai

import com.sun.aurum.model.PillarResult

object Pillar6Coherence {
    fun score(pillars: List<PillarResult>): PillarResult {
        if (pillars.size < 2) return PillarResult(6, "Signal Coherence", 5.0, 10.0, "MODERATE_COHERENCE", emptyMap(), "Insufficient pillars")
        val normalized = pillars.map { p -> if (p.maxScore > 0) (p.score / p.maxScore) * 100.0 else 50.0 }
        val pairs = mutableListOf<Pair<Int, Int>>()
        for (i in normalized.indices) for (j in (i + 1) until normalized.size) pairs.add(Pair(i, j))
        if (pairs.isEmpty()) return PillarResult(6, "Signal Coherence", 5.0, 10.0, "MODERATE_COHERENCE", emptyMap(), "")
        var agreements = 0; var conflicts = 0
        for ((i, j) in pairs) {
            val a = normalized[i]; val b = normalized[j]
            when {
                a > 60 && b > 60 -> agreements++
                a < 40 && b < 40 -> agreements++
                (a > 60 && b < 40) || (a < 40 && b > 60) -> conflicts++
            }
        }
        val totalPairs = pairs.size
        val agreementRatio = agreements.toDouble() / totalPairs
        val conflictRatio  = conflicts.toDouble()  / totalPairs
        val rawScore = when {
            agreementRatio >= 0.8 -> (8 + 2 * agreementRatio).coerceAtMost(10.0)
            agreementRatio >= 0.5 -> (5 + 3 * agreementRatio)
            agreementRatio >= 0.2 -> (2 + 3 * agreementRatio)
            else -> (0 + 2 * agreementRatio)
        }
        val finalScore = (rawScore - conflictRatio * 2).coerceIn(0.0, 10.0)
        val label = when { finalScore >= 8 -> "HIGH_COHERENCE"; finalScore >= 5 -> "MODERATE_COHERENCE"; finalScore >= 2 -> "LOW_COHERENCE"; else -> "SIGNAL_CONFLICT" }
        val components = mapOf("agreements" to agreements.toDouble(), "conflicts" to conflicts.toDouble(), "total_pairs" to totalPairs.toDouble(), "agreement_ratio" to agreementRatio)
        return PillarResult(6, "Signal Coherence", finalScore, 10.0, label, components, "Agreements=$agreements Conflicts=$conflicts / $totalPairs pairs")
    }
}
