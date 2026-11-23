import { readFile, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

const base = 'https://loyalcaravan951.grafana.net';
const uid = 'spring_boot_21';
const datasource = { type: 'prometheus', uid: 'grafanacloud-prom' };
const names = ['Media Service', 'Voice Relay', 'MCP Server'];

export function appendServiceRows(dashboard, template) {
  if (dashboard.uid !== uid) throw new Error('Unexpected dashboard');
  if (dashboard.panels.some(p => names.includes(p.title))) throw new Error('Service rows already exist; refusing to replace them');
  const next = structuredClone(dashboard);
  const flatten = panels => panels.flatMap(p => [p, ...flatten(p.panels || [])]);
  let id = Math.max(...flatten(next.panels).map(p => p.id || 0)) + 1;
  let y = Math.max(...next.panels.map(p => p.gridPos.y + p.gridPos.h));
  const media = JSON.parse(JSON.stringify(template).replaceAll('portfolio-application-copilot', 'portfolio-media-service').replaceAll('Career Workflow Service', 'Media Service'));
  const oldY = media.gridPos.y;
  media.id = id++; media.gridPos.y = y;
  for (const p of media.panels) {
    p.id = id++; p.gridPos.y += y - oldY;
    if (p.title === 'Availability') {
      p.title = 'Metrics check';
      p.targets[0].expr = 'min(up{job="portfolio-media-service"})';
      p.fieldConfig.defaults.mappings[0].options['0'].text = 'CHECK FAILED';
      p.fieldConfig.defaults.noValue = 'NO DATA';
      p.description = 'Authenticated metrics scrape every 120 seconds. Missing data is not proof of a service outage.';
    }
  }
  next.panels.push(media); y++;
  for (const [name, job] of [['Voice Relay','portfolio-voice-relay'], ['MCP Server','portfolio-mcp-server']]) {
    const panel = (title, expr, x, unit, history = false) => ({
      id: id++, title, type: history ? 'timeseries' : 'stat', datasource,
      description: 'Public health endpoint sampled every 120 seconds. Does not exercise AI, tools, or voice calls.',
      gridPos: {x, y:y+1, w:6, h:6},
      targets: [{refId:'A',datasource,expr,instant:!history,range:history,legendFormat:title}],
      fieldConfig: {defaults:{unit,noValue:'NO DATA',decimals:unit === 'ms' ? 1 : 0,
        mappings: title === 'Health check' ? [{type:'value',options:{'0':{text:'CHECK FAILED',color:'red'},'1':{text:'UP',color:'green'}}}] : [],
        thresholds:{mode:'absolute',steps:[{color:'green',value:null}]}},overrides:[]},
      options: history ? {legend:{displayMode:'list',placement:'bottom'},tooltip:{mode:'single'}} : {colorMode:'value',graphMode:'none',reduceOptions:{calcs:['lastNotNull'],fields:'',values:false}},
    });
    next.panels.push({id:id++,title:name,type:'row',collapsed:true,gridPos:{x:0,y,w:24,h:1},panels:[
      panel('Health check',`min(probe_success{job="${job}"})`,0,'none'),
      panel('Response time',`max(probe_duration_seconds{job="${job}"})*1000`,6,'ms'),
      panel('HTTP status',`max(probe_http_status_code{job="${job}"})`,12,'none'),
      panel('Response time history',`max(probe_duration_seconds{job="${job}"})*1000`,18,'ms',true),
    ]}); y++;
  }
  return next;
}

async function main() {
  const token = process.env.GRAFANA_RENDER_TOKEN;
  if (!token) throw new Error('GRAFANA_RENDER_TOKEN is required');
  const headers = {Authorization:`Bearer ${token}`,'Content-Type':'application/json'};
  const response = await fetch(`${base}/api/dashboards/uid/${uid}`, {headers});
  if (!response.ok) throw new Error(`Dashboard read failed: ${response.status}`);
  const current = await response.json();
  const template = JSON.parse(await readFile(new URL('./career-grafana-row.json',import.meta.url)));
  const next = appendServiceRows(current.dashboard,template);
  console.log(JSON.stringify({uid,version:current.dashboard.version,added:names,apply:process.argv.includes('--apply')}));
  if (!process.argv.includes('--apply')) return;
  const backup = process.env.GRAFANA_DASHBOARD_BACKUP;
  if (!backup) throw new Error('Set GRAFANA_DASHBOARD_BACKUP before applying');
  await writeFile(backup,JSON.stringify(current,null,2),{flag:'wx',mode:0o600});
  const saved = await fetch(`${base}/api/dashboards/db`,{method:'POST',headers,
    body:JSON.stringify({dashboard:next,folderUid:current.meta.folderUid,overwrite:false,message:'Add Media and Node health monitoring'})});
  if (!saved.ok) throw new Error(`Dashboard save failed: ${saved.status}`);
  const result = await saved.json();
  console.log(JSON.stringify({status:result.status,uid:result.uid,version:result.version}));
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) main().catch(error => {console.error(error.message);process.exitCode=1;});
