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
    /** Everyone who looks after this family, the parent reading it included. Empty until a phone reads it fresh. */
    val parents: List<FamilyParentV1> = emptyList(),
)

/** One adult of a family, as the other parents may see them: who they are, and whether they started the family. */
@Serializable data class FamilyParentV1(val userId: String, val role: String, val displayName: String?)

@Serializable data class ProfilePublic(val id: String, val displayName: String?, val createdAt: String, val updatedAt: String)
