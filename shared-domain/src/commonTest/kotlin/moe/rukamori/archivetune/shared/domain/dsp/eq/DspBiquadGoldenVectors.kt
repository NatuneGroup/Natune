package moe.rukamori.archivetune.shared.domain.dsp.eq

/**
 * Response golden vectors for the biquad designs, computed from the Audio EQ Cookbook formulas
 * independently of this module and pinned to 1e-6. A native or hand-rolled port compares against these
 * numbers instead of re-deriving them, so a coefficient that quietly changes shape fails here first.
 *
 * Regenerate by re-evaluating the cookbook equations, never by copying [BiquadDesign] output.
 */
internal object DspBiquadGoldenVectors {
    const val SAMPLE_RATE_HZ = 48_000
    const val TOLERANCE = 1e-6

    val frequenciesHz = doubleArrayOf(
        20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 16000.0
    )

    /** One design, its pinned coefficients and its pinned magnitude at every entry of [frequenciesHz]. */
    data class Case(
        val name: String,
        val coefficients: BiquadCoefficients,
        val magnitudes: DoubleArray,
    )

    val cases: List<Case> = listOf(
        Case(
            name = "LOW_PASS_1000_Q_07071",
            coefficients = BiquadCoefficients(
                b0 = 0.003916126660547383,
                b1 = 0.007832253321094766,
                b2 = 0.003916126660547383,
                a1 = -1.815341082704568,
                a2 = 0.8310055893467576,
            ),
            magnitudes = doubleArrayOf(
                0.9999999204559096, 0.999996892785668, 0.9999502859168351, 0.999205327289042, 0.9702646429346865, 0.7071067811865505, 0.24057709943870356, 0.037255863327080904, 0.007296024084942792, 0.0014319803710821789
            ),
        ),
        Case(
            name = "HIGH_PASS_100_Q_07071",
            coefficients = BiquadCoefficients(
                b0 = 0.9907866979404267,
                b1 = -1.9815733958808535,
                b2 = 0.9907866979404267,
                a1 = -1.981488509144573,
                a2 = 0.9816582826171341,
            ),
            magnitudes = doubleArrayOf(
                0.03996694435791923, 0.24253073586345184, 0.7071067811859857, 0.9701473891639124, 0.9992020522541796, 0.9999502859168619, 0.9999969457052129, 0.9999999308958296, 0.9999999973532834, 0.99999999989805
            ),
        ),
        Case(
            name = "PEAKING_1000_PLUS_6DB_Q_1",
            coefficients = BiquadCoefficients(
                b0 = 1.043953086990335,
                b1 = -1.8953207239365961,
                b2 = 0.8677222847598566,
                a1 = -1.8953207239365961,
                a2 = 0.9116753717501915,
            ),
            magnitudes = doubleArrayOf(
                1.0002980961246446, 1.0018675047675896, 1.0075331502182665, 1.0311556798274317, 1.2415638757951797, 1.995262314968876, 1.2396513333145478, 1.029034370311873, 1.0054954169736812, 1.0010714688667732
            ),
        ),
        Case(
            name = "PEAKING_250_MINUS_4_5DB_Q_0_9",
            coefficients = BiquadCoefficients(
                b0 = 0.9906961416388775,
                b1 = -1.9529335957111675,
                b2 = 0.9632836393179424,
                a1 = -1.9529335957111675,
                a2 = 0.95397978095682,
            ),
            magnitudes = doubleArrayOf(
                0.9957146593125162, 0.9730161393926339, 0.8909275143900329, 0.6420708223836982, 0.8311377002941149, 0.9577803114659497, 0.9896305883353216, 0.9984453066612305, 0.9996958864242984, 0.9999403192836283
            ),
        ),
        Case(
            name = "LOW_SHELF_200_PLUS_4DB_Q_07071",
            coefficients = BiquadCoefficients(
                b0 = 1.004280619651054,
                b1 = -1.966848209585342,
                b2 = 0.963416421298848,
                a1 = -1.9670048370757747,
                a2 = 0.9675404134594692,
            ),
            magnitudes = doubleArrayOf(
                1.5848176254082498, 1.58195636185294, 1.5413124952842339, 1.2589254117940296, 1.0119309326450994, 1.000757922896368, 1.0000466218606596, 1.000001054913961, 1.0000000404036868, 1.0000000015563257
            ),
        ),
        Case(
            name = "HIGH_SHELF_5000_MINUS_3DB_Q_07071",
            coefficients = BiquadCoefficients(
                b0 = 0.7645903678406224,
                b1 = -0.794889607687788,
                b2 = 0.2825679918627737,
                a1 = -1.1748465804602355,
                a2 = 0.42711533247584293,
            ),
            magnitudes = doubleArrayOf(
                0.9999999999221023, 0.9999999969569929, 0.999999951309776, 0.9999992208248798, 0.9999695301080728, 0.9995111739054338, 0.9922460398591179, 0.8413951416451951, 0.7171856761828429, 0.708313256208786
            ),
        ),
        Case(
            name = "HIGH_SHELF_SLOPE_10500_PLUS_4_8DB_S_085",
            coefficients = BiquadCoefficients(
                b0 = 1.3592993931099027,
                b1 = -0.5093420300702903,
                b2 = 0.2141838266020734,
                a1 = -0.06719982078863498,
                a2 = 0.13134101043032084,
            ),
            magnitudes = doubleArrayOf(
                1.000000260927926, 1.000001630925574, 1.0000065255028527, 1.000026130823109, 1.000164578932643, 1.0006763779841426, 1.0029971162327378, 1.0319565978716698, 1.2784505121860728, 1.6600458684979087
            ),
        ),
    )
}
