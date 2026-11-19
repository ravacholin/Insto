package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun SettingsCategory(
  title: String,
  expanded: Boolean,
  onClick: () -> Unit,
  content: @Composable ColumnScope.() -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column {
      Row(
        Modifier.fillMaxWidth()
          .clickable(role = Role.Button, onClick = onClick)
          .semantics { stateDescription = if (expanded) "Expandido" else "Contraído" }
          .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          title,
          modifier = Modifier.weight(1f).semantics { heading() },
          style = MaterialTheme.typography.titleMedium,
        )
        Icon(
          if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
      }
      if (expanded) {
        HorizontalDivider()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
      }
    }
  }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
    Text(
      title,
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.semantics { heading() }.padding(horizontal = 4.dp),
    )
    Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = MaterialTheme.shapes.medium,
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
      Column(
        Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
      )
    }
  }
}

@Composable
fun SettingsDescription(text: String, modifier: Modifier = Modifier) {
  Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun SettingsSwitch(
  title: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  description: String? = null,
  enabled: Boolean = true,
) {
  Row(
    Modifier.fillMaxWidth()
      .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
      .heightIn(min = 48.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      description?.let { SettingsDescription(it) }
    }
    Switch(checked = checked, onCheckedChange = null, enabled = enabled)
  }
}

@Composable
fun <T> SettingsChoices(
  options: List<T>,
  selected: T,
  onSelect: (T) -> Unit,
  label: (T) -> String,
  description: (T) -> String? = { null },
  itemModifier: (T) -> Modifier = { Modifier },
) {
  Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    options.forEach { option ->
      val isSelected = option == selected
      Surface(
        shape = MaterialTheme.shapes.small,
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
      ) {
        Row(
          Modifier.fillMaxWidth().then(itemModifier(option))
            .selectable(isSelected, role = Role.RadioButton, onClick = { onSelect(option) })
            .heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          RadioButton(selected = isSelected, onClick = null)
          Column(Modifier.weight(1f)) {
            Text(label(option), style = MaterialTheme.typography.bodyLarge)
            description(option)?.let { SettingsDescription(it) }
          }
        }
      }
    }
  }
}
