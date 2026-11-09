#!/usr/bin/env python3
"""Copy Android settings presentation metadata into portable Compose resources.

This is a bounded source/resource sync, not a Kotlin build or semantic index.
Bindings remain explicit shared SettingsRows; missing host actions are disabled.
"""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / 'app/src/main/kotlin/moe/rukamori/archivetune/ui/screens/settings'
COMMON = ROOT / 'shared-ui/src/commonMain/kotlin/moe/rukamori/archivetune/shared/ui/settings'
RES = ROOT / 'shared-ui/src/commonMain/composeResources'
PAGES = {
    'appearance': 'AppearanceSettings',
    'appearance/extras': 'AppearanceExtrasSettings',
    'appearance/navigation_bar': 'NavigationBarSettings',
    'appearance/lyrics_animations': 'LyricsAnimationSettings',
    'appearance/liquid_glass': 'LiquidGlassSettings',
    'appearance/aod_customized': 'AodCustomizedScreen',
    'player': 'PlayerSettings', 'lyrics': 'LyricsSettings',
    'lyrics/providers': 'LyricsProvidersSettings',
    'lyrics/romanisation': 'LyricsRomanisationSettings',
    'content': 'ContentSettings', 'privacy': 'PrivacySettings',
    'misc': 'DebugSettings', 'language_packs': 'LanguagePackSettings',
    'android_auto': 'AndroidAutoSettings', 'jiosaavn': 'JioSettings',
    'integration': 'IntegrationScreen', 'account': 'AccountSettings',
    'tidal': 'TidalSettings', 'qobuz': 'QobuzSettings', 'deezer': 'DeezerSettings',
    'amazon': 'AmazonSettings', 'applemusic': 'AppleMusicSettings',
    'qqmusic': 'QqMusicSettings', 'spotify_streaming': 'SpotifyStreamingSettings',
    'telegram': 'TelegramSettings', 'discord': 'DiscordSettings',
    'discord/experimental': 'DiscordExperimental', 'internet': 'InternetSettings',
    'downloads': 'DownloadsSettings', 'storage': 'StorageSettings',
    'canvas': 'CanvasSettings', 'ai_integration': 'AiIntegrationSettings',
    'lastfm': 'LastFMSettings', 'backup_restore': 'BackupAndRestore', 'update': 'UpdateScreen',
}
TITLE_KEYS = {
    'appearance': 'appearance', 'player': 'player_and_audio', 'privacy': 'settings_behavior_title',
    'misc': 'misc', 'android_auto': 'android_auto', 'content': 'content',
    'integration': 'integration', 'language_packs': 'language_packs',
}
# Android and portable hosts retain some different historical anchor spellings.
ALIASES = {
    'low_data_mode': ['network_metered'], 'enable_haptic_feedback': ['haptics'],
    'force_high_refresh_rate': ['force_high_refresh_rate'],
    'preferred_sources': ['preferred_sources'], 'source_settings': ['preferred_sources'],
    'acoustic_quality_title': ['acoustic_quality'], 'aod_customize_title': ['aod_customize'],
    'usb_exclusive_audio_title': ['usb_exclusive_audio'], 'bit_perfect_usb_title': ['bit_perfect_usb'],
    'auto_start_on_bluetooth': ['bluetooth_auto_start'], 'pause_on_device_mute': ['pause_mute'],
    'seek_seconds_addup': ['seek_seconds_addup'], 'swipe_song_to_add': ['swipe_to_song'],
    'auto_skip_next_on_error': ['auto_skip_error'], 'stop_music_on_task_clear': ['stop_task_clear'], 'audio_sources': ['preferred_sources'],
    'you_might_like_source': ['playlist_suggestion_source'],
    'top_length': ['top_size'], 'settings_behavior_title': ['privacy'],
}


def matching(text, start, opening='(', closing=')'):
    depth = 0
    quoted = False
    escaped = False
    for index in range(start, len(text)):
        char = text[index]
        if quoted:
            if escaped:
                escaped = False
            elif char == '\\':
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char == opening:
            depth += 1
        elif char == closing:
            depth -= 1
            if depth == 0:
                return index
    raise ValueError('Unbalanced source at ' + str(start))


def arguments(text):
    """Split only top-level named arguments; ignore nested composable lambdas."""
    parts = []
    start = 0
    stack = []
    quoted = escaped = False
    for index, char in enumerate(text):
        if quoted:
            if escaped:
                escaped = False
            elif char == '\\':
                escaped = True
            elif char == '"':
                quoted = False
            continue
        if char == '"':
            quoted = True
        elif char in '({[':
            stack.append(char)
        elif char in ')}]':
            if stack:
                stack.pop()
        elif char == ',' and not stack:
            parts.append(text[start:index]); start = index + 1
    parts.append(text[start:])
    result = {}
    for part in parts:
        found = re.match(r'\s*(\w+)\s*=\s*([\s\S]*)', part)
        if found:
            result[found[1]] = found[2]
    return result


