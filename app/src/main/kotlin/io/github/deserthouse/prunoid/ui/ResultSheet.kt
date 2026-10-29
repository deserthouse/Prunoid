package io.github.deserthouse.prunoid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.deserthouse.prunoid.R
import io.github.deserthouse.prunoid.ui.operations.Operation
import io.github.deserthouse.prunoid.ui.operations.OperationOutcome

/** 批U2（C05）：操作结果全量明细底表——详情/管理共用（原两处各 take(3) 截断无展开路径，
 *  协议保留了 items 而 UI 未完整暴露）。逐项目标+失败原因可滚动查看。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpResultSheet(op: Operation, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            val (label, color) = when (op.outcome) {
                OperationOutcome.SUCCESS -> stringResource(R.string.result_success) to MaterialTheme.colorScheme.primary
                OperationOutcome.PARTIAL -> stringResource(R.string.result_partial) to MaterialTheme.colorScheme.secondary
                OperationOutcome.FAILED -> stringResource(R.string.result_failed) to MaterialTheme.colorScheme.error
                OperationOutcome.UNVERIFIED -> stringResource(R.string.result_unverified) to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                "$label · " + stringResource(R.string.result_counts, op.okCount, op.failCount),
                style = MaterialTheme.typography.titleMedium,
                color = color
            )
            Text(
                op.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(op.items, key = { it.target + it.detail + it.ok.toString() }) { item ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            if (item.ok) "✓" else "✗",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (item.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.width(20.dp)
                        )
                        Column {
                            Text(
                                item.target,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (item.detail.isNotBlank()) {
                                Text(
                                    item.detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (item.ok) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.error,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
