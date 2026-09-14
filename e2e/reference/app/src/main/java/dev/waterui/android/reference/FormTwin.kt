package dev.waterui.android.reference

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Twin of examples/form: three sections of labelled form controls plus live
// value previews, inside a padded scroll stack. Initial state only.
//
// Control translations mirror the labelled layouts the backend builds:
//   TextField        -> label over a filled MD3 TextField (TextInputLayout)
//   Toggle (switch)  -> label (weight 1) + trailing Switch (MaterialSwitch)
//   Stepper          -> label + value + connected filled −/+ pair
//   Slider (labelled)-> label over a full-width MD3 Slider (SeekBar)
//   Progress(0.5)    -> LinearProgressIndicator
//
// The example installs a theme body font of 17pt; MD3 bodyLarge is 16sp —
// left at the platform value since parity measures layout/styling, and the
// difference is recorded in the budget if it matters.

@Composable
private fun FormTextField(label: String, prompt: String) {
    Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
        BodyText(label)
        Spacer(Modifier.height(4.dp))
        TextField(
            value = "",
            onValueChange = {},
            placeholder = { Text(prompt) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FormToggle(label: String, isOn: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        BodyText(label, modifier = Modifier.weight(1f))
        Switch(checked = isOn, onCheckedChange = {})
    }
}

@Composable
private fun FormStepper(label: String, value: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        BodyText(label, modifier = Modifier.weight(1f))
        BodyText("$value")
        Button(onClick = {}, modifier = Modifier.padding(start = 8.dp)) { Text("−") }
        Button(onClick = {}, modifier = Modifier.padding(start = 4.dp)) { Text("+") }
    }
}

@Composable
private fun FormSlider(label: String, value: Float) {
    Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
        BodyText(label)
        Spacer(Modifier.height(4.dp))
        Slider(value = value, onValueChange = {}, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun FormTwin() {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(WATERUI_PADDING.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VStack {
            TitleText("WaterUI Form Examples")
            BodyText("Demonstrating form building with reactive data binding")
            HorizontalDivider()
            Spacer(Modifier.height(0.dp))

            // Registration form (#[form] derive output)
            VStack {
                SubheadlineText("Registration Form")
                BodyText("Using #[form] derive macro")
                VStack {
                    FormTextField("Full Name", "Full name of the user")
                    FormTextField("Email", "Email address for account")
                    FormStepper("Age", 0)
                    FormToggle("Newsletter", false)
                    FormSlider("Volume", 0f)
                }
                HorizontalDivider()
                BoldText("Live Preview:")
                BodyText("Name: ")
                BodyText("Email: ")
                BodyText("Age: 0")
                BodyText("Newsletter: false")
                BodyText("Volume: 0")
            }
            Spacer(Modifier.height(0.dp))

            // Settings form
            VStack {
                SubheadlineText("App Settings")
                BodyText("Another form with different field types")
                VStack {
                    FormSlider("Brightness", 0f)
                    FormToggle("Dark Mode", false)
                    FormSlider("Font Scale", 0f)
                    FormStepper("Auto Save Minutes", 0)
                    FormToggle("Notifications Enabled", false)
                }
                HorizontalDivider()
                BoldText("Current Settings:")
                HStack {
                    BodyText("Dark Mode: ")
                    BodyText("false")
                }
                HStack {
                    BodyText("Brightness: ")
                    BodyText("0.0000")
                }
            }
            Spacer(Modifier.height(0.dp))

            // Manual controls
            VStack {
                SubheadlineText("Manual Form Controls")
                BodyText("Building forms manually with individual controls")
                FormTextField("Username", "Enter your username")
                FormToggle("Enable Feature", false)
                FormStepper("Item Count", 5)
                FormSlider("Progress", 0.5f)
                LinearProgressIndicator(
                    progress = { 0.5f },
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
                BoldText("Manual Controls Preview:")
                BodyText("Username: ")
                BodyText("Feature Enabled: false")
                BodyText("Count: 5")
                BodyText("Progress: 0.5")
            }
            Spacer(Modifier.height(0.dp))
            HorizontalDivider()
            BodyText("Built with WaterUI Form Components")
        }
    }
}
