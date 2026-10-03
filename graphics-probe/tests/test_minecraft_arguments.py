import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from minecraft_arguments import game_arguments, argument_lines


class ArgumentTests(unittest.TestCase):
    def test_scalar_array_features_and_literal_spaces(self):
        metadata = {'arguments': {'game': ['--name', '${name}',
            {'rules': [{'action': 'allow', 'features': {'is_demo_user': True}}], 'value': '--demo'},
            {'rules': [{'action': 'allow', 'features': {'has_custom_resolution': True}}],
             'value': ['--width', '${width}']}]}}
        values = {'name': 'A User', 'width': '1280'}
        self.assertEqual(['--name', 'A User'], game_arguments(metadata, values,
                         {'is_demo_user': False, 'has_custom_resolution': False}))
        args = game_arguments(metadata, values,
                              {'is_demo_user': True, 'has_custom_resolution': True})
        self.assertEqual(['--name', 'A User', '--demo', '--width', '1280'], args)
        self.assertEqual(args, [s for s in argument_lines(args).splitlines() if s and not s.startswith('#')])

    def test_rejects_unknown_rules_placeholders_and_unbound_values(self):
        for item, values in [({'rules': [{'action': 'allow', 'os': {'name': 'linux'}}], 'value': '--x'}, {}),
                             ('${missing}', {}), ('${name}', {'name': '${recursive}'})]:
            with self.assertRaises(ValueError):
                game_arguments({'arguments': {'game': [item]}}, values, {})

    def test_rejects_unrepresentable_literal_values(self):
        for value in ['a\nb', 'a\x00b', '#comment', '']:
            with self.assertRaises(ValueError):
                game_arguments({'arguments': {'game': ['${name}']}}, {'name': value}, {})

    def test_blank_optional_identity_is_single_literal_argument(self):
        metadata = {'arguments': {'game': ['--clientId', '${clientid}', '--xuid', '${auth_xuid}']}}
        self.assertEqual(['--clientId=', '--xuid='], game_arguments(metadata,
                         {'clientid': '', 'auth_xuid': ''}, {}))


if __name__ == '__main__':
    unittest.main()
