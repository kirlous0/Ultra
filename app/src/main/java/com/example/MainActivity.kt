package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.camera.AppDestination
import com.example.system.AppLanguage
import com.example.system.LocaleStrings
import com.example.ui.MainViewModel
import com.example.ui.screens.CameraScreen
import com.example.ui.screens.DiagnosticsScreen
import com.example.ui.screens.GalleryScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StudioScreen
import com.example.ui.theme.UltraCamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            val vm: MainViewModel = viewModel()
            val settings by vm.settings.collectAsStateWithLifecycle()
            val currentDestination by vm.currentDestination.collectAsStateWithLifecycle()
            val isArabic = settings.language == AppLanguage.ARABIC
            val strings = remember(settings.language) { LocaleStrings.forLanguage(settings.language) }
            val layoutDirection = if (isArabic) LayoutDirection.Rtl else LayoutDirection.Ltr

            val context = LocalContext.current
            var hasCameraPerm by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                )
            }

            val cameraPermLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                hasCameraPerm = granted
                if (granted) vm.onAppResumed(true)
            }

            LaunchedEffect(Unit) {
                if (!hasCameraPerm) {
                    cameraPermLauncher.launch(Manifest.permission.CAMERA)
                }
            }

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, hasCameraPerm) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> vm.onAppResumed(hasCameraPerm)
                        Lifecycle.Event.ON_PAUSE -> vm.onAppPaused()
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                UltraCamTheme(isArabic = isArabic) {
                    when (currentDestination) {
                        AppDestination.GALLERY -> GalleryScreen(viewModel = vm, strings = strings)
                        AppDestination.STUDIO -> StudioScreen(viewModel = vm, strings = strings)
                        AppDestination.DIAGNOSTICS -> DiagnosticsScreen(viewModel = vm, strings = strings)
                        AppDestination.SETTINGS -> SettingsScreen(viewModel = vm, strings = strings)
                        else -> CameraScreen(
                            viewModel = vm,
                            strings = strings,
                            hasCameraPermission = hasCameraPerm,
                            onRequestCameraPermission = {
                                cameraPermLauncher.launch(Manifest.permission.CAMERA)
                            }
                        )
                    }
                }
            }
        }
    }
}
