package app.naz.whispernote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LabelPicker(labels: List<String>, selected: Set<String>, onToggle: (String, Boolean) -> Unit,
                onClear: () -> Unit, onCreate: (String) -> Unit, dismiss: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest=dismiss,title={Text("Note labels")},text={
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Choose one or more labels, such as Lecture and Favorite.")
            if(selected.isEmpty()) Text("Unlabelled",style=MaterialTheme.typography.labelMedium,modifier=Modifier.padding(vertical=8.dp))
            labels.forEach { label ->
                Row(Modifier.fillMaxWidth().heightIn(min=48.dp)
                    .toggleable(value=label in selected,role=Role.Checkbox,onValueChange={onToggle(label,it)}),
                    verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(checked=label in selected,onCheckedChange=null)
                    Text(label,modifier=Modifier.weight(1f).padding(start=8.dp))
                }
            }
            TextButton(onClick=onClear,enabled=selected.isNotEmpty()) {Text("Remove all labels")}
            TextButton(onClick={creating=true}) { Icon(Icons.Outlined.Add,null); Text("Create label") }
        }
    },confirmButton={TextButton(onClick=dismiss) {Text("Done")}})
    if(creating) LabelNameDialog(null,{onCreate(it);creating=false},{creating=false})
}

@Composable
fun LabelNameDialog(existing: String?, save: (String) -> Unit, dismiss: () -> Unit) {
    var name by remember { mutableStateOf(existing.orEmpty()) }
    AlertDialog(onDismissRequest=dismiss,title={Text(if(existing==null) "Create label" else "Rename label")},text={
        OutlinedTextField(name,{if(it.length<=60) name=it},label={Text("Label name")},singleLine=true,supportingText={Text("For example: German · Season 1")})
    },confirmButton={TextButton(onClick={save(name.trim())},enabled=name.isNotBlank()) {Text(if(existing==null) "Create" else "Rename")}},dismissButton={TextButton(onClick=dismiss) {Text("Cancel")}})
}
