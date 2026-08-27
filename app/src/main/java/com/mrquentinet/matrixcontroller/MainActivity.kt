package com.mrquentinet.matrixcontroller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mrquentinet.matrixcontroller.ui.common.LocalNetworkPermissionGate
import com.mrquentinet.matrixcontroller.ui.navigation.MatrixNavHost
import com.mrquentinet.matrixcontroller.ui.theme.MatrixControllerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MatrixControllerApplication).container
        setContent {
            MatrixControllerTheme {
                LocalNetworkPermissionGate {
                    MatrixNavHost(container)
                }
            }
        }
    }
}
