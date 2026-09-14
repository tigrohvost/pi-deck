import json
import os
from pathlib import Path
import secrets
import statistics
import subprocess
import sys
import time
import urllib.request

sys.path.insert(0, '/home/che/dev/pi-deck-android-alpha13/tools')
import speculative_probe as probe

ROOT = Path('/tmp/pideck-model-audit-20260907')
REMOTE = '/data/local/tmp/pideck-audit-20260907'
probe.DEVICE_ROOT = REMOTE + '/native'
MODELS = {
    'granite': ('/data/local/tmp/pideck-b10092-affinity-repro', 'libpideck_llama_server.so', '/sdcard/Download/PiDeck/incoming/granite-4.2-3b-20e436143017.gguf', '20e436143017578687f7f848225cc6c6038126c84149192229c7dff6e4e0f427', 1.0),
    'qwen2': ('/data/local/tmp/pideck-b10092-affinity-repro', 'libpideck_llama_server.so', '/sdcard/Download/PiDeck/incoming/qwen3.8-2b-distill-4aa0fb13c431.gguf', '4aa0fb13c431514262f259d420ecc95a8714df58ac2a2384514e20b93983f0ff', 0.6),
    'qad': ( '/data/local/tmp/pideck-b10092-affinity-repro', 'libpideck_llama_server.so', '/data/local/tmp/pideck-perf-models/LFM2.5-2.6B-QAD-Q4_0.gguf', 'a247afd6414918eac8e520a9e6137dc271235461ecbe1180462221d5b8d40b03', 0.1),
    'qwen4': ('/data/local/tmp/pideck-b10092-affinity-repro', 'libpideck_llama_server.so', REMOTE + '/Qwen3.8-4B-Q4_K_M.gguf', 'dec96e8cf2e11b613bb46513dec485377f9ca5a351e71712ee0e244f287c6790', 0.6),
    'k2': (REMOTE, 'k2-server', REMOTE + '/K2-Horizon-3.7B-Q4_K_M.gguf', 'f8dce940ec3e45be3e884a203b0eb3dee4ba57eb23fc63d0e210cdade2ca48ec', 1.0),
}
name = sys.argv[1]
threads = int(os.environ.get('AUDIT_THREADS', '5'))
batch_threads = int(os.environ.get('AUDIT_BATCH_THREADS', '8'))
batch_cpus = os.environ.get('AUDIT_BATCH_CPUS', '0-7')
context = int(os.environ.get('AUDIT_CONTEXT', '8192'))
minimum_headroom = float(os.environ.get('AUDIT_MIN_HEADROOM', '0.95'))
extra = os.environ.get('AUDIT_EXTRA', '').split()
mode = sys.argv[2] if len(sys.argv) > 2 else 'both'
library, executable, model, expected, temperature = MODELS[name]
executable = os.environ.get('AUDIT_EXECUTABLE', executable)
output = ROOT / os.environ.get('AUDIT_OUTPUT', f'{name}-native-{mode}.json')
report = {'model': name, 'device': 'SM-S918B', 'modelPath': model, 'modelSha256': expected, 'libraryDirectory': library, 'context': context, 'threads': threads, 'batchThreads': batch_threads, 'batchCpus': batch_cpus, 'extraFlags': extra, 'toolPromptCache': 'false on first request of each case, true only for its exact growing prefix', 'batchSize': 512, 'ubatchSize': 128, 'temperature': temperature, 'minimumHeadroom': minimum_headroom, 'reasoningBudget': 256, 'samples': [], 'tools': []}

def save():
    temp = output.with_suffix('.tmp')
    temp.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    temp.replace(output)

def cooldown():
    start = time.monotonic()
    while True:
        state = probe.thermal_state()
        if state['headroom'] >= minimum_headroom and (state['hottestCpuMilliCelsius'] or 0) <= 65000:
            return state
        if time.monotonic() - start > 600:
            raise RuntimeError('Thermal gate not reached within 600 seconds')
        time.sleep(8)

def request(messages, maximum=512, tools=None, ignore_eos=False, reuse_cache=False):
    payload = {'messages': messages, 'max_tokens': maximum, 'temperature': temperature, 'top_p': 0.95, 'top_k': 50 if name == 'qad' else 20, 'seed': 42, 'stream': False, 'cache_prompt': reuse_cache, 'ignore_eos': ignore_eos, 'chat_template_kwargs': {'reasoning_effort': 'low'}}
    if tools:
        payload.update(tools=tools, tool_choice='auto', parallel_tool_calls=False)
    req = urllib.request.Request('http://127.0.0.1:18080/v1/chat/completions', data=json.dumps(payload).encode(), headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + key})
    started = time.monotonic()
    with urllib.request.urlopen(req, timeout=360) as response:
        value = json.loads(response.read(2 * 1024**2))
    elapsed = time.monotonic() - started
    return value, elapsed

