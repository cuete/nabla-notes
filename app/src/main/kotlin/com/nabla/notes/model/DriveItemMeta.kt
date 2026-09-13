package com.nabla.notes.model

/** Minimal metadata for a OneDrive item resolved by path (see OneDriveRepository.resolveItemByPath). */
data class DriveItemMeta(
    val id: String,
    val name: String,
    val mimeType: String?,
    val downloadUrl: String?,
    val isFolder: Boolean
)
