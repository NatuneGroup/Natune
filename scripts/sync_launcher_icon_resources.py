#!/usr/bin/env python3
"""Sync the pinned IconPack's previews, labels and iOS alternate-icon assets."""
from pathlib import Path
import json
import re
import shutil
import xml.etree.ElementTree as ET
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'shared-ui/src/commonMain/composeResources'
PACKAGE = ROOT / 'shared-ui/src/commonMain/kotlin/moe/rukamori/archivetune/shared/ui/settings'
icons = json.loads((ROOT / 'IconPack/metadata.json').read_text())
strings = ET.Element('resources')
lines = ['package moe.rukamori.archivetune.shared.ui.settings', '',
         'import moe.rukamori.archivetune.shared.ui.resources.*', '',
         '/** The pinned IconPack catalog; regenerate with scripts/sync_launcher_icon_resources.py. */',
         'val SharedLauncherIconCatalog = listOf(',
         '    LauncherIcon("default", Res.string.ios_icon_default, Res.drawable.phone_launcher_default, isDefault = true),']
assets = ROOT / 'iosApp/ArchiveTune/Assets.xcassets'
shutil.copyfile(assets / 'AppIcon.appiconset/AppIcon-1024.png', RES / 'drawable/phone_launcher_default.png')
for icon in icons:
    identifier = icon['Id']
    if not re.fullmatch(r'\d+', identifier):
        raise ValueError('Invalid icon identity')
    preview = ROOT / 'IconPack/previews' / (identifier + '.png')
    shutil.copyfile(preview, RES / 'drawable' / ('phone_launcher_' + identifier + '.png'))
    ET.SubElement(strings, 'string', name='phone_launcher_name_' + identifier).text = icon['Name']
    ET.SubElement(strings, 'string', name='phone_launcher_author_' + identifier).text = icon['Author']
    profile = json.dumps(icon.get('GitHubAuthorUrl', ''))
    lines.append(f'    LauncherIcon("{identifier}", Res.string.phone_launcher_name_{identifier}, Res.drawable.phone_launcher_{identifier}, Res.string.phone_launcher_author_{identifier}, {profile}),')
    destination = assets / ('NatuneIcon_' + identifier + '.appiconset')
    destination.mkdir(exist_ok=True)
    with Image.open(preview) as image:
        resized = image.convert('RGBA').resize((1024, 1024), Image.Resampling.LANCZOS)
        opaque = Image.new('RGB', resized.size, resized.getpixel((512, 512))[:3])
        opaque.paste(resized, mask=resized.getchannel('A'))
        opaque.save(destination / 'Icon.png')
    (destination / 'Contents.json').write_text(json.dumps({
        'images': [{'filename': 'Icon.png', 'idiom': 'universal', 'platform': 'ios', 'size': '1024x1024'}],
        'info': {'author': 'xcode', 'version': 1},
    }, indent=2) + '\n')
lines.append(')')
(PACKAGE / 'SharedLauncherIconCatalog.kt').write_text('\n'.join(lines) + '\n')
ET.indent(strings)
ET.ElementTree(strings).write(RES / 'values/phone_launcher_catalog_strings.xml', encoding='utf-8', xml_declaration=True)
shutil.copyfile(ROOT / 'IconPack/LICENSE', RES / 'files/notices/IconPack-CC-BY-SA-4.0.txt')
print(f'Synced {len(icons)} pinned launcher icons and their attribution.')
