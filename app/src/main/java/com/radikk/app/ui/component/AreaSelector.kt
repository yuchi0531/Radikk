package com.radikk.app.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.radikk.app.data.api.RadikoApi

/**
 * エリア選択ドロップダウン。
 * 変更時は再認証が必要 (トークンはエリアに紐づく)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AreaSelector(
    selectedAreaId: String,
    onAreaSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = RadikoApi.AREA_NAMES[selectedAreaId] ?: selectedAreaId,
            onValueChange = {},
            readOnly = true,
            label = { Text("エリア") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            RadikoApi.AREA_IDS.forEach { areaId ->
                DropdownMenuItem(
                    text = {
                        Text(RadikoApi.AREA_NAMES[areaId] ?: areaId)
                    },
                    onClick = {
                        expanded = false
                        onAreaSelected(areaId)
                    },
                )
            }
        }
    }
}
