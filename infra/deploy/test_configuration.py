import json
from pathlib import Path
import tempfile
import unittest

from configuration import allowed, compare, finish, inside, old_env, prepare, promote, read_plan, restore
from prepare_credentials import read_env, render_env


class ConfigurationDeploymentTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        folder = Path(self.directory.name)
        self.source, self.root = folder / "source", folder / "server"
        self.transaction = self.root / "backup/config-test"
        for directory in (self.source, self.root):
            self.put(directory, "compose.yaml", "services: {}\n")
            self.put(directory, "cdc/compose.yaml", "services: {}\n")
            self.put(directory, "nginx/conf.d/default.conf", "# old\n")
        self.main = {
            "name": "axcore", "networks": {"axcore-net": {}},
            "services": {name: {"image": "test:1"} for name in (
                "backend", "batch", "nginx", "postgres", "jenkins", "alloy", "grafana"
            )},
        }
        self.cdc = {"name": "axcore-cdc", "services": {"kafka": {"image": "test:1"}}}

    def put(self, root, name, content):
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def render(self, main=None, cdc=None):
        for project, old, new in (("main", self.main, main or self.main), ("cdc", self.cdc, cdc or self.cdc)):
            self.put(self.transaction, f"{project}-old.json", json.dumps(old))
            self.put(self.transaction, f"{project}-new.json", json.dumps(new))

    def test_whitelist_excludes_secrets_data_and_connector_registration(self):
        for path in (".env", "cdc/.env", "certbot/conf/key.pem", "monitoring/textfile/docker.prom",
                     "jenkins-data/config.xml", "cdc/connectors/cdc-erp.json", "source-db/compose.yaml"):
            self.assertFalse(allowed(path), path)
        self.assertTrue(allowed("monitoring/grafana/provisioning/alerting/alerts.yaml"))

    def test_nginx_changes_backup_apply_and_restore(self):
        self.put(self.source, "nginx/conf.d/default.conf", "# new\n")
        prepare(self.source, self.root, self.transaction)
        self.render()
        compare(self.transaction)
        self.assertTrue(read_plan(self.transaction)["nginx_reload"])
        promote(self.root, self.transaction)
        self.assertEqual((self.root / "nginx/conf.d/default.conf").read_text(), "# new\n")
        restore(self.root, self.transaction)
        self.assertEqual((self.root / "nginx/conf.d/default.conf").read_text(), "# old\n")

    def test_backend_config_change_also_recreates_batch(self):
        prepare(self.source, self.root, self.transaction)
        new = json.loads(json.dumps(self.main))
        new["services"]["backend"]["mem_limit"] = 123456
        self.render(main=new)
        compare(self.transaction)
        self.assertEqual(read_plan(self.transaction)["services"], ["main:backend", "main:batch"])

    def test_jenkins_and_database_changes_stop_before_promotion(self):
        prepare(self.source, self.root, self.transaction)
        for service in ("jenkins", "postgres"):
            new = json.loads(json.dumps(self.main))
            new["services"][service]["image"] = "test:2"
            self.render(main=new)
            with self.assertRaises(ValueError):
                compare(self.transaction)

    def test_volume_change_and_service_removal_require_manual_review(self):
        prepare(self.source, self.root, self.transaction)
        new = json.loads(json.dumps(self.main))
        new["volumes"] = {"different": {}}
        self.render(main=new)
        with self.assertRaises(ValueError):
            compare(self.transaction)
        new = json.loads(json.dumps(self.main))
        del new["services"]["batch"]
        self.render(main=new)
        with self.assertRaises(ValueError):
            compare(self.transaction)

    def test_new_dashboard_recreates_grafana_and_rollback_removes_new_file(self):
        name = "monitoring/grafana/dashboards/check.json"
        self.put(self.source, name, '{"panels": []}')
        prepare(self.source, self.root, self.transaction)
        self.render()
        compare(self.transaction)
        self.assertEqual(read_plan(self.transaction)["services"], ["main:grafana"])
        promote(self.root, self.transaction)
        self.assertTrue((self.root / name).is_file())
        restore(self.root, self.transaction)
        self.assertFalse((self.root / name).exists())

    def test_only_previously_managed_deletions_are_applied(self):
        name = "monitoring/grafana/dashboards/retired.json"
        self.put(self.root, name, "{}")
        self.put(self.root, ".managed-config.json", json.dumps([name]))
        self.put(self.root, "monitoring/grafana/dashboards/local.json", "{}")
        prepare(self.source, self.root, self.transaction)
        self.render()
        compare(self.transaction)
        promote(self.root, self.transaction)
        self.assertFalse((self.root / name).exists())
        self.assertTrue((self.root / "monitoring/grafana/dashboards/local.json").exists())
        restore(self.root, self.transaction)
        self.assertTrue((self.root / name).exists())

    def test_systemd_change_stops_before_files_are_written(self):
        self.put(self.source, "monitoring/docker-state/axcore-docker-state.timer", "new unit")
        with self.assertRaises(ValueError):
            prepare(self.source, self.root, self.transaction)
        self.assertFalse(self.transaction.exists())

    def test_traversal_and_symlinks_are_rejected(self):
        with self.assertRaises(ValueError):
            inside(self.root, "../outside")

    def test_manifest_is_recorded_only_on_finish(self):
        prepare(self.source, self.root, self.transaction)
        self.render()
        compare(self.transaction)
        promote(self.root, self.transaction)
        self.assertFalse((self.root / ".managed-config.json").exists())
        finish(self.root, self.transaction)
        self.assertIn("compose.yaml", json.loads((self.root / ".managed-config.json").read_text()))

    def test_setting_rollback_keeps_current_image_tags_and_secret(self):
        self.put(self.root, ".env", render_env({
            "BACKEND_TAG": "new1234", "POSTGRES_PASSWORD": "fake-only",
            "AI_ONTOLOGY_LLM_MODEL": "new-model",
        }))
        self.put(self.root, "cdc/.env", render_env({"ERP_CDC_PASSWORD": "fake-only"}))
        self.put(self.root, ".deployment-settings-before.json", json.dumps({
            "main": {"AI_ONTOLOGY_LLM_MODEL": "old-model"}, "cdc": {},
        }))
        prepare(self.source, self.root, self.transaction)
        old_env(self.root, self.transaction)
        old = read_env(self.transaction / "main-old.env")
        self.assertEqual(old["AI_ONTOLOGY_LLM_MODEL"], "old-model")
        self.assertEqual(old["BACKEND_TAG"], "new1234")
        restore(self.root, self.transaction)
        runtime = read_env(self.root / ".env")
        self.assertEqual(runtime["AI_ONTOLOGY_LLM_MODEL"], "old-model")
        self.assertEqual(runtime["BACKEND_TAG"], "new1234")
        self.assertEqual(runtime["POSTGRES_PASSWORD"], "fake-only")
        self.assertNotIn("POSTGRES_PASSWORD", read_env(self.root / ".env.config"))


if __name__ == "__main__":
    unittest.main()
