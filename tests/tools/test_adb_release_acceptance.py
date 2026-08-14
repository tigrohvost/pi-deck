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

    def test_semantic_click_is_case_insensitive(self) -> None:
        class FakeAdb:
            tapped = None

            def tap(self, node):
                self.tapped = node

        value = FakeAdb()
        xml = """<hierarchy>
          <node class="android.widget.Button" text="ALLOW ONCE" bounds="[10,20][30,40]" />
        </hierarchy>"""
        acceptance.click_named(value, xml, ("Allow once",))
        self.assertEqual("ALLOW ONCE", value.tapped["text"])

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

    def test_secure_keyguard_state_is_parsed_explicitly(self) -> None:
        self.assertTrue(acceptance.keyguard_showing("  showing=true\n  occluded=false\n"))
        self.assertFalse(acceptance.keyguard_showing("  showing=false\n"))
        self.assertFalse(acceptance.keyguard_showing("unrelated=true\n"))

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

    def test_tool_result_requires_a_real_uuid(self) -> None:
        xml = """<hierarchy>
          <node text="cat /proc/sys/kernel/random/uuid" />
          <node text="f47ac10b-58cc-4372-a567-0e02b2c3d479" />
        </hierarchy>"""
        self.assertEqual(
            "f47ac10b-58cc-4372-a567-0e02b2c3d479",
            acceptance.matching_text(xml, acceptance.UUID_PATTERN),
        )
        self.assertIsNone(
            acceptance.matching_text(
                "<hierarchy><node text='Linux' /></hierarchy>",
                acceptance.UUID_PATTERN,
            )
        )

    def test_terminal_answer_requires_ready_state(self) -> None:
        streaming = """<hierarchy>
          <node text="MODEL IS THINKING" />
          <node text="PIDECK_OK" />
        </hierarchy>"""
        terminal = """<hierarchy>
          <node text="READY · AGENT" />
          <node text="PIDECK_OK" />
        </hierarchy>"""
        self.assertFalse(acceptance.terminal_text_present(streaming, ("PIDECK_OK",)))
        self.assertTrue(acceptance.terminal_text_present(terminal, ("PIDECK_OK",)))

if __name__ == "__main__":
    unittest.main()
