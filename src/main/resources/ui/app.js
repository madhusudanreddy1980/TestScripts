/* Batch Test Console — vanilla JS, no external dependencies. All text is set via textContent. */
(() => {
  'use strict';
  const $ = (sel) => document.querySelector(sel);
  const tid = (id) => document.querySelector(`[data-testid="${id}"]`);
  const state = { catalogue: null, job: null, run: null, poll: null };
  const TERMINAL = ['COMPLETED', 'FAILED', 'TIMED_OUT', 'NOT_FOUND', 'ERROR'];

  function el(tag, attrs = {}, ...children) {
    const e = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (v === undefined || v === null || v === false) continue;
      if (k === 'class') e.className = v;
      else if (k === 'text') e.textContent = v;
      else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
      else e.setAttribute(k, v === true ? '' : v);
    }
    for (const c of children.flat()) if (c != null) e.append(c instanceof Node ? c : document.createTextNode(String(c)));
    return e;
  }
  const fmt = (v) => v === null || v === undefined ? '—' : (typeof v === 'object' ? JSON.stringify(v) : String(v));

  function toast(msg, info = false) {
    const t = tid('toast');
    t.textContent = msg;
    t.className = 'toast' + (info ? ' info' : '');
    t.hidden = false;
    clearTimeout(t._h);
    t._h = setTimeout(() => { t.hidden = true; }, 6000);
  }

  async function api(method, path, body) {
    const res = await fetch(path, {
      method, headers: body ? { 'Content-Type': 'application/json' } : {},
      body: body ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch { data = text; }
    if (!res.ok) {
      const msg = data && data.message ? `${data.error}: ${data.message}` : `HTTP ${res.status}`;
      toast(msg);
      throw new Error(msg);
    }
    return data;
  }

  // ------------------------------------------------------------------ tabs
  function showTab(name) {
    document.querySelectorAll('.tabs button').forEach((b) => b.classList.toggle('active', b.dataset.tab === name));
    document.querySelectorAll('.screen').forEach((s) => s.classList.toggle('active', s.id === name));
    if (name === 'reports') loadReports();
  }
  document.querySelectorAll('.tabs button').forEach((b) => b.addEventListener('click', () => showTab(b.dataset.tab)));
  document.querySelectorAll('[data-close]').forEach((b) => b.addEventListener('click', () => { b.closest('.panel').hidden = true; }));

  // ------------------------------------------------------------- screen 1
  async function loadCatalogue() {
    const c = await api('GET', '/api/catalogue');
    state.catalogue = c;
    tid('env-badge').textContent = `env: ${c.environment} · cloud: ${c.cloud_provider} (${c.cloud_profile})`
      + (c.submission_allowed ? '' : ' · submission disabled');
    const tbody = tid('catalogue-table').querySelector('tbody');
    tbody.replaceChildren();
    for (const j of c.jobs) {
      const valid = j.configuration_status === 'VALID';
      tbody.append(el('tr', { 'data-testid': `job-row-${j.job_id}` },
        el('td', { text: j.job_name }), el('td', {}, el('code', { text: j.job_id })), el('td', { text: j.file_format }),
        el('td', { text: j.cloud_provider }), el('td', {}, el('code', { text: j.inbound_location })),
        el('td', { text: j.database_profile }), el('td', { text: j.batch_status_table }), el('td', { text: j.test_template }),
        el('td', { text: j.schema_version }),
        el('td', { 'data-testid': `job-status-${j.job_id}`, text: j.configuration_status }),
        el('td', {},
          el('button', { 'data-testid': `select-${j.job_id}`, disabled: !valid, onclick: () => selectJob(j.job_id, false) }, 'Select'),
          el('button', { 'data-testid': `view-config-${j.job_id}`, disabled: !valid, onclick: () => showConfig(j.job_id, false) }, 'View configuration'),
          el('button', { 'data-testid': `view-template-${j.job_id}`, disabled: !valid, onclick: () => showConfig(j.job_id, true) }, 'View template'),
          el('button', { class: 'primary', 'data-testid': `execute-${j.job_id}`, disabled: !valid, onclick: () => selectJob(j.job_id, true) }, 'Execute test'))));
    }
    const fj = tid('f-job');
    fj.replaceChildren(el('option', { value: '', text: 'Any' }), ...c.jobs.map((j) => el('option', { value: j.job_id, text: j.job_id })));
  }

  async function showConfig(jobId, templateOnly) {
    const cfg = await api('GET', `/api/jobs/${encodeURIComponent(jobId)}/config`);
    const panel = tid('config-panel');
    tid('config-title').textContent = templateOnly ? `${jobId} · ${cfg.template_name}` : `${jobId} · configuration`;
    tid('config-body').textContent = templateOnly ? cfg.template
      : `# job.yaml\n${cfg.job_yaml}\n# validation-rules.yaml\n${cfg.validation_rules_yaml}\n# ${'eligibility SQL'}\n${cfg.eligibility_sql}`;
    panel.hidden = false;
  }

  function selectJob(jobId, go) {
    state.job = jobId;
    resetRun();
    tid('selected-job').textContent = `— ${jobId}`;
    const sel = tid('scenario');
    sel.replaceChildren(...state.catalogue.scenarios.filter((s) => s.job_id === jobId)
      .map((s) => el('option', { value: s.name, text: `${s.name} (${s.expect})` })));
    describeScenario();
    toast(`Selected ${jobId}`, true);
    if (go) showTab('data');
  }

  function scenarioInfo() {
    return state.catalogue.scenarios.find((s) => s.job_id === state.job && s.name === tid('scenario').value);
  }
  function describeScenario() {
    const s = scenarioInfo();
    tid('scenario-description').textContent = s
      ? `${s.description || s.name} — ${s.expect === 'processed' ? 'positive test' : 'negative test, expect ' + s.expect.replace('_', ' ')}`
      + (s.synthetic ? ' · synthetic records' : '') : '';
  }
  tid('scenario').addEventListener('change', describeScenario);

  // ------------------------------------------------------------- screen 2
  function needJob() {
    if (!state.job) { toast('Select a batch job in the catalogue first'); throw new Error('no job'); }
  }

  tid('btn-load-template').addEventListener('click', async () => {
    needJob();
    const cfg = await api('GET', `/api/jobs/${encodeURIComponent(state.job)}/config`);
    tid('template-title').textContent = `${cfg.template_name} · schema ${cfg.schema_name}`;
    tid('template-body').textContent = cfg.template;
    tid('template-panel').hidden = false;
  });

  tid('btn-fetch-eligible').addEventListener('click', async () => {
    needJob();
    const data = await api('GET', `/api/jobs/${encodeURIComponent(state.job)}/eligible`);
    const table = tid('eligible-table');
    const cols = data.rows.length ? Object.keys(data.rows[0].row) : [];
    table.querySelector('thead').replaceChildren(el('tr', {}, el('th', { text: 'Use' }), el('th', { text: 'Eligible' }),
      ...cols.map((c) => el('th', { text: c }))));
    const tbody = table.querySelector('tbody');
    tbody.replaceChildren();
    for (const r of data.rows) {
      tbody.append(el('tr', { class: r.eligible ? '' : 'ineligible', 'data-testid': `eligible-row-${r.key}` },
        el('td', {}, el('input', { type: 'checkbox', 'data-testid': `eligible-check-${r.key}`, disabled: !r.eligible,
          onchange: syncKeys, 'data-key': r.key })),
        el('td', { text: r.eligible ? 'yes' : `no — ${r.reasons.join('; ')}` }),
        ...cols.map((c) => el('td', { text: fmt(r.row[c]) }))));
    }
    const n = data.rows.filter((r) => r.eligible).length;
    tid('eligible-summary').textContent = `${n} eligible of ${data.rows.length} returned`;
    tid('eligible-panel').hidden = false;
  });

  function syncKeys() {
    const keys = [...tid('eligible-table').querySelectorAll('input[type=checkbox]:checked')].map((c) => c.dataset.key);
    tid('keys').value = keys.join(', ');
    if (keys.length) tid('count').value = keys.length;
  }

  function parseOverrides(text) {
    const out = {};
    for (const line of text.split('\n').map((l) => l.trim()).filter(Boolean)) {
      const m = line.match(/^([^.=\s]+)\.([^=\s]+)\s*=\s*(.*)$/);
      if (!m) { toast(`Override must look like all.field=value — got "${line}"`); throw new Error('bad override'); }
      (out[m[1]] = out[m[1]] || {})[m[2]] = m[3];
    }
    return out;
  }

  tid('btn-generate').addEventListener('click', async () => {
    needJob();
    const keys = tid('keys').value.split(',').map((k) => k.trim()).filter(Boolean);
    const body = { job_id: state.job, scenario: tid('scenario').value, count: Number(tid('count').value) || 1,
      keys, overrides: parseOverrides(tid('overrides').value) };
    stopPolling();
    const run = await api('POST', '/api/runs', body);
    renderRun(run);
    tid('preview-panel').hidden = false;
  });

  tid('btn-validate').addEventListener('click', async () => {
    renderRun(await api('POST', `/api/runs/${state.run.run_id}/validate`));
  });

  tid('btn-preview').addEventListener('click', () => { tid('preview-panel').hidden = !tid('preview-panel').hidden; });
  tid('confirm-submit').addEventListener('change', () => state.run && renderRun(state.run));

  tid('btn-submit').addEventListener('click', async () => {
    if (!tid('confirm-submit').checked) { toast('Tick the confirmation box first'); return; }
    const run = await api('POST', `/api/runs/${state.run.run_id}/submit`, { confirm: true });
    renderRun(run);
    showTab('monitor');
    startPolling();
  });

  function resetRun() {
    stopPolling();
    state.run = null;
    tid('run-panel').hidden = true;
    tid('confirm-submit').checked = false;
    renderMonitor(null);
    renderValidation(null);
  }

  function renderRun(run) {
    state.run = run;
    tid('run-panel').hidden = false;
    tid('run-id').textContent = run.run_id;
    tid('run-state').textContent = run.state;
    tid('file-name').textContent = fmt(run.file_name);
    tid('record-count').textContent = String(run.records);
    tid('file-sha').textContent = run.sha256 ? run.sha256.slice(0, 16) + '…' : '—';
    const target = state.catalogue.jobs.find((j) => j.job_id === run.job_id);
    tid('submit-target').textContent = target ? `${target.inbound_location}/${run.file_name}` : '';

    const v = run.validation;
    tid('validation-errors').hidden = !(v && !v.valid);
    tid('validation-errors').textContent = v && !v.valid ? v.errors.join('\n') : '';
    tid('validation-ok').hidden = !(v && v.valid);

    if (run.preview) {
      const recs = run.preview.records;
      const cols = recs.length ? Object.keys(recs[0]) : [];
      tid('preview-table').querySelector('thead').replaceChildren(el('tr', {}, ...cols.map((c) => el('th', { text: c }))));
      tid('preview-table').querySelector('tbody').replaceChildren(...recs.map((r) => el('tr', {}, ...cols.map((c) => el('td', { text: fmt(r[c]) })))));
      tid('preview-file').textContent = run.preview.file_preview;
    }

    const dl = tid('btn-download');
    dl.setAttribute('aria-disabled', run.file_name ? 'false' : 'true');
    if (run.file_name) dl.href = `/api/runs/${run.run_id}/download`;
    tid('btn-validate').disabled = run.state !== 'DATA_GENERATED';
    tid('btn-preview').disabled = !run.preview;

    const canSubmit = state.catalogue.submission_allowed && run.expect !== 'blocked' && !run.upload
      && (run.state === 'VALIDATED' || (run.state === 'VALIDATION_FAILED' && run.force_submit_invalid));
    tid('btn-submit').disabled = !(canSubmit && tid('confirm-submit').checked);
    tid('confirm-submit').disabled = !canSubmit;
    let hint;
    if (run.result && run.expect === 'blocked') hint = `Negative scenario: ${run.result.result} — ${run.result.summary}`;
    else if (run.upload) hint = 'Submitted. Follow progress on the Execution monitor.';
    else if (run.state === 'DATA_GENERATED') hint = 'Validate before submitting.';
    else if (run.state === 'VALIDATION_FAILED' && !run.force_submit_invalid) hint = 'Submission blocked: the file failed validation.';
    else if (canSubmit) hint = tid('confirm-submit').checked ? 'Ready to submit.' : 'Tick the confirmation box to enable submission.';
    else hint = 'Generate and validate first. Nothing is submitted automatically.';
    tid('submit-hint').textContent = hint;
    renderMonitor(run);
    renderValidation(run);
  }

  // ------------------------------------------------------------- screen 3
  function startPolling() {
    stopPolling();
    state.poll = setInterval(async () => {
      try {
        const run = await api('GET', `/api/runs/${state.run.run_id}`);
        renderRun(run);
        if (run.result) stopPolling();
      } catch { stopPolling(); }
    }, 400);
  }
  function stopPolling() { if (state.poll) clearInterval(state.poll); state.poll = null; }

  function renderMonitor(run) {
    const submitted = run && run.upload;
    tid('monitor-empty').hidden = !!submitted;
    tid('monitor-fields').hidden = !submitted;
    const order = ['CREATED', 'DATA_GENERATED', 'VALIDATED', 'UPLOADED', 'PROCESSING', 'TERMINAL'];
    const cur = !run ? -1 : TERMINAL.includes(run.state) ? 5 : run.state === 'VALIDATION_FAILED' ? 2 : order.indexOf(run.state);
    tid('state-chain').querySelectorAll('li').forEach((li, i) => {
      li.className = i < cur ? 'done' : i === cur ? 'current' : '';
    });
    const term = tid('chain-terminal');
    term.textContent = run && TERMINAL.includes(run.state) ? run.state : 'Completed / Failed / Timed out';
    if (run && ['FAILED', 'TIMED_OUT', 'NOT_FOUND', 'ERROR'].includes(run.state)) term.className = 'bad';
    const events = run ? run.events : [];
    tid('events-table').querySelector('tbody').replaceChildren(...events.map((e) =>
      el('tr', {}, el('td', {}, el('code', { text: (e.at || '').slice(11, 23) })), el('td', { text: e.state }), el('td', { text: e.message }))));
    if (!submitted) return;
    const o = run.outcome || {};
    const c = o.counts || {};
    const set = (id, v) => { tid(id).textContent = fmt(v); };
    set('m-run-id', run.run_id); set('m-job', run.job_name); set('m-file', run.file_name); set('m-cloud', run.cloud_provider);
    set('m-inbound', run.upload.uri);
    set('m-upload', run.upload.verified ? 'UPLOADED · verified (SHA-256)' : 'UPLOADED · NOT verified');
    set('m-status', o.status ? `${o.status} (${o.appStatus || '—'})` : 'PROCESSING');
    set('m-exec', o.executionId); set('m-start', o.startedAt); set('m-end', o.completedAt);
    set('m-duration', o.durationSeconds != null ? `${o.durationSeconds} s` : null);
    set('m-read', c.records_read); set('m-processed', c.records_processed); set('m-inserted', c.records_inserted);
    set('m-updated', c.records_updated); set('m-rejected', c.records_rejected);
    const r = tid('m-result');
    r.replaceChildren(run.result ? el('span', { class: `badge ${run.result.result}`, text: run.result.result }) : document.createTextNode('pending'));
    const err = tid('m-error');
    const msg = run.error ? `${run.error.category}: ${run.error.message}` : o.errorMessage || (o.notes && o.notes.length ? o.notes.join('; ') : '');
    err.hidden = !msg;
    err.textContent = msg || '';
  }

  // ------------------------------------------------------------- screen 4
  function renderValidation(run) {
    const res = tid('v-result');
    res.replaceChildren();
    const link = tid('v-report-link');
    if (!run || !run.result) {
      tid('v-summary').textContent = run && run.upload ? 'Waiting for the batch to finish…' : 'No reconciliation yet.';
      link.hidden = true;
    } else {
      res.append(el('span', { class: `badge ${run.result.result}`, 'data-testid': 'v-result-badge', text: run.result.result }));
      tid('v-summary').textContent = `${run.result.summary} (exit code ${run.result.exit_code})`;
      link.href = `/api/runs/${run.run_id}/report`;
      link.hidden = false;
    }
    const asserts = run ? run.assertions : [];
    tid('assertions-table').querySelector('tbody').replaceChildren(...asserts.map((a) =>
      el('tr', { class: a.status === 'FAIL' ? 'fail' : '', 'data-testid': `assert-${a.id}` },
        el('td', {}, el('code', { text: a.id })), el('td', { text: a.category }),
        el('td', {}, a.rule, a.details ? el('div', { class: 'muted', text: a.details }) : null),
        el('td', { text: fmt(a.expected) }), el('td', { text: fmt(a.actual) }),
        el('td', {}, el('span', { class: `badge ${a.status}`, text: a.status })),
        el('td', {}, el('details', {}, el('summary', { text: 'evidence' }),
          el('pre', { text: `${a.query || ''}\nparams: ${JSON.stringify(a.params || {})}\nrecords: ${JSON.stringify(a.recordIds || [])}\nat: ${a.timestamp}` }))))));
    const exps = run ? run.expectations : [];
    tid('expectations-table').querySelector('tbody').replaceChildren(...exps.map((e) =>
      el('tr', {}, el('td', { text: String(e.index) }), el('td', { text: fmt(e.key) }), el('td', { text: e.action }),
        el('td', { text: fmt(e.reason) }), el('td', {}, el('code', { text: e.expected_row ? JSON.stringify(e.expected_row) : '—' })))));
    const diffs = [];
    asserts.forEach((a) => (a.diffs || []).forEach((d) => diffs.push({ ...d, assertion: a.id })));
    tid('diffs-table').querySelector('tbody').replaceChildren(...diffs.map((d) =>
      el('tr', {}, el('td', {}, el('code', { text: d.assertion })), el('td', { text: d.column }), el('td', { text: fmt(d.expected) }),
        el('td', { text: fmt(d.actual) }), el('td', { text: d.kind || 'mapped' }))));
  }

  // --------------------------------------------------------------- reports
  async function loadReports() {
    const q = new URLSearchParams();
    const add = (k, id) => { const v = tid(id).value; if (v) q.set(k, v); };
    add('job', 'f-job'); add('result', 'f-result'); add('scenario', 'f-scenario'); add('date', 'f-date');
    const rows = await api('GET', `/api/reports?${q}`);
    tid('reports-table').querySelector('tbody').replaceChildren(...rows.map((r) =>
      el('tr', { 'data-testid': `report-row-${r.run_id}` }, el('td', {}, el('code', { text: r.run_id })), el('td', { text: r.job }),
        el('td', { text: r.environment }), el('td', { text: r.scenario }),
        el('td', {}, el('span', { class: `badge ${r.result}`, text: r.result })), el('td', { text: r.summary }))));
  }
  tid('btn-filter').addEventListener('click', loadReports);

  loadCatalogue().catch(() => {});
})();