def tool_schema(tool_name, description, properties, required):
    return {'type': 'function', 'function': {'name': tool_name, 'description': description, 'parameters': {'type': 'object', 'properties': properties, 'required': required, 'additionalProperties': False}}}

TOOLS = [
    tool_schema('read', 'Read a UTF-8 file from the test workspace.', {'path': {'type': 'string'}}, ['path']),
    tool_schema('edit', 'Replace one exact occurrence of old_text in a file with new_text.', {'path': {'type': 'string'}, 'old_text': {'type': 'string'}, 'new_text': {'type': 'string'}}, ['path', 'old_text', 'new_text']),
    tool_schema('run_tests', 'Run the named Python test and report its real result.', {'path': {'type': 'string'}}, ['path']),
    tool_schema('weather', 'Get current weather by city name.', {'city': {'type': 'string'}}, ['city']),
]

def tool_case(label, prompt):
    if label not in os.environ.get('AUDIT_CASES', 'missing,repair,weather').split(','):
        return
    source = 'def clamp(value, lower, upper):\n    return min(lower, max(upper, value))\n'
    messages = [{'role': 'system', 'content': 'Ты аккуратный помощник. Выполняй запрос пользователя с помощью доступных инструментов. Отвечай по-русски кратко. Используй результат инструмента; не выдумывай выполненные действия.'}, {'role': 'user', 'content': prompt}]
    calls = []
    rounds = []
    test_passed = False
    final = ''
    cooldown_started = time.monotonic()
    before = cooldown()
    cooldown_seconds = time.monotonic() - cooldown_started
    started = time.monotonic()
    for _ in range(7):
        response, elapsed = request(messages, 640, TOOLS, reuse_cache=bool(rounds))
        choice = response['choices'][0]
        message = choice['message']
        current_calls = message.get('tool_calls') or []
        rounds.append({'elapsed': elapsed, 'timings': response.get('timings'), 'finishReason': choice.get('finish_reason'), 'content': message.get('content'), 'reasoningChars': len(message.get('reasoning_content') or ''), 'toolCalls': current_calls})
        messages.append({k: v for k, v in message.items() if k in ['role', 'content', 'tool_calls', 'reasoning_content']})
        if not current_calls:
            final = message.get('content') or ''
            break
        for call in current_calls:
            function = call['function']
            tool = function['name']
            try:
                args = json.loads(function['arguments']) if isinstance(function['arguments'], str) else function['arguments']
                if tool == 'read':
                    result = source if args['path'] == 'src/math_utils.py' else 'Error: file not found'
                elif tool == 'edit':
                    old, new = args['old_text'], args['new_text']
                    if args['path'] != 'src/math_utils.py' or not old or source.count(old) != 1:
                        result = 'Error: exact target was not found'
                    elif any(token in new for token in ['import', '__', 'open(', 'exec(', 'eval(']):
                        result = 'Error: edit outside the permitted arithmetic fixture'
                    else:
                        source = source.replace(old, new)
                        result = 'Edit saved'
                elif tool == 'run_tests':
                    if args['path'] != 'tests/test_math_utils.py':
                        result = 'Error: unknown test'
                    else:
                        scope = {}
                        exec(compile(source, '<fixture>', 'exec'), {'__builtins__': {'min': min, 'max': max}}, scope)
                        clamp = scope['clamp']
                        cases = [(5, 0, 10, 5), (-1, 0, 10, 0), (15, 0, 10, 10), (3, 3, 3, 3)]
                        test_passed = all(clamp(a, b, c) == expected for a, b, c, expected in cases)
                        result = '4 tests passed' if test_passed else 'Tests FAILED: clamp gives incorrect results'
                elif tool == 'weather':
                    result = json.dumps({'city': args['city'], 'temperature_c': 13, 'condition': 'дождь', 'test_fixture': True}, ensure_ascii=False)
                else:
                    result = 'Error: unknown tool'
            except Exception as error:
                result = 'Error: ' + type(error).__name__
            calls.append({'name': tool, 'args': args if 'args' in locals() else None, 'result': result})
            messages.append({'role': 'tool', 'tool_call_id': call['id'], 'content': result})
        report['inProgress'] = {'label': label, 'rounds': rounds, 'calls': calls}
        save()
        print('Round', label, len(rounds), [c['name'] for c in calls], flush=True)
        if len(calls) > (6 if label == 'repair' else 1):
            break
    names = [c['name'] for c in calls]
    passed = bool(final) and ((label == 'missing' and names == ['read'] and any(s in final.lower() for s in ['не найден', 'отсутств', 'не существует'])) or (label == 'repair' and test_passed and 'edit' in names and 'run_tests' in names and len(names) <= 6) or (label == 'weather' and names == ['weather'] and '13' in final))
    case = {'label': label, 'passed': passed, 'seconds': time.monotonic() - started, 'cooldownSeconds': cooldown_seconds, 'inferenceSeconds': sum(r['elapsed'] for r in rounds), 'thermalBefore': before, 'thermalAfter': probe.thermal_state(), 'calls': calls, 'rounds': rounds, 'answer': final, 'testPassed': test_passed}
    report['tools'].append(case)
    save()
    print(label, 'PASS' if passed else 'FAIL', names, round(case['seconds'], 2), flush=True)

