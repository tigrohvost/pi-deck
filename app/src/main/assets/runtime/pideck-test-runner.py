"""Private structured result channel for run_tests; stdout is never a verdict."""
import inspect
import json
import os
from pathlib import Path
import runpy
import sys
import traceback

result = {"runner": "pytest", "version": None, "selected": None,
          "passed": 0, "failed": 0, "skipped": 0, "status": "infra_error"}


def fallback(target: Path, expression: str) -> int:
    result.update(runner="zero-fixture", version=sys.version.split()[0], status="unsupported")
    if expression:
        print("offline zero-fixture fallback does not implement pytest -k expressions")
        return 2
    files = []
    for path in ([target] if target.is_file() else target.rglob("test_*.py")):
        files.append(path)
        if len(files) > 200:
            print("offline fallback supports at most 200 files; suite was not checked")
            return 2
    root = Path.cwd().resolve()
    tests = []
    for file in sorted(files):
        if not file.resolve().is_relative_to(root):
            print("test file resolves outside workspace")
            return 2
        if any((parent / "conftest.py").exists()
               for parent in [file.parent, *file.parents]
               if parent == root or parent.is_relative_to(root)):
            print("conftest.py requires pytest; offline fallback is unsupported")
            return 2
        namespace = runpy.run_path(str(file))
        for name, value in sorted(namespace.items()):
            if (name.startswith("Test") and inspect.isclass(value)) or (
                    name == "pytestmark"):
                print("test classes and pytest marks require pytest")
                return 2
            if not name.startswith("test_") or not callable(value):
                continue
            if (not inspect.isfunction(value) or inspect.signature(value).parameters
                    or inspect.iscoroutinefunction(value) or inspect.isgeneratorfunction(value)
                    or inspect.isasyncgenfunction(value) or getattr(value, "pytestmark", None)
                    or hasattr(value, "__wrapped__")):
                print(f"{file.name}:{name}: unsupported offline test semantics")
                return 2
            tests.append((file, name, value))
    result["selected"] = len(tests)
    if not tests:
        result["status"] = "no_tests"
        print("no tests ran in offline zero-fixture fallback")
        return 5
    for file, name, function in tests:
        try:
            returned = function()
            if (inspect.isawaitable(returned) or inspect.isgenerator(returned)
                    or inspect.isasyncgen(returned)):
                if inspect.iscoroutine(returned) or inspect.isgenerator(returned):
                    returned.close()
                print(f"{file.name}:{name}: unsupported awaitable/generator result")
                return 2
        except Exception:
            result.update(status="failed", failed=result["failed"] + 1)
            traceback.print_exc()
            print(f"1 failed, {result['passed']} passed in offline zero-fixture fallback")
            return 1
        result["passed"] += 1
    result["status"] = "passed"
    print(f"{result['passed']} passed in offline zero-fixture fallback")
    return 0


def main() -> int:
    target = Path(sys.argv[1]).resolve()
    expression = sys.argv[2]
    root = Path.cwd().resolve()
    if not target.is_relative_to(root) or not target.exists():
        print("test target must exist inside the workspace")
        return 2
    sys.path.insert(0, str(root))
    try:
        import pytest
    except ModuleNotFoundError as error:
        if error.name != "pytest":
            raise
        return fallback(target, expression)
    result["version"] = pytest.__version__
    errors = []

    class ReceiptPlugin:
        def pytest_collection_finish(self, session):
            result["selected"] = len(session.items)

        def pytest_runtest_logreport(self, report):
            if report.skipped:
                result["skipped"] += 1
            elif report.failed:
                result["failed"] += 1
                if report.when != "call":
                    errors.append(report.when)
            elif report.when == "call" and report.passed:
                result["passed"] += 1

    arguments = ["-x", "-q", "--tb=short", "-p", "no:cacheprovider", str(target)]
    if expression:
        arguments.extend(["-k", expression])
    code = int(pytest.main(arguments, plugins=[ReceiptPlugin()]))
    if code == 0 and result["selected"] and result["passed"] and not result["failed"]:
        result["status"] = "passed"
    elif code == 5 or (code == 0 and not result["passed"]):
        result["status"] = "no_tests"
    elif code == 1 and result["failed"] and not errors:
        result["status"] = "failed"
    else:
        result["status"] = "infra_error"
    return code


exit_code = 2
try:
    exit_code = main()
except BaseException:
    result["status"] = "infra_error"
    traceback.print_exc()
finally:
    os.write(3, (json.dumps(result) + "\n").encode())
raise SystemExit(exit_code)
