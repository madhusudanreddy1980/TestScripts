<#-- HTML output format: everything is auto-escaped. Null values are "missing" in FreeMarker, hence the ! defaults. -->
<#function v x><#if x?is_sequence><#return x?join(', ')><#elseif x?is_hash><#return 'object'><#elseif x?is_boolean><#return x?c><#else><#return x?string></#if></#function>
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${r.job.job_id} · ${r.test_run_id} · ${r.result}</title>
<style>
  :root { --bg:#fbfaf8; --fg:#1d1d1b; --muted:#6b6a66; --line:#e4e1db; --pass:#1f7a4d; --fail:#b3261e; --skip:#8a6d00; --card:#fff; }
  @media (prefers-color-scheme: dark) { :root { --bg:#171716; --fg:#ecebe7; --muted:#a3a19b; --line:#33322f; --pass:#5fc58f; --fail:#ff8a80; --skip:#e3c35a; --card:#1f1f1d; } }
  * { box-sizing: border-box; }
  body { margin:0; padding:24px 16px 48px; background:var(--bg); color:var(--fg); font:14px/1.5 ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif; }
  main { max-width:1100px; margin:0 auto; }
  h1 { font-size:22px; margin:0 0 4px; } h2 { font-size:16px; margin:28px 0 8px; border-bottom:1px solid var(--line); padding-bottom:4px; }
  .muted { color:var(--muted); } code, pre { font:12px/1.45 ui-monospace, SFMono-Regular, Menlo, monospace; }
  pre { background:var(--card); border:1px solid var(--line); padding:10px; overflow-x:auto; white-space:pre-wrap; word-break:break-word; }
  .badge { display:inline-block; white-space:nowrap; padding:2px 10px; border-radius:999px; font-weight:600; font-size:12px; color:#fff; }
  .badge.PASS { background:var(--pass); } .badge.FAIL, .badge.ERROR { background:var(--fail); }
  .badge.SKIPPED, .badge.TIMED_OUT, .badge.NOT_FOUND, .badge.NOT_SUBMITTED { background:var(--skip); }
  .grid { display:grid; grid-template-columns:repeat(auto-fit, minmax(160px, 1fr)); gap:8px; }
  .kv { background:var(--card); border:1px solid var(--line); border-radius:8px; padding:8px 10px; }
  .kv b { display:block; font-size:11px; text-transform:uppercase; letter-spacing:.04em; color:var(--muted); font-weight:600; }
  .kv span { word-break:break-all; }
  .scroll { overflow-x:auto; }
  table { border-collapse:collapse; width:100%; background:var(--card); }
  th, td { border:1px solid var(--line); padding:6px 8px; text-align:left; vertical-align:top; }
  th { font-size:12px; color:var(--muted); font-weight:600; }
  tr.r-FAIL td { background: color-mix(in srgb, var(--fail) 8%, var(--card)); }
  tr.r-FAIL td:first-child { border-left:3px solid var(--fail); }
  details summary { cursor:pointer; color:var(--muted); }
</style>
</head>
<body><main>
<h1>${r.job.name} <span class="badge ${r.result}" data-testid="report-result">${r.result}</span></h1>
<div class="muted">Run <code>${r.test_run_id}</code> · scenario <b>${r.scenario.name}</b> (expect ${r.scenario.expect}) · ${r.environment} / ${r.cloud_provider} · exit ${r.exit_code}</div>
<p>${r.summary}</p>

<h2>Execution</h2>
<div class="grid">
  <div class="kv"><b>Final state</b><span>${r.final_state}</span></div>
  <div class="kv"><b>Input file</b><span>${r.input_file.name!'—'}</span></div>
  <div class="kv"><b>SHA-256</b><span>${(r.input_file.sha256!'')?truncate(16)}</span></div>
  <div class="kv"><b>Records in file</b><span>${r.input_file.records}</span></div>
  <div class="kv"><b>Schema valid</b><span><#if r.input_file.schema_validation??>${r.input_file.schema_validation.valid?c}<#else>—</#if></span></div>
  <div class="kv"><b>Upload verified</b><span><#if r.upload??>${(r.upload.verified!false)?c}<#else>not submitted</#if></span></div>
<#if r.batch??>
  <div class="kv"><b>Execution ID</b><span>${r.batch.executionId!'—'}</span></div>
  <div class="kv"><b>Batch status</b><span>${r.batch.status} (${r.batch.appStatus!'—'})</span></div>
  <div class="kv"><b>Started</b><span>${r.batch.startedAt!'—'}</span></div>
  <div class="kv"><b>Completed</b><span>${r.batch.completedAt!'—'}</span></div>
  <div class="kv"><b>Duration</b><span>${r.batch.durationSeconds!'—'} s</span></div>
  <#list r.batch.counts as k, c><div class="kv"><b>${k?replace('_', ' ')}</b><span>${c!'—'}</span></div></#list>
</#if>
</div>
<#if r.upload??><p class="muted">Inbound location: <code>${r.upload.uri}</code></p></#if>
<#if r.batch?? && r.batch.errorMessage??><p>Batch error: <code>${r.batch.errorMessage}</code></p></#if>
<#if r.error??><p><span class="badge ERROR">${r.error.category}</span> ${r.error.message}</p></#if>

<#assign verrs = (r.input_file.schema_validation.errors)![] + r.input_file.business_rule_errors![]>
<#if verrs?has_content>
<h2>Validation errors</h2>
<pre><#list verrs as e>${e}
</#list></pre>
</#if>

<h2>Database validation (<#list r.assertion_counts as k, c>${c} ${k}<#sep>, </#sep><#else>none</#list>)</h2>
<#if r.assertions?has_content>
<div class="scroll"><table>
<tr><th>ID</th><th>Category</th><th>Rule</th><th>Expected</th><th>Actual</th><th>Status</th><th>Evidence</th></tr>
<#list r.assertions as a>
<tr class="r-${a.status}">
  <td><code>${a.id}</code></td><td>${a.category}</td><td>${a.rule}<#if a.details?has_content><div class="muted">${a.details}</div></#if></td>
  <td>${v(a.expected!'—')}</td><td>${v(a.actual!'—')}</td><td><span class="badge ${a.status}">${a.status}</span></td>
  <td><details><summary>SQL</summary><pre>${a.query!''}
records: ${v(a.recordIds![])}
at: ${a.timestamp}</pre></details></td>
</tr>
</#list>
</table></div>
<#else><p class="muted">No database assertions (file not submitted or batch did not reach a terminal state).</p></#if>

<#if r.field_differences?has_content>
<h2>Field-level differences</h2>
<div class="scroll"><table><tr><th>Assertion</th><th>Column</th><th>Expected</th><th>Actual</th><th>Kind</th></tr>
<#list r.field_differences as d><tr><td><code>${d.assertion}</code></td><td>${d.column}</td><td>${v(d.expected!'null')}</td><td>${v(d.actual!'null')}</td><td>${d.kind!'mapped'}</td></tr></#list>
</table></div>
</#if>

<h2>Records and traceability</h2>
<div class="scroll"><table><tr><th>#</th><th>Key</th><th>Source ID</th><th>Origin</th><th>Expected outcome</th><th>Notes</th></tr>
<#list r.record_traceability as t>
<tr><td>${t.index}</td><td>${v(t.key!'—')}</td><td>${v(t.source_key!'—')}</td><td>${t.origin}</td>
<td><#if (r.expectations?size > t?index)><#assign e = r.expectations[t?index]>${e.action}<#if e.reason??> (${e.reason})</#if><#else>—</#if></td>
<td>${t.notes?join(', ')}</td></tr>
</#list>
</table></div>

<h2>Execution log</h2>
<div class="scroll"><table><tr><th>Time (UTC)</th><th>State</th><th>Message</th></tr>
<#list r.events as ev><tr><td><code>${ev.at?substring(11, [ev.at?length, 23]?min)}</code></td><td>${ev.state}</td><td>${ev.message}</td></tr></#list>
</table></div>
<p class="muted">Generated ${r.generated_at} · sensitive fields masked per job classification.</p>
</main></body></html>
