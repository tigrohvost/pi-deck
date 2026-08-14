from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]
TOOL_PATH = REPOSITORY / "tools" / "adb_release_acceptance.py"
SPEC = importlib.util.spec_from_file_location("adb_release_acceptance", TOOL_PATH)
assert SPEC is not None and SPEC.loader is not None
acceptance = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(acceptance)


class AdbReleaseAcceptanceTest(unittest.TestCase):
    XML = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
    <hierarchy rotation="0">
      <node class="android.widget.EditText" text="" bounds="[20,100][800,200]" />
      <node class="android.widget.TextView" text="ЯДРО" bounds="[300,2100][600,2200]" />
      <node class="android.widget.TextView" text="" content-desc="Отправить сообщение" bounds="[900,100][1000,200]" />
      <node class="android.widget.TextView" text="Готово отвечать · Агент" bounds="[20,20][600,80]" />
    </hierarchy>"""

    def test_ui_lookup_uses_semantics_and_validated_bounds(self) -> None:
        editor = acceptance.find_node(self.XML, class_suffix="EditText")
        self.assertIsNotNone(editor)
        send = acceptance.find_node(
            self.XML, descriptions=("Отправить сообщение", "Send message")
        )
        self.assertEqual("[900,100][1000,200]", send["bounds"])
        self.assertTrue(acceptance.text_present(self.XML, ("Готово отвечать",)))
        self.assertTrue(acceptance.text_present(self.XML, ("ГОТОВО ОТВЕЧАТЬ",)))
        self.assertEqual((20, 100, 800, 200), acceptance.parse_bounds("[20,100][800,200]"))
        self.assertIsNone(acceptance.parse_bounds("[20,100][20,200]"))

    def test_serial_and_installed_path_fail_closed(self) -> None:
        devices = "List of devices attached\nR5C123\tdevice product:test\n"
        self.assertEqual("R5C123", acceptance.resolve_serial(devices, None))
        with self.assertRaises(acceptance.AcceptanceError):
            acceptance.resolve_serial(devices + "OTHER\tdevice\n", None)
        self.assertEqual(
            "/data/app/example/base.apk",
            acceptance.remote_apk_path("package:/data/app/example/base.apk\n"),
        )
        with self.assertRaises(acceptance.AcceptanceError):
            acceptance.remote_apk_path("package:/system/app/example.apk\n")

    def test_report_helpers_do_not_return_raw_logs_or_ui(self) -> None:
        processes = acceptance.safe_process_facts(
            "PID NAME\n123 dev.pideck.app\n124 unrelated\n125 llama-server\n"
        )
        self.assertEqual(
            [{"pid": 123, "name": "dev.pideck.app"}, {"pid": 125, "name": "llama-server"}],
            processes,
        )
        self.assertEqual(
            {"totalPssKb": 1234, "totalRssKb": 5678},
            acceptance.memory_facts("TOTAL PSS: 1234 TOTAL RSS: 5678"),
        )


if __name__ == "__main__":
    unittest.main()
