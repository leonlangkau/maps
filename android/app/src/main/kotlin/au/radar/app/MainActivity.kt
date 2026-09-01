package au.radar.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import au.radar.app.ui.DriveScreen

class MainActivity : ComponentActivity() {

    private val viewModel: DriveViewModel by viewModels()
    private var hasLocation by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        hasLocation = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (hasLocation) beginDriving()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A driving app that dims and locks mid-trip is useless in a cradle.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        hasLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        setContent {
            // Dark only: this is a screen used at night in a car.
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    DriveScreen(viewModel = viewModel, hasLocationPermission = hasLocation)
                }
            }
        }

        if (hasLocation) {
            beginDriving()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    private fun beginDriving() {
        DriveService.start(this)
        viewModel.start()
    }

    override fun onDestroy() {
        // Only stop the service when the activity is really going away, not on a
        // rotation: warnings should survive the screen turning off.
        if (isFinishing) {
            viewModel.stop()
            DriveService.stop(this)
        }
        super.onDestroy()
    }
}
