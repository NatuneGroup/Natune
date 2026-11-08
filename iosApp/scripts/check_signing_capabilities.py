"""Check requested capability fields in decoded provisioning profiles; Apple verifies their signatures."""
import argparse
import plistlib
from pathlib import Path

APP_BUNDLE = "moe.rukamori.archivetune"
WIDGET_BUNDLE = APP_BUNDLE + ".controls"


def validate_profiles(app, widget, group, team=None):
    if not isinstance(group, str) or not group.startswith("group.") or "$" in group:
        raise ValueError("Set one registered App Group for both targets.")
    teams = []
    for label, profile, bundle in [("App", app, APP_BUNDLE), ("Widget", widget, WIDGET_BUNDLE)]:
        fields = profile.get("Entitlements", {}) if isinstance(profile, dict) else {}
        if not isinstance(fields, dict):
            raise ValueError(label + " profile has no capability fields.")
        identifier = fields.get("application-identifier", "")
        if not isinstance(identifier, str) or not identifier.endswith("." + bundle):
            raise ValueError(label + " profile must match its explicit Natune bundle identifier.")
        granted_team = fields.get("com.apple.developer.team-identifier")
        if not isinstance(granted_team, str) or not granted_team or (team and granted_team != team):
            raise ValueError(label + " profile must match the configured development team.")
        teams.append(granted_team)
        groups = fields.get("com.apple.security.application-groups", [])
        if not isinstance(groups, list) or group not in groups:
            raise ValueError(label + " profile needs the shared App Group entitlement.")
    if teams[0] != teams[1]:
        raise ValueError("App and widget profiles must use the same development team.")
    if app["Entitlements"].get("com.apple.developer.carplay-audio") is not True:
        raise ValueError("App profile needs Apple's CarPlay audio entitlement.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app-profile", type=Path, required=True, help="Decoded app .mobileprovision plist")
    parser.add_argument("--widget-profile", type=Path, required=True, help="Decoded widget .mobileprovision plist")
    parser.add_argument("--app-group", default="group.moe.rukamori.archivetune")
    parser.add_argument("--team")
    args = parser.parse_args()
    try:
        app = plistlib.loads(args.app_profile.read_bytes())
        widget = plistlib.loads(args.widget_profile.read_bytes())
        validate_profiles(app, widget, args.app_group, args.team)
    except ValueError as error:
        parser.exit(1, str(error) + "\n")
    except (OSError, plistlib.InvalidFileException, TypeError):
        parser.exit(1, "Cannot read decoded provisioning profiles.\n")
    print("Capability fields match the app and widget targets; signing and device validation are still required.")


if __name__ == "__main__":
    main()
