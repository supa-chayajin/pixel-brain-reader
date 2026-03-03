package cloud.wafflecommons.pixelbrainreader.ui.lifeos

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord

@Composable
fun RequestHealthPermissions(onResult: (Boolean) -> Unit) {
    val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(HydrationRecord::class),
        HealthPermission.getReadPermission(NutritionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class)
    )

    var showDialog by remember { mutableStateOf(true) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { grantedPermissions ->
        val allGranted = grantedPermissions.containsAll(permissions)
        showDialog = false
        onResult(allGranted)
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { /* Block dismiss so user has to make a choice */ },
            title = { Text("Health Data Access") },
            text = { Text("PixelBrainReader needs access to your Pixel Watch vitals (HRV, Sleep, Exercise), as well as your logged Hydration, and Weight. L'accès aux données de nutrition est nécessaire pour suivre automatiquement vos calories et valider vos objectifs de nutrition.") },
            confirmButton = {
                Button(onClick = { permissionLauncher.launch(permissions) }) {
                    Text("Grant Access")
                }
            }
        )
    }
}
