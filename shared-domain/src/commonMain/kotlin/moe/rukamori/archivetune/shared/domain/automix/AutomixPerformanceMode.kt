package moe.rukamori.archivetune.shared.domain.automix

/**
 * Automix (the analysis-driven transition engine, ported from 4nx3b): how much CPU the background
 * beat/vocal analysis may use. Crossfade and automix are mutually exclusive — at most one of them
 * shapes a given track boundary.
 */
enum class AutomixPerformanceMode(val inferenceThreads: Int) {
    EFFICIENT(1),
    BALANCED(2),
    PERFORMANCE(4),
}
