import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { appendServiceRows } from './add-service-panels.mjs';
const template = JSON.parse(readFileSync(new URL('./career-grafana-row.json',import.meta.url)));
test('append rows without modifying existing panels, variables, or version',()=>{
  const source={uid:'spring_boot_21',version:9,templating:{list:[]},panels:[template]};
  const next=appendServiceRows(source,template);
  assert.deepEqual(next.panels[0],template);
  assert.equal(source.panels.length,1); assert.equal(next.panels.length,4);
  assert.equal(next.version,9);assert.deepEqual(next.templating,source.templating);
  const ids=next.panels.flatMap(p=>[p.id,...p.panels.map(n=>n.id)]);
  assert.equal(new Set(ids).size,ids.length);
  for(const row of next.panels.slice(2)){
    assert.match(row.panels[0].targets[0].expr,/probe_success/);
    assert.doesNotMatch(JSON.stringify(row),/jvm_|heap|reqps/);
  }
  assert.throws(()=>appendServiceRows(next,template),/already exist/);
  assert.throws(()=>appendServiceRows({...source,uid:'other'},template),/Unexpected/);
});
