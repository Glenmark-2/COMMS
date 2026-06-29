package com.ridecompanion.features.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    viewModel: SessionViewModel,
    onNavigateToRideDashboard: (sessionId: String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    
    var riderName by remember { mutableStateOf("") }
    var sessionName by remember { mutableStateOf("") }
    var sessionIdToJoin by remember { mutableStateOf("") }
    
    var isCreateMode by remember { mutableStateOf(true) }

    LaunchedEffect(uiState) {
        if (uiState is SessionUiState.Success) {
            onNavigateToRideDashboard((uiState as SessionUiState.Success).sessionId)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0C0F1D), Color(0xFF07080F))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(300.dp)
                .align(Alignment.TopStart)
                .offset(x = (-50).dp, y = (-50).dp)
                .background(Color(0x0E00E5FF), shape = RoundedCornerShape(150.dp))
        )
        Box(
            modifier = Modifier
                .size(400.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 100.dp, y = 100.dp)
                .background(Color(0x0C3D5AFE), shape = RoundedCornerShape(200.dp))
        )

        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0x0F2E3047))
                .border(1.dp, Color(0x1Fffffff), RoundedCornerShape(24.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "RIDE COMPANION",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
                color = Color(0xFF00E5FF),
                letterSpacing = 2.sp
            )
            
            Text(
                text = "Secure intercom for cycling groups",
                fontSize = 13.sp,
                color = Color(0xFF8A90A6),
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
            )

            OutlinedTextField(
                value = riderName,
                onValueChange = { riderName = it },
                label = { Text("Your Name", color = Color(0xFF8A90A6)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF00E5FF),
                    unfocusedBorderColor = Color(0x3Dffffff),
                    focusedContainerColor = Color(0x08ffffff),
                    unfocusedContainerColor = Color(0x04ffffff)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (isCreateMode) {
                OutlinedTextField(
                    value = sessionName,
                    onValueChange = { sessionName = it },
                    label = { Text("Ride Name", color = Color(0xFF8A90A6)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00E5FF),
                        unfocusedBorderColor = Color(0x3Dffffff),
                        focusedContainerColor = Color(0x08ffffff),
                        unfocusedContainerColor = Color(0x04ffffff)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            } else {
                OutlinedTextField(
                    value = sessionIdToJoin,
                    onValueChange = { sessionIdToJoin = it },
                    label = { Text("Session ID", color = Color(0xFF8A90A6)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00E5FF),
                        unfocusedBorderColor = Color(0x3Dffffff),
                        focusedContainerColor = Color(0x08ffffff),
                        unfocusedContainerColor = Color(0x04ffffff)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = {
                    if (isCreateMode) {
                        viewModel.createSession(sessionName, riderName)
                    } else {
                        viewModel.joinSession(sessionIdToJoin, riderName)
                    }
                },
                enabled = riderName.isNotBlank() && (if (isCreateMode) sessionName.isNotBlank() else sessionIdToJoin.isNotBlank()) && uiState !is SessionUiState.Loading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E5FF),
                    disabledContainerColor = Color(0x1F00E5FF),
                    contentColor = Color(0xFF060913),
                    disabledContentColor = Color(0x3Dffffff)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                if (uiState is SessionUiState.Loading) {
                    CircularProgressIndicator(color = Color(0xFF060913), modifier = Modifier.size(24.dp))
                } else {
                    Text(
                        text = if (isCreateMode) "CREATE RIDE" else "JOIN RIDE",
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(
                onClick = { isCreateMode = !isCreateMode },
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF8A90A6))
            ) {
                Text(
                    text = if (isCreateMode) "Switch to Join Ride" else "Switch to Create Ride",
                    fontWeight = FontWeight.Medium
                )
            }

            AnimatedVisibility(
                visible = uiState is SessionUiState.Error,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                val errorMsg = (uiState as? SessionUiState.Error)?.message ?: ""
                Text(
                    text = errorMsg,
                    color = Color(0xFFFF5252),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}
