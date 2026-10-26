package moe.rukamori.archivetune.shared.domain.settings

/** Custom Android Auto actions are stored by enum name; an unknown stored value reads as the slot default. */
enum class AndroidAutoCustomAction {
    LIKE,
    START_RADIO,
    SHUFFLE,
    REPEAT,
    NONE,
    ;

    companion object {
        fun fromPreference(value: String?, defaultValue: AndroidAutoCustomAction): AndroidAutoCustomAction =
            entries.firstOrNull { it.name == value } ?: defaultValue
    }
}

enum class AndroidAutoActionSlot { PRIMARY, SECONDARY }

fun PreferenceSnapshot.androidAutoPrimaryAction(): AndroidAutoCustomAction =
    AndroidAutoCustomAction.fromPreference(this[AndroidAutoPrimaryActionKey], AndroidAutoCustomAction.LIKE)

fun PreferenceSnapshot.androidAutoSecondaryAction(): AndroidAutoCustomAction =
    AndroidAutoCustomAction.fromPreference(this[AndroidAutoSecondaryActionKey], AndroidAutoCustomAction.START_RADIO)

/**
 * The baseline slot rule. A primary action is never NONE, and the two slots never hold the same action: choosing an
 * action held by the other slot swaps the two, and a slot that would otherwise repeat is replaced by a fallback.
 */
fun androidAutoSlotAssignment(
    slot: AndroidAutoActionSlot,
    action: AndroidAutoCustomAction,
    currentPrimary: AndroidAutoCustomAction,
    currentSecondary: AndroidAutoCustomAction,
): Pair<AndroidAutoCustomAction, AndroidAutoCustomAction> {
    require(slot == AndroidAutoActionSlot.SECONDARY || action != AndroidAutoCustomAction.NONE)
    val primary = when {
        slot == AndroidAutoActionSlot.PRIMARY -> action
        action != currentPrimary -> currentPrimary
        currentSecondary != AndroidAutoCustomAction.NONE -> currentSecondary
        action != AndroidAutoCustomAction.START_RADIO -> AndroidAutoCustomAction.START_RADIO
        else -> AndroidAutoCustomAction.LIKE
    }
    val secondary = when {
        slot == AndroidAutoActionSlot.SECONDARY -> action
        action == currentSecondary -> currentPrimary
        else -> currentSecondary
    }
    require(primary != AndroidAutoCustomAction.NONE)
    return primary to secondary
}

/** Both slots commit in one edit, so a swap never leaves the two stored slots disagreeing. */
fun PreferenceEditor.setAndroidAutoAction(slot: AndroidAutoActionSlot, action: AndroidAutoCustomAction) {
    val current = snapshot()
    val (primary, secondary) = androidAutoSlotAssignment(
        slot = slot,
        action = action,
        currentPrimary = current.androidAutoPrimaryAction(),
        currentSecondary = current.androidAutoSecondaryAction(),
    )
    this[AndroidAutoPrimaryActionKey] = primary.name
    this[AndroidAutoSecondaryActionKey] = secondary.name
}
