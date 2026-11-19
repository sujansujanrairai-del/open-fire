package com.blindtechnexus.openfire.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blindtechnexus.openfire.model.Combat
import com.blindtechnexus.openfire.ui.theme.BtnBlue
import com.blindtechnexus.openfire.ui.theme.BtnBlueEnd
import com.blindtechnexus.openfire.ui.theme.BtnDanger
import com.blindtechnexus.openfire.ui.theme.BtnDangerEnd
import com.blindtechnexus.openfire.ui.theme.BtnPrimary
import com.blindtechnexus.openfire.ui.theme.BtnPrimaryEnd
import com.blindtechnexus.openfire.ui.theme.BtnWarn
import com.blindtechnexus.openfire.ui.theme.BtnWarnEnd
import com.blindtechnexus.openfire.ui.theme.CardBg
import com.blindtechnexus.openfire.ui.theme.CardBorder
import com.blindtechnexus.openfire.ui.theme.DarkBgTop
import com.blindtechnexus.openfire.ui.theme.DangerBright
import com.blindtechnexus.openfire.ui.theme.HintBg
import com.blindtechnexus.openfire.ui.theme.HintText
import com.blindtechnexus.openfire.ui.theme.HpDevilEnd
import com.blindtechnexus.openfire.ui.theme.HpDevilStart
import com.blindtechnexus.openfire.ui.theme.HpPlayerEnd
import com.blindtechnexus.openfire.ui.theme.HpPlayerStart
import com.blindtechnexus.openfire.ui.theme.LogBg
import com.blindtechnexus.openfire.ui.theme.LogText
import com.blindtechnexus.openfire.ui.theme.TipBg
import com.blindtechnexus.openfire.ui.theme.TipText
import com.blindtechnexus.openfire.ui.theme.WarnBright
import kotlin.math.roundToInt

enum class ButtonType {
    DEFAULT,
    PRIMARY,
    DANGER,
    WARN
}

@Composable
fun GameButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    type: ButtonType = ButtonType.DEFAULT,
    testTag: String = "game_button",
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null
) {
    // A caller that needs to land the keyboard or switch focus on this button
    // (the Confirm/Cancel pair of a dialog, the first control of a screen)
    // passes its own requester; otherwise the button keeps the default browse
    // order of the surrounding column.
    val bgColor = when (type) {
        ButtonType.PRIMARY -> BtnPrimary
        ButtonType.DANGER -> BtnDanger
        ButtonType.WARN -> BtnWarn
        ButtonType.DEFAULT -> BtnBlue
    }
    val bgBrush = when (type) {
        ButtonType.PRIMARY -> Brush.verticalGradient(listOf(BtnPrimary, BtnPrimaryEnd))
        ButtonType.DANGER -> Brush.verticalGradient(listOf(BtnDanger, BtnDangerEnd))
        ButtonType.WARN -> Brush.verticalGradient(listOf(BtnWarn, BtnWarnEnd))
        ButtonType.DEFAULT -> Brush.verticalGradient(listOf(BtnBlue, BtnBlueEnd))
    }
    val shape = RoundedCornerShape(14.dp)

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .background(
                brush = if (enabled) bgBrush else Brush.verticalGradient(
                    listOf(Color(0xFF2A3342), Color(0xFF222A37))
                ),
                shape = shape
            )
            .testTag(testTag)
            // A caller that has to land the focus here (a dialog's Cancel, the
            // first control of a screen) passes its own requester. The button
            // itself already owns exactly one focusable, clickable node with
            // its label, so no second focus target is ever added.
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            ),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Color(0xFF9AA5B5)
        ),
        shape = shape
    ) {
        Text(
            text = text,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun GameCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = CardBg,
    borderColor: Color = CardBorder,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(2.dp, borderColor, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(listOf(backgroundColor, DarkBgTop)),
                    shape = RoundedCornerShape(16.dp)
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
fun LogBox(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(LogBg)
            .border(2.dp, LogText, RoundedCornerShape(14.dp))
            .padding(14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = LogText,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun TipBox(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(TipBg)
            .border(2.dp, TipText, RoundedCornerShape(14.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = TipText,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun AlertBox(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BtnDanger)
            .border(2.dp, DangerBright, RoundedCornerShape(14.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun HintBox(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(HintBg)
            .border(2.dp, BtnPrimary, RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Text(
            text = text,
            color = HintText,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
    }
}

@Composable
fun StatBadge(label: String, value: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(BtnBlue)
            .padding(horizontal = 14.dp, vertical = 7.dp)
            .semantics { contentDescription = "$label: $value" }
    ) {
        Text(
            text = "$label: $value",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun HpBar(current: Int, max: Int, isDevil: Boolean, modifier: Modifier = Modifier) {
    val fraction = if (max > 0) (current.toFloat() / max.toFloat()).coerceIn(0f, 1f) else 0f
    val animatedFraction by animateFloatAsState(targetValue = fraction, label = "hp_anim")

    val gradient = if (isDevil) {
        Brush.horizontalGradient(listOf(HpDevilStart, HpDevilEnd))
    } else {
        Brush.horizontalGradient(listOf(HpPlayerStart, HpPlayerEnd))
    }

    val desc = if (isDevil) "Enemy health $current of $max" else "Your health $current of $max"

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF101820))
            .semantics { contentDescription = desc }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedFraction)
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(gradient)
        )
    }
}

@Composable
fun RadarBar(distance: Float, modifier: Modifier = Modifier, extraDesc: String = "") {
    val prox = (Combat.proximityOf(distance) / 100f).coerceIn(0f, 1f)
    val animatedProx by animateFloatAsState(targetValue = prox, label = "radar_anim")

    val gradient = Brush.horizontalGradient(
        listOf(HpPlayerStart, WarnBright, DangerBright)
    )

    val baseDesc = "Enemy proximity ${(prox * 100).roundToInt()} percent, distance ${distance.roundToInt()} meters"
    val desc = if (extraDesc.isEmpty()) baseDesc else "$baseDesc. $extraDesc"

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0xFF101820))
            .semantics { contentDescription = desc }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedProx)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(gradient)
        )
    }
}
