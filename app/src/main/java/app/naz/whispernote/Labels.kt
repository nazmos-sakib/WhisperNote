package app.naz.whispernote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LabelPicker(labels: List<String>, selected: String?, onSelect: (String?) -> Unit, onCreate: (String) -> Unit, dismiss: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest=dismiss,title={Text("Move to label")},text={
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Organize this note into a series or subject.")
            TextButton(onClick={onSelect(null);dismiss()}) { Text(if(selected==null) "✓ Unlabelled" else "Unlabelled") }
            labels.forEach { label -> TextButton(onClick={onSelect(label);dismiss()}) { Text(if(selected==label) "✓ $label" else label) } }
            TextButton(onClick={creating=true}) { Icon(Icons.Outlined.Add,null); Text("Create label") }
        }
    },confirmButton={TextButton(onClick=dismiss) {Text("Close")}})
    if(creating) LabelNameDialog(null,{onCreate(it);creating=false},{creating=false})
}

@Composable
fun LabelNameDialog(existing: String?, save: (String) -> Unit, dismiss: () -> Unit) {
    var name by remember { mutableStateOf(existing.orEmpty()) }
    AlertDialog(onDismissRequest=dismiss,title={Text(if(existing==null) "Create label" else "Rename label")},text={
        OutlinedTextField(name,{if(it.length<=60) name=it},label={Text("Label name")},singleLine=true,supportingText={Text("For example: German · Season 1")})
    },confirmButton={TextButton(onClick={save(name.trim())},enabled=name.isNotBlank()) {Text(if(existing==null) "Create" else "Rename")}},dismissButton={TextButton(onClick=dismiss) {Text("Cancel")}})
}
