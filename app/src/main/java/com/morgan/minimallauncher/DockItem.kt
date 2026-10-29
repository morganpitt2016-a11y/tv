package com.morgan.minimallauncher

sealed class DockItem {
    data class AppTile(val entry: AppEntry) : DockItem()
    data class DeviceTile(val device: GoveeHelper.Device) : DockItem()
    object MoreAppsTile : DockItem()
    object SettingsTile : DockItem()
}
