/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.shared.ui.marquee.MarqueeText as SharedMarqueeText

/**
 * One line of text that scrolls sideways when it does not fit and fades at its edges while it scrolls. The motion,
 * the fade and the pass timing come from the shared marquee. [animationEnabled] and [LocalAnimationsDisabled] turn the
 * motion off, and [phaseKey] keeps a line's pass when it is left and re-entered. [animationMode] is accepted for
 * existing callers and has no effect.
 */
@Composable
fun MarqueeText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
    fadeWidth: Dp = 24.dp,
    animationMode: MarqueeAnimationMode = MarqueeAnimationMode.Immediately,
    iterations: Int = Int.MAX_VALUE,
    animationEnabled: Boolean = true,
    phaseKey: Any? = null,
) {
    SharedMarqueeText(
        text = text,
        modifier = modifier,
        style = style.merge(TextStyle(fontSize = fontSize)),
        color = color,
        fontWeight = fontWeight,
        textAlign = textAlign,
        inlineContent = inlineContent,
        fadeWidth = fadeWidth,
        active = animationEnabled && !LocalAnimationsDisabled.current,
        phaseKey = phaseKey,
        iterations = iterations,
    )
}

internal fun marqueeShouldAnimate(
    contentWidth: Int,
    viewportWidth: Int,
    enabled: Boolean,
    motionAllowed: Boolean,
    iterations: Int,
): Boolean = enabled && motionAllowed && iterations > 0 && viewportWidth > 0 && contentWidth > viewportWidth

internal fun marqueeFadesEdges(animate: Boolean, fadeWidth: Dp): Boolean = animate && fadeWidth > 0.dp

/** [MarqueeText] for plain text, which is most of them. */
@Composable
fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    fadeWidth: Dp = 24.dp,
    animationMode: MarqueeAnimationMode = MarqueeAnimationMode.Immediately,
    iterations: Int = Int.MAX_VALUE,
    animationEnabled: Boolean = true,
    phaseKey: Any? = null,
) {
    MarqueeText(
        text = remember(text) { AnnotatedString(text) },
        modifier = modifier,
        style = style,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        textAlign = textAlign,
        fadeWidth = fadeWidth,
        animationMode = animationMode,
        iterations = iterations,
        animationEnabled = animationEnabled,
        phaseKey = phaseKey,
    )
}
