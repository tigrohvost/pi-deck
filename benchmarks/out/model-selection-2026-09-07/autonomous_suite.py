import datetime,json,sys,uuid
from pathlib import Path
sys.path.insert(0,'/home/che/dev/pi-deck-android-alpha13/tools')
from adb_agent_benchmark import AdbClient,AgentBenchmark,BridgeClient,read_bridge_token
from adb_suite_v2 import load_suite,score_case
R=Path('/tmp/pideck-model-audit-20260907')
class BoundedBridge(BridgeClient):
 limit=6
 seen=set()
 aborted=False
 def events(self,after,timeout_ms=1000):
  response=super().events(after,timeout_ms)
  for e in response.get('events',[]):
   if e.get('type')=='TOOL_CALL_REQUESTED':
    payload=e.get('payload',{})
    self.seen.add(str(payload.get('toolCallId') or e.get('seq') or e.get('sequence') or repr(payload)))
    if len(self.seen)>self.limit and not self.aborted:
     self.aborted=True
     self.command(str(uuid.uuid4()),'ABORT',{'targetOperationId':e['operationId']})
     print('Stopped after exceeding task call limit',self.limit,flush=True)
  return response
adb=AdbClient('R5CW11HGLVV'); adb.forward(18787,8787)
b=BoundedBridge(18787,read_bridge_token(R/'bridge-token'))
runner=AgentBenchmark(adb,b,'dev.pideck.app','dev.pideck.app/.MainActivity',420,3,'lfm2.5-2.6b-qad',0.94,240)
state=runner.wait_ready(); assert state.get('accessProfile')=='autonomous'; assert not state.get('activeOperationId')
report={'createdAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'modelId':state['modelId'],'accessProfile':state['accessProfile'],'tasksSelected':sys.argv[1:],'outcomes':[],'harnessNote':'Abort after the suite maximum tool calls has already been exceeded; fixture-only tasks; temporary 20 minute grant.'}
for task in load_suite(Path('/home/che/dev/pi-deck-android-alpha13/benchmarks/suite-v2/tasks.json'))['tasks']:
 if task['id'] not in sys.argv[1:]: continue
 run_id=str(uuid.uuid4()); before=b.prepare_benchmark(run_id)
 b.limit=task['expected'].get('maxToolCalls',8); b.seen=set(); b.aborted=False
 case=runner.run_warm(task['id'],task['prompt'].replace('{fixture}',before['fixturePath']))
 after=b.benchmark_snapshot(run_id)
 outcome=score_case(task,before,after,case); outcome['rawCase']=case
 outcome['stoppedAfterCallLimit']=b.aborted
 report['outcomes'].append(outcome)
 (R/'qad-autonomous-coding.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
 print(task['id'],outcome['outcome'],outcome['toolNames'],outcome['changedPaths'],flush=True)
