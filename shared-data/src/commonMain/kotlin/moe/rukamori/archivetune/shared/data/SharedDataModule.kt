package moe.rukamori.archivetune.shared.data

import moe.rukamori.archivetune.shared.domain.SharedDomainModule

/**
 * Names the shared data module. It depends on the domain module, so repositories use domain
 * types instead of re-declaring them.
 */
object SharedDataModule {
    const val NAME: String = "shared-data"
    const val DOMAIN_MODULE: String = SharedDomainModule.NAME
}
