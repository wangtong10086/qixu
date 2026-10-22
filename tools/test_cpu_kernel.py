import unittest
from prepare_cpu_kernel import merge, configuration, REQUIRED


class ConfigTests(unittest.TestCase):
    def test_preserves_unrelated_configuration(self):
        old = '# CONFIG_CFS_BANDWIDTH is not set\nCONFIG_FAIR_GROUP_SCHED=y\nCONFIG_VENDOR_TEST="original"\n'
        new = configuration(merge(old))
        self.assertEqual(new["CONFIG_VENDOR_TEST"], '"original"')
        self.assertEqual({key: new[key] for key in REQUIRED}, REQUIRED)
        self.assertEqual(merge(merge(old)), merge(old))

    def test_disabled_symbol_parser(self):
        self.assertEqual(configuration('# CONFIG_CFS_BANDWIDTH is not set\n'), {"CONFIG_CFS_BANDWIDTH": "n"})


if __name__ == '__main__':
    unittest.main()
