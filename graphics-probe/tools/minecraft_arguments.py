"""Literal 1.18.2 game arguments for the runner's one-value-per-line protocol."""

import re

PLACEHOLDER = re.compile(r'\$\{([A-Za-z_][A-Za-z_0-9]*)\}')
FEATURES = frozenset({'is_demo_user', 'has_custom_resolution'})


def _literal(value: str) -> str:
    if not isinstance(value, str) or not value or value.startswith('#') or any(c in value for c in '\r\n\x00'):
        raise ValueError('value cannot be represented by runner argument lines')
    if '${' in value:
        raise ValueError('unbound placeholder')
    return value


def _expand(template: str, values: dict[str, str]) -> str:
    if not isinstance(template, str):
        raise ValueError('argument must be a string')
    def replace(match):
        key = match.group(1)
        if key not in values or not isinstance(values[key], str):
            raise ValueError(f'unknown placeholder: {key}')
        return values[key]
    result = PLACEHOLDER.sub(replace, template)
    if '$' in result:
        raise ValueError('unknown or nested placeholder')
    return result


def game_arguments(metadata: dict, values: dict[str, str], features: dict[str, bool]) -> list[str]:
    if set(features) - FEATURES or any(type(v) is not bool for v in features.values()):
        raise ValueError('unknown feature')
    entries = metadata['arguments']['game']
    if not isinstance(entries, list):
        raise ValueError('game arguments must be an array')
    expanded = []
    for entry in entries:
        if isinstance(entry, str):
            expanded.append(_expand(entry, values))
            continue
        if not isinstance(entry, dict) or set(entry) != {'rules', 'value'} or not isinstance(entry['rules'], list):
            raise ValueError('unknown argument rule')
        allowed = False
        for rule in entry['rules']:
            if not isinstance(rule, dict) or set(rule) != {'action', 'features'} or rule['action'] not in ('allow', 'disallow'):
                raise ValueError('unknown argument rule')
            conditions = rule['features']
            if not isinstance(conditions, dict) or set(conditions) - FEATURES or any(type(v) is not bool for v in conditions.values()):
                raise ValueError('unknown feature rule')
            if all(features.get(k, False) == v for k, v in conditions.items()):
                allowed = rule['action'] == 'allow'
        value = entry['value']
        if not isinstance(value, (str, list)) or (isinstance(value, list) and not all(isinstance(v, str) for v in value)):
            raise ValueError('invalid argument value')
        if allowed:
            expanded.extend(_expand(v, values) for v in ([value] if isinstance(value, str) else value))
    result = []
    for item in expanded:
        if item == '' and result and result[-1] in ('--clientId', '--xuid'):
            result[-1] += '='
        else:
            result.append(_literal(item))
    return result


def argument_lines(arguments: list[str]) -> str:
    return ''.join(_literal(item) + '\n' for item in arguments)
