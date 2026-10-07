package dev.stmedrano.harbor.parent.data

import dev.stmedrano.harbor.parent.family.*
import kotlinx.serialization.Serializable

@Serializable data class FamilySnapshot(
    val family: FamilyV1,
    val membership: FamilyMemberV1,
    val children: List<ChildV1>,
    val devices: List<DevicePublicV1>,
    val fetchedAt: Long,
    val profile: ProfilePublic? = null,
)

@Serializable data class ProfilePublic(val id: String, val displayName: String?, val createdAt: String, val updatedAt: String)
