"""Fail when an APK built with -Pnatune.discordSdk=true lacks the Discord presence libraries."""

import sys
import zipfile


def main(apk: str) -> int:
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
    abis = sorted({name.split("/")[1] for name in names if name.startswith("lib/") and name.count("/") == 2})
    missing = [
        f"lib/{abi}/{library}"
        for abi in abis
        for library in ("libdiscord_partner_sdk.so", "libnatune_discord_social.so")
        if f"lib/{abi}/{library}" not in names
    ]
    if not abis or missing:
        print(f"::error::{apk} is missing Discord Rich Presence libraries: {missing or 'no native ABIs'}")
        return 1
    print(f"{apk}: Discord Social SDK present for {', '.join(abis)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
