#!/usr/bin/env python3
"""Sync the pinned IconPack's previews, labels and iOS alternate-icon assets."""
from pathlib import Path
import json
import re
import shutil
import xml.etree.ElementTree as ET
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
COMMON_RES = ROOT / 'shared-ui/src/commonMain/composeResources'
RES = ROOT / 'shared-ui/src/iosMain/composeResources'
PACKAGE = ROOT / 'shared-ui/src/iosMain/kotlin/moe/rukamori/archivetune/shared/ui/settings'
icons = json.loads((ROOT / 'IconPack/metadata.json').read_text())
strings = ET.Element('resources')
existing_labels = {}
for source in (COMMON_RES, RES):
    for resource_file in sorted((source / 'values').glob('*.xml')):
        if resource_file.name == 'phone_launcher_catalog_strings.xml':
            continue
        for item in ET.parse(resource_file).getroot().findall('string'):
            existing_labels.setdefault(''.join(item.itertext()), item.attrib['name'])

def shared_label(value, fallback):
    name = existing_labels.get(value)
    if name is None:
        name = fallback
        ET.SubElement(strings, 'string', name=name).text = value
        existing_labels[value] = name
    return name

lines = ['package moe.rukamori.archivetune.shared.ui.settings', '',
         'import moe.rukamori.archivetune.shared.ui.resources.*', '',
         '/** The pinned IconPack catalog; regenerate with scripts/sync_launcher_icon_resources.py. */',
         'val SharedLauncherIconCatalog = listOf(',
         '    LauncherIcon("default", Res.string.m6_default, Res.drawable.phone_launcher_default, isDefault = true),']
assets = ROOT / 'iosApp/ArchiveTune/Assets.xcassets'
shutil.copyfile(assets / 'AppIcon.appiconset/AppIcon-1024.png', RES / 'drawable/phone_launcher_default.png')
for icon in icons:
    identifier = icon['Id']
    if not re.fullmatch(r'\d+', identifier):
        raise ValueError('Invalid icon identity')
    preview = ROOT / 'IconPack/previews' / (identifier + '.png')
    shutil.copyfile(preview, RES / 'drawable' / ('phone_launcher_' + identifier + '.png'))
    name_label = shared_label(icon['Name'], 'phone_launcher_name_' + identifier)
    author_label = shared_label(icon['Author'], 'phone_launcher_author_' + identifier)
    profile = json.dumps(icon.get('GitHubAuthorUrl', ''))
    lines.append(f'    LauncherIcon("{identifier}", Res.string.{name_label}, Res.drawable.phone_launcher_{identifier}, Res.string.{author_label}, {profile}),')
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
(COMMON_RES / 'files/notices/IconPack-CC-BY-SA-4.0.txt').write_text((ROOT / 'IconPack/README.md').read_text() + '\n\n' + (ROOT / 'IconPack/LICENSE').read_text())
print(f'Synced {len(icons)} pinned launcher icons and their attribution.')