def string_key(value):
    found = re.search(r'(?<!\w)R\.string\.(\w+)', value)
    return found[1] if found else None


def resource_values(paths):
    values = {}
    for path in sorted(paths):
        for item in ET.parse(path).getroot().findall('string'):
            values[item.attrib['name']] = item
    return values


def normal(value):
    return re.sub(r'[^\w]+', '', ''.join(value.itertext()).lower())


def main():
    android_strings = resource_values((ROOT / 'app/src/main/res/values').glob('*.xml'))
    persisted_keys = {}
    for path in (ROOT / 'shared-domain/src/commonMain').rglob('*.kt'):
        for match in re.finditer(r'val\s+(\w+)\s*=\s*\w+PreferenceKey\("([^"]+)"\)', path.read_text()):
            persisted_keys[match[1]] = match[2]
    shared_strings = resource_values((RES / 'values').glob('*.xml'))
    shared_by_text = {}
    for key, item in shared_strings.items():
        if not key.startswith('phone_reference_'):
            shared_by_text.setdefault(normal(item), key)
    new_strings = {}
    icons = set()

    def label(key):
        if key not in android_strings:
            return None
        item = android_strings[key]
        shared_key = shared_by_text.get(normal(item))
        if shared_key:
            return shared_key
        shared_key = 'phone_reference_' + key
        new_strings[shared_key] = item
        return shared_key

    page_sources = []
    count = 0
    pattern = r'\b(PreferenceGroup|preferenceGroup|SwitchPreference|ListPreference|EnumListPreference|PreferenceEntry|SliderPreference|IntPreference|NumberPickerPreference|CrossfadeSliderPreference)(?:<[^>\n]+>)?\s*\('
    for route, filename in PAGES.items():
        source = (ANDROID / (filename + '.kt')).read_text()
        source = re.sub(r'"(?:\\.|[^"\\])*"|/\*[\s\S]*?\*/|//[^\n]*',
                        lambda match: match[0] if match[0].startswith('"') else ' ' * len(match[0]), source)
        variables = {match[1]: match[2] for match in re.finditer(r'val\s+(\w+)\s*=\s*stringResource\(R\.string\.(\w+)\)', source)}
        bound_keys = {}
        for match in re.finditer(r'val\s*\(\s*(\w+)[^)]*\)\s*=\s*(?:prefs\.(?:state|enumState|conflictAwareState)|remember(?:Enum)?Preference)\(\s*(?:key\s*=\s*)?(\w+)', source):
            bound_keys[match[1]] = match[2]
        conditional_ranges = []
        for gate in re.finditer(r'\b(?:if|item)\s*\(', source):
            gate_start = source.index('(', gate.start())
            gate_end = matching(source, gate_start)
            expression = source[gate_start + 1:gate_end]
            body = gate_end + 1
            while body < len(source) and source[body].isspace():
                body += 1
            if body < len(source) and source[body] == '{' and 'Build.' not in expression and ('visible' in expression or source[gate.start():gate_start].strip() == 'if'):
                conditional_ranges.append((body, matching(source, body, '{', '}')))
        entries = []
        seen = set()
        for found in re.finditer(pattern, source):
            start = source.index('(', found.start())
            end = matching(source, start)
            args = arguments(source[start + 1:end])
            title = string_key(args.get('title', '')) or variables.get(args.get('title', '').strip())
            if title is None or label(title) is None:
                continue
            is_group = found[1] in ['PreferenceGroup', 'preferenceGroup']
            anchor_match = re.search(r'modifierFor\("([^"]+)"\)', args.get('modifier', ''))
            anchor = anchor_match[1] if anchor_match and not is_group else title
            if is_group:
                anchor = 'group:' + anchor
            identity = (is_group, anchor)
            if identity in seen:
                continue
            seen.add(identity)
            icon_match = re.search(r'R\.drawable\.(\w+)', args.get('icon', ''))
            icon = icon_match[1] if icon_match else None
            if icon and not (ROOT / 'app/src/main/res/drawable' / (icon + '.xml')).exists():
                icon = None
            if icon:
                icons.add(icon)
            # An Android-only host section missing from shared bindings remains a
            # disabled row of the same kind. Existing conditional bindings retain
            # their own availability and saved values.
            kind = 'HEADER' if is_group else 'TOGGLE' if found[1] == 'SwitchPreference' else 'VALUE' if 'ListPreference' in found[1] or 'Slider' in found[1] or 'IntPreference' in found[1] else 'ACTION'
            aliases = [title, *ALIASES.get(anchor, []), *ALIASES.get(title, [])]
            if is_group and route == 'appearance':
                aliases.extend({'liquid_glass': ['appearance_theme'], 'theme': ['appearance_theme'], 'player': ['appearance_player'], 'interface': ['appearance_interface']}.get(title, []))
            key = bound_keys.get(args.get('checked', args.get('selectedValue', '')).strip())
            key_name = persisted_keys.get(key)
            conditional = any(start < found.start() < end for start, end in conditional_ranges)
            entries.append((anchor, label(title), icon, kind, list(dict.fromkeys(aliases)), key_name, conditional))
        title_key = TITLE_KEYS.get(route)
        if title_key is None or title_key not in android_strings:
            top = re.search(r'(?:SettingsTopAppBar|TopAppBar)\([\s\S]{0,300}?R\.string\.(\w+)', source)
            title_key = top[1] if top else None
        title_label = label(title_key) if title_key else None
        title_label = title_label or label(route.rsplit('/', 1)[-1]) or (entries[0][1] if entries else 'm6_settings_home_group_settings')
        name = 'Phone' + filename + 'Reference'
        lines = ['package moe.rukamori.archivetune.shared.ui.settings', '',
                 'import moe.rukamori.archivetune.shared.ui.resources.*', '',
                 '/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */',
                 f'internal val {name} = PhoneSettingsReference(Res.string.{title_label}, listOf(']
        if route == 'android_auto':
            connection = next((index for index, entry in enumerate(entries) if entry[0] == 'group:android_auto_connection'), len(entries))
            entries = entries[connection:] + entries[:connection]
        for anchor, resource, icon, kind, aliases, key_name, conditional in entries:
            drawable = f'Res.drawable.phone_reference_{icon}' if icon else 'null'
            alias_text = ', '.join('"' + alias + '"' for alias in aliases)
            original = new_strings.get(resource)
            if original is None:
                original = shared_strings.get(resource)
            bindings = [key for key, item in shared_strings.items() if original is not None and normal(item) == normal(original) and not key.startswith('phone_reference_')]
            binding_text = ', '.join('Res.string.' + key for key in bindings)
            key_literal = '"' + key_name + '"' if key_name else 'null'
            lines.append(f'    PhoneSettingReference("{anchor}", Res.string.{resource}, {drawable}, PhoneSettingKind.{kind}, setOf({alias_text}), {key_literal}, setOf({binding_text}), {str(conditional).lower()}),')
        lines.append('))')
        (COMMON / (name + '.kt')).write_text('\n'.join(lines) + '\n')
        page_sources.append((route, name))
        count += len(entries)
    routes = ['package moe.rukamori.archivetune.shared.ui.settings', '',
              '/** Android screen references used only by the phone settings host. */',
              'fun phoneSettingsReference(route: String): PhoneSettingsReference? = when (route.substringBefore(\'?\')) {']
    routes.append('    "settings" -> PhoneSettingsHomeReference')
    for route, name in page_sources:
        routes.append(f'    "settings/{route}" -> {name}')
    routes += ['    "settings/appearance/theme", "settings/appearance/player", "settings/appearance/interface" -> PhoneAppearanceSettingsReference',
               '    else -> null', '}']
    (COMMON / 'PhoneSettingsReferences.kt').write_text('\n'.join(routes) + '\n')
    output = ET.Element('resources')
    ET.SubElement(output, 'string', name='phone_not_available_on_ios').text = 'Not available on iOS'
    for key, item in sorted(new_strings.items()):
        copy = ET.fromstring(ET.tostring(item, encoding='unicode'))
        copy.set('name', key)
        output.append(copy)
    ET.indent(output)
    ET.ElementTree(output).write(RES / 'values/phone_settings_reference_strings.xml', encoding='utf-8', xml_declaration=True)
    for icon in sorted(icons):
        source = ROOT / 'app/src/main/res/drawable' / (icon + '.xml')
        vector = ET.parse(source).getroot()
        namespace = '{http://schemas.android.com/apk/res/android}'
        for node in vector.iter():
            for attribute, value in list(node.attrib.items()):
                if value.startswith(('@', '?')):
                    if attribute.endswith(('fillColor', 'strokeColor')):
                        node.set(attribute, '#FFFFFFFF' if value.endswith('/white') else '#FF000000')
                    else:
                        del node.attrib[attribute]
                elif attribute.endswith(('fillColor', 'strokeColor')) and re.fullmatch(r'#[0-9a-fA-F]{3,4}', value):
                    node.set(attribute, '#' + ''.join(char * 2 for char in value[1:]))
            if node.tag == 'path' and namespace + 'fillColor' not in node.attrib:
                node.set(namespace + 'fillColor', '#00000000')
        ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')
        ET.indent(vector)
        ET.ElementTree(vector).write(RES / 'drawable' / ('phone_reference_' + icon + '.xml'), encoding='utf-8', xml_declaration=True)
    print(f'Synced {len(PAGES)} pages, {count} row/group references, {len(new_strings)} strings, {len(icons)} vectors.')


if __name__ == '__main__':
    main()