probe.adb('shell', 'mkdir', '-p', probe.DEVICE_ROOT)
actual = probe.adb('shell', 'sha256sum', model, timeout=240).split()[0]
if actual != expected:
    raise RuntimeError('Model digest mismatch')
report['serverSha256'] = probe.adb('shell', 'sha256sum', library + '/' + executable).split()[0]
key = secrets.token_hex(24)
subprocess.run(['adb', 'shell', 'umask 077; cat > ' + probe.DEVICE_ROOT + '/key'], input=key.encode(), check=True)
probe.adb('forward', 'tcp:18080', 'tcp:18080')
handle = None
try:
    print('Cooling before load', name, flush=True)
    report['thermalBeforeLoad'] = cooldown()
    start = time.monotonic()
    handle = probe.start_server(library, executable, model, context, threads, batch_threads, '3-7', batch_cpus, ['-ngl', '0', '-b', '512', '-ub', '128', '--reasoning-budget', '256'] + extra, 18080, probe.DEVICE_ROOT + '/key')
    probe.wait_for_health(18080, key)
    report['loadSeconds'] = time.monotonic() - start
    report['affinity'] = probe.thread_affinity_snapshot(executable)
    print('Loaded', name, round(report['loadSeconds'], 2), flush=True)
    if mode in ['speed', 'both']:
        for i in range(4):
            before = cooldown()
            messages = [{'role': 'user', 'content': f'Задание {i + 1}. Напиши подробное учебное объяснение того, как программы читают файлы, изменяют строки и проверяют результат тестами. Нужно не меньше 400 слов.'}]
            response, elapsed = request(messages, 192, ignore_eos=True)
            timings = response.get('timings', {})
            after = probe.thermal_state()
            valid = timings.get('predicted_n') == 192
            sample = {'index': i, 'warmup': i == 0, 'valid': valid, 'elapsedSeconds': elapsed, 'timings': timings, 'thermalBefore': before, 'thermalAfter': after, 'peakRssKiB': probe.peak_rss_kib(executable)}
            report['samples'].append(sample)
            save()
            print('Sample', i, timings.get('predicted_per_second'), timings.get('predicted_n'), 'valid', valid, flush=True)
        rates = [s['timings']['predicted_per_second'] for s in report['samples'] if s['valid'] and not s['warmup']]
        report['medianDecodeTokensPerSecond'] = statistics.median(rates) if rates else None
    if mode in ['tools', 'both']:
        tool_case('missing', 'Вызови read ровно один раз для docs/definitely-missing.txt. Если файл отсутствует, сразу сообщи об этом и остановись.')
        tool_case('repair', 'Исправь функцию clamp в src/math_utils.py: число внутри границ должно оставаться неизменным, ниже нижней границы возвращается нижняя, выше верхней — верхняя. Сначала прочитай файл, затем исправь его и запусти tests/test_math_utils.py. Не меняй другие файлы.')
        tool_case('weather', 'Какая погода в Москве? Используй weather один раз и кратко укажи температуру и осадки. Это синтетическая проверка инструмента.')
    save()
finally:
    if handle is not None:
        report['serverLogTail'] = subprocess.run(['adb', 'shell', 'tail', '-c', '12000', probe.DEVICE_ROOT + '/server.log'], capture_output=True, check=False).stdout.decode('utf-8', errors='replace')
        save()
        probe.stop_server(library, executable, handle)
    probe.adb('forward', '--remove', 'tcp:18080', check=False)
