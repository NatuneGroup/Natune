package moe.rukamori.archivetune.shared.data.stream

import moe.rukamori.archivetune.shared.data.bytesource.RandomAccessByteSource
import moe.rukamori.archivetune.shared.domain.platform.AccountAdmission
import moe.rukamori.archivetune.shared.domain.platform.AcceptedAccountReader

/**
 * Opens ciphertext only while the account admitted at prepare time is still the accepted account. The check lives in
 * portable code, ahead of the host transport, so a host opener cannot skip it. A different account scope, a signed-out
 * account or a replaced account returns null, and the caller then falls through its source chain. Anonymous sources
 * have no account to re-check, so they keep the host opener unwrapped.
 */
class AdmittedCiphertextOpener(
    private val admission: AccountAdmission,
    private val accounts: AcceptedAccountReader,
    private val delegate: NativeCiphertextOpener,
) : NativeCiphertextOpener {
    override suspend fun open(
        url: String,
        accountScope: String,
    ): RandomAccessByteSource? {
        if (accountScope != admission.accountScope) return null
        val current = accounts.acceptedAccount(admission.provider, admission.accountScope) ?: return null
        if (current != admission.accepted) return null
        return delegate.open(url, accountScope)
    }
}
