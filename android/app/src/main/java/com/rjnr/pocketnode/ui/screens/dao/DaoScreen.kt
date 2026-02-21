package com.rjnr.pocketnode.ui.screens.dao

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rjnr.pocketnode.ui.theme.CkbWalletTheme

@Composable
fun DaoScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .background(MaterialTheme.colorScheme.surface),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    )
    {

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                FeatureCard()

                Spacer(Modifier.height(32.dp)) // mt-8
                ExternalCaption()
            }
        }

    }
}

@Composable
private fun FeatureCard() {
    val cardShape = RoundedCornerShape(12.dp) // rounded-lg

    Column(
        modifier = Modifier
            .widthIn(max = 380.dp) // max-w-sm
            .fillMaxWidth()
            .clip(cardShape)
            .background(color = MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, cardShape)
            .padding(32.dp), // p-8
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Icon Section with glow
        Box(
            modifier = Modifier
                .padding(bottom = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            // blurred green glow behind lock
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.20f))
                    .blur(28.dp)
            )
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = "Lock",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
        }

        Text(
            text = "Nervos DAO",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Text(
            text = "Earn compensation by locking CKB in the Nervos DAO. Secure your assets while contributing to the network's decentralization.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // Separator
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.onSurface)
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Coming in the next update",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // Disabled button
        Button(
            onClick = {},
            enabled = false,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp) // py-3 feel
        ) {
            Icon(
                imageVector = Icons.Filled.Notifications,
                contentDescription = "Notify",
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Notify Me",
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp
            )
        }

        Text(
            text = "M2 — Nervos DAO integration",
            fontSize = 10.sp,
            letterSpacing = 2.sp, // tracking-widest-ish
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.60f),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 24.dp)
        )
    }
}

@Composable
private fun ExternalCaption() {
    Text(
        text = "Deposit, withdraw, and track DAO compensation — coming soon.",
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        lineHeight = 18.sp,
        modifier = Modifier.padding(horizontal = 32.dp) // px-8
    )
}


@Preview(showBackground = true)
@Composable
fun DaoScreenPreview() {
    CkbWalletTheme {
        DaoScreen()
    }
}

@Preview(showBackground = true)
@Composable
fun FeatureCardPreview() {
    CkbWalletTheme {
        FeatureCard()
    }
}
