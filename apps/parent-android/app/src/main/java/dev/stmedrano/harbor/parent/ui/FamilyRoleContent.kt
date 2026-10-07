package dev.stmedrano.harbor.parent.ui

import androidx.compose.runtime.Composable

@Composable
fun FamilyRoleContent(state: FamilyEntryState, entry: @Composable () -> Unit,
    parentAuth: @Composable () -> Unit, parentMenu: @Composable () -> Unit,
    parentContent: @Composable () -> Unit, childPairing: @Composable () -> Unit,
    childContent: @Composable () -> Unit) { }
