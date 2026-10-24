import { ChangeEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, AuditAnalysis, AuditCase, AuditStatus, AuditStep, Dashboard, IdentityResolution, Phase, SourceRecord } from "./api";
import { AdminWorkspace, WorkspaceDataSummary } from "./AdminWorkspace";

const phaseOptions: Phase[] = ["FINDING", "REMEDIATION", "VERIFICATION"];
const operationOptions: Record<Phase, { operation: string; state: string }[]> = {
  FINDING: [
    { operation: "REPORT_REMAINING_RECORD", state: "PRESENT" },
    { operation: "REPORT_EXPOSED_PII", state: "PRESENT" },
    { operation: "REPORT_ACTIVE_DATA_FLOW", state: "ACTIVE" },
    { operation: "REPORT_RESTORE_RISK", state: "ACTIVE" },
  ],
  REMEDIATION: [
    { operation: "DISABLE_DATA_FLOW", state: "DISABLED" },
    { operation: "BLOCK_RESTORE", state: "BLOCKED" },
    { operation: "DELETE_RECORD", state: "ABSENT" },
    { operation: "REDACT_RECORD", state: "REDACTED" },
    { operation: "ANONYMIZE_RECORD", state: "ANONYMIZED" },
  ],
  VERIFICATION: [
    { operation: "VERIFY_RECORD_ABSENT", state: "ABSENT" },
    { operation: "VERIFY_RECORD_PRESENT", state: "PRESENT" },
    { operation: "VERIFY_RECORD_REDACTED", state: "REDACTED" },
    { operation: "VERIFY_RECORD_ANONYMIZED", state: "ANONYMIZED" },
    { operation: "VERIFY_DATA_FLOW_DISABLED", state: "DISABLED" },
    { operation: "VERIFY_RESTORE_BLOCKED", state: "BLOCKED" },
  ],
};

const emptyDashboard: Dashboard = {
  totalCases: 0,
  pendingCases: 0,
  completeCases: 0,
  incompleteCases: 0,
  notApplicableCases: 0,
  sourceFiles: 0,
  reviewedPercent: 0,
};

function App() {
  const [dashboard, setDashboard] = useState(emptyDashboard);
  const [cases, setCases] = useState<AuditCase[]>([]);
  const [search, setSearch] = useState("");
  const [statusFilter, setStatusFilter] = useState("ALL");
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [activeCase, setActiveCase] = useState<AuditCase | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [notice, setNotice] = useState("");
  const [activeView, setActiveView] = useState<"overview" | "customers" | "data" | "activity">("overview");
  const [activeNav, setActiveNav] = useState<"overview" | "customers" | "activity" | "requests" | "data">("overview");
  const [matchingCases, setMatchingCases] = useState(0);
  const [datasetVersion, setDatasetVersion] = useState(0);
  const [batchProgress, setBatchProgress] = useState<{ completed: number; total: number; currentCaseId: string } | null>(null);
  const batchCancelled = useRef(false);
  const refreshSequence = useRef(0);

  const refresh = useCallback(async () => {
    const sequence = ++refreshSequence.current;
    setError("");
    try {
      const [summary, result] = await Promise.all([
        api.dashboard(),
        api.cases(search, statusFilter, page),
      ]);
      if (sequence !== refreshSequence.current) return;
      setDashboard(summary);
      setCases(result.content);
      setTotalPages(result.totalPages);
      setMatchingCases(result.totalElements);
    } catch (cause) {
      if (sequence !== refreshSequence.current) return;
      setError(cause instanceof Error ? cause.message : "Could not load the audit workspace.");
    }
  }, [page, search, statusFilter]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const openCase = async (item: AuditCase) => {
    setError("");
    try {
      setActiveCase(await api.caseDetail(item.caseId));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not load this case.");
    }
  };

  const saveCase = async (status: AuditStatus, steps: AuditStep[]) => {
    if (!activeCase) return;
    setBusy(true);
    setError("");
    try {
      const saved = await api.saveAudit(activeCase.caseId, status, steps);
      setActiveCase(saved);
      setNotice(`Audit saved for ${saved.caseId}`);
      await refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not save the audit.");
    } finally {
      setBusy(false);
    }
  };

  const analyzeCase = async (caseId: string): Promise<AuditAnalysis | null> => {
    setBusy(true);
    setError("");
    try {
      const analysis = await api.analyzeCase(caseId);
      setNotice(`AI analysis prepared for ${caseId}; review and save the proposed audit.`);
      return analysis;
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not analyze this case.");
      return null;
    } finally {
      setBusy(false);
    }
  };

  const analyzePendingCases = async () => {
    if (!window.confirm(`Analyze and save AI recommendations for ${dashboard.pendingCases} pending cases? Case data will be sent to OpenAI and provider usage may be billed.`)) return;
    batchCancelled.current = false;
    setBusy(true);
    setError("");
    try {
      const firstPage = await api.cases("", "ALL", 0, 100);
      const pendingCases = firstPage.content.filter((item) => item.deletionStatus == null);
      for (let pageIndex = 1; pageIndex < firstPage.totalPages; pageIndex++) {
        const pageResult = await api.cases("", "ALL", pageIndex, 100);
        pendingCases.push(...pageResult.content.filter((item) => item.deletionStatus == null));
      }
      let completed = 0;
      for (const item of pendingCases) {
        if (batchCancelled.current) break;
        setBatchProgress({ completed, total: pendingCases.length, currentCaseId: item.caseId });
        const analysis = await api.analyzeCase(item.caseId);
        await api.saveAudit(item.caseId, analysis.audit.deletionStatus, analysis.audit.steps);
        completed++;
        setBatchProgress({ completed, total: pendingCases.length, currentCaseId: item.caseId });
      }
      await refresh();
      setNotice(batchCancelled.current
        ? `AI batch stopped after saving ${completed} of ${pendingCases.length} cases.`
        : `AI recommendations generated and saved for ${completed} cases.`);
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : "AI batch analysis failed.";
      setError(`AI batch stopped. Completed cases remain saved. ${message}`);
    } finally {
      setBusy(false);
      setBatchProgress(null);
    }
  };

  const importFiles = async (files: File[]) => {
    setBusy(true);
    setError("");
    try {
      const result = await api.importFiles(files);
      setNotice(`${result.casesImported} requests and ${result.sourceRowsImported} source rows imported.`);
      setImportOpen(false);
      setPage(0);
      setDatasetVersion((version) => version + 1);
      await refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not import the dataset.");
    } finally {
      setBusy(false);
    }
  };

  const exportCsv = async () => {
    setBusy(true);
    setError("");
    try {
      const blob = await api.exportCsv();
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = "output.csv";
      anchor.click();
      URL.revokeObjectURL(url);
      setNotice("output.csv exported.");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not export the results.");
    } finally {
      setBusy(false);
    }
  };

  const openCaseById = async (caseId: string) => {
    setError("");
    try {
      setActiveCase(await api.caseDetail(caseId));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not load this deletion request.");
    }
  };

  const reviewed = dashboard.totalCases - dashboard.pendingCases;
  const pageTitle = {
    overview: "Admin console",
    customers: "Customer operations",
    data: "Data sources",
    activity: "System activity",
  }[activeView];

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">N</div>
          <div><strong>northstar</strong><span>TRUST OPERATIONS</span></div>
        </div>
        <div className="workspace-label">ADMIN CONSOLE</div>
        <button className={`nav-item ${activeNav === "overview" ? "selected" : ""}`} onClick={() => { setActiveView("overview"); setActiveNav("overview"); }}><span className="nav-icon">▦</span> Overview</button>
        <button className={`nav-item ${activeNav === "customers" ? "selected" : ""}`} onClick={() => { setActiveView("customers"); setActiveNav("customers"); }}><span className="nav-icon">♙</span> Customers</button>
        <button className={`nav-item ${activeNav === "activity" ? "selected" : ""}`} onClick={() => { setActiveView("activity"); setActiveNav("activity"); }}><span className="nav-icon">◷</span> System activity</button>
        <button className={`nav-item ${activeNav === "requests" ? "selected" : ""}`} onClick={() => { setActiveView("overview"); setActiveNav("requests"); window.setTimeout(() => document.getElementById("cases")?.scrollIntoView({ behavior: "smooth" }), 0); }}>
          <span className="nav-icon">◷</span> Deletion requests
          {dashboard.pendingCases > 0 && <span className="nav-count">{dashboard.pendingCases}</span>}
        </button>
        <div className="sidebar-divider" />
        <div className="workspace-label">DATA</div>
        <button className={`nav-item ${activeNav === "data" ? "selected" : ""}`} onClick={() => { setActiveView("data"); setActiveNav("data"); }}><span className="nav-icon">▤</span> Data sources</button>
        <button className="nav-item" onClick={() => setImportOpen(true)}><span className="nav-icon">↥</span> Import dataset</button>
        <div className="sidebar-bottom">
          <div className="secure-note"><span className="secure-icon">⌑</span><div><strong>Evidence-first review</strong><p>Every decision needs a trail.</p></div></div>
          <div className="user-profile"><div className="avatar">AO</div><div><strong>Audit operator</strong><span>Workspace admin</span></div><span className="more">···</span></div>
        </div>
      </aside>

      <main className="main-content">
        <header className="topbar">
          <div className="breadcrumbs">Northstar Shop <span>/</span> <strong>{pageTitle}</strong></div>
          <div className="top-actions"><span className="environment-pill"><i /> LOCAL WORKSPACE</span><button className="icon-button" aria-label="Help">?</button></div>
        </header>
        <div className="page-content">
          <section className="page-heading">
            <div>
              <div className="eyebrow">ADMIN CONSOLE <span>·</span> LOCAL DATA WORKSPACE</div>
              <h1>{pageTitle}</h1>
              <p>{activeView === "overview" ? "Customer privacy operations and deletion audits, backed by the bundled dataset." : "Explore customer records and imported operational data."}</p>
            </div>
            {activeView === "overview" && <div className="heading-actions">
              <button className="button secondary" disabled={busy} onClick={() => setImportOpen(true)}><span>＋</span> Import dataset</button>
              {dashboard.pendingCases > 0 && <button className="button secondary" disabled={busy} onClick={() => void analyzePendingCases()}><span>✦</span> Analyze pending with AI</button>}
              {batchProgress && <div className="batch-progress"><span>{batchProgress.completed}/{batchProgress.total}</span><small>Analyzing {batchProgress.currentCaseId}</small><button onClick={() => { batchCancelled.current = true; }}>Cancel batch</button></div>}
              <button className="button primary" disabled={busy || dashboard.totalCases === 0} onClick={() => void exportCsv()}>
                <span>↓</span> Export output.csv
              </button>
            </div>}
          </section>

          {error && <div className="alert error-alert"><span>!</span><div>{error}</div><button onClick={() => setError("")}>×</button></div>}
          {notice && <div className="alert success-alert"><span>✓</span><div>{notice}</div><button onClick={() => setNotice("")}>×</button></div>}

          {activeView === "overview" ? <>
          <section className="metrics-grid" aria-label="Audit summary">
            <Metric label="Total requests" value={dashboard.totalCases} icon="◎" tone="blue" detail="Imported cases" />
            <Metric label="Awaiting review" value={dashboard.pendingCases} icon="◷" tone="amber" detail="Need an audit decision" />
            <Metric label="Complete" value={dashboard.completeCases} icon="✓" tone="green" detail="Saved audit outcome" />
            <Metric label="Incomplete" value={dashboard.incompleteCases} icon="!" tone="red" detail="Remediation required" />
            <Metric label="Not applicable" value={dashboard.notApplicableCases} icon="—" tone="blue" detail="Deletion not requested" />
          </section>

          <section className="overview-grid">
            <div className="panel progress-panel">
              <div className="panel-title-row"><div><h2>Review progress</h2><p>Cases with a saved audit decision</p></div><span className="progress-glyph">↗</span></div>
              <div className="progress-numbers"><strong>{dashboard.reviewedPercent}<small>%</small></strong><span>{reviewed.toLocaleString()} of {dashboard.totalCases.toLocaleString()} reviewed</span></div>
              <div className="progress-track"><div style={{ width: `${dashboard.reviewedPercent}%` }} /></div>
              <div className="progress-footer"><span><i className="dot complete-dot" /> Complete <b>{dashboard.completeCases}</b></span><span><i className="dot incomplete-dot" /> Incomplete <b>{dashboard.incompleteCases}</b></span><span><i className="dot pending-dot" /> Pending <b>{dashboard.pendingCases}</b></span><span><i className="dot not-applicable-dot" /> Not applicable <b>{dashboard.notApplicableCases}</b></span></div>
            </div>
            <div className="panel import-panel">
              <div className="import-icon">⇧</div><div className="import-copy"><h2>Bring in your evidence</h2><p>Import deletion requests and supporting CSV records to start the review.</p><button className="text-button" onClick={() => setImportOpen(true)}>Import dataset <span>→</span></button></div>
              <div className="source-count"><strong>{dashboard.sourceFiles}</strong><span>CSV sources</span></div>
            </div>
          </section>

          <WorkspaceDataSummary key={datasetVersion} />

          <section className="panel cases-panel" id="cases">
            <div className="cases-heading">
              <div><div className="title-inline"><h2>Deletion requests</h2><span className="count-chip">{dashboard.totalCases}</span></div><p>Review the reported outcome against the available evidence.</p></div>
              <div className="table-actions">
                <div className="search-box"><span>⌕</span><input aria-label="Search cases" placeholder="Search ID or email..." value={search} onChange={(event) => { setSearch(event.target.value); setPage(0); }} /></div>
                <select aria-label="Filter cases by audit outcome" value={statusFilter} onChange={(event) => { setStatusFilter(event.target.value); setPage(0); }}>
                  <option value="ALL">All audit outcomes</option><option value="PENDING">Awaiting review</option><option value="COMPLETE">Complete</option><option value="INCOMPLETE">Incomplete</option><option value="NOT_APPLICABLE">Not applicable</option>
                </select>
              </div>
            </div>
            <CaseTable
              cases={cases}
              onOpen={openCase}
              emptyMessage={emptyCaseMessage(statusFilter, search, dashboard.totalCases)}
              onClearFilters={() => { setSearch(""); setStatusFilter("ALL"); setPage(0); }}
              canClearFilters={statusFilter !== "ALL" || search.trim().length > 0}
            />
            <div className="pagination">
              <span>Showing {cases.length ? page * 20 + 1 : 0}–{page * 20 + cases.length} of {matchingCases}</span>
              <div><button disabled={page <= 0} onClick={() => setPage((previous) => previous - 1)}>← Previous</button><span>Page {totalPages ? page + 1 : 0} of {totalPages}</span><button disabled={page + 1 >= totalPages} onClick={() => setPage((previous) => previous + 1)}>Next →</button></div>
            </div>
          </section>
          </> : <AdminWorkspace key={`${activeView}-${datasetVersion}`} view={activeView} onOpenCase={(caseId) => void openCaseById(caseId)} />}
          <footer className="page-footer"><span>Northstar Trust Operations</span><span>Deletion evidence workspace <b>·</b> v0.1</span></footer>
        </div>
      </main>

      {importOpen && <ImportDialog busy={busy} onClose={() => setImportOpen(false)} onImport={(files) => void importFiles(files)} />}
      {activeCase && <AuditDrawer auditCase={activeCase} busy={busy} onClose={() => setActiveCase(null)} onAnalyze={analyzeCase} onSave={(status, steps) => void saveCase(status, steps)} />}
    </div>
  );
}

function Metric({ label, value, icon, tone, detail }: { label: string; value: number; icon: string; tone: string; detail: string }) {
  return <div className="metric-card"><div className="metric-top"><span>{label}</span><span className={`metric-icon ${tone}`}>{icon}</span></div><strong>{value.toLocaleString()}</strong><small>{detail}</small></div>;
}

function CaseTable({
  cases,
  onOpen,
  emptyMessage,
  onClearFilters,
  canClearFilters,
}: {
  cases: AuditCase[];
  onOpen: (item: AuditCase) => void;
  emptyMessage: string;
  onClearFilters: () => void;
  canClearFilters: boolean;
}) {
  if (!cases.length) {
    return <div className="empty-state"><div className="empty-illustration">⌕</div><strong>No matching requests</strong><span>{emptyMessage}</span>{canClearFilters && <button className="text-button" onClick={onClearFilters}>Clear filters</button>}</div>;
  }
  return <div className="table-scroll"><table><thead><tr><th>CASE</th><th>REQUESTER</th><th>REQUESTED</th><th>REPORTED STATUS</th><th>AUDIT RESULT</th><th /></tr></thead><tbody>
    {cases.map((item) => <tr key={item.caseId} onClick={() => onOpen(item)} tabIndex={0} onKeyDown={(event) => { if (event.key === "Enter") onOpen(item); }}>
      <td><span className="case-id">{item.caseId}</span></td><td><span className="email-cell">{item.email}</span></td><td>{formatDate(item.requestedAt)}</td><td><ReportedStatus value={item.reportedStatus} /></td><td><StatusBadge value={item.deletionStatus} /></td><td><button className="row-action" aria-label={`Review ${item.caseId}`}>→</button></td>
    </tr>)}
  </tbody></table></div>;
}

function emptyCaseMessage(status: string, search: string, totalCases: number) {
  if (totalCases === 0) return "Import deletion_requests.csv to start reviewing cases.";
  if (search.trim()) return "Try a different search or clear the active filters.";
  if (status === "PENDING") return "There are no requests awaiting review.";
  if (status === "COMPLETE") return "No requests have a saved Complete audit outcome yet.";
  if (status === "INCOMPLETE") return "No requests have a saved Incomplete audit outcome yet.";
  if (status === "NOT_APPLICABLE") return "No requests have a saved Not applicable audit outcome yet.";
  return "No requests match the selected filters.";
}

function ReportedStatus({ value }: { value: string | null }) {
  const normalized = value?.toUpperCase() ?? "UNKNOWN";
  const tone = normalized === "COMPLETE" ? "green" : normalized === "NOT_STARTED" ? "gray" : "amber";
  return <span className={`reported-status ${tone}`}><i />{normalized.replace(/_/g, " ")}</span>;
}

function StatusBadge({ value }: { value: AuditStatus | null }) {
  if (!value) return <span className="status-badge pending"><i /> Awaiting review</span>;
  const label = value === "NOT_APPLICABLE" ? "Not applicable" : value[0] + value.slice(1).toLowerCase();
  return <span className={`status-badge ${value.toLowerCase()}`}><i /> {label}</span>;
}

function ImportDialog({ busy, onClose, onImport }: { busy: boolean; onClose: () => void; onImport: (files: File[]) => void }) {
  const [files, setFiles] = useState<File[]>([]);
  const [dragging, setDragging] = useState(false);
  const [localError, setLocalError] = useState("");
  const addFiles = (incoming: FileList | null) => {
    if (!incoming) return;
    const selected = Array.from(incoming);
    const invalid = selected.find((file) => !file.name.toLowerCase().endsWith(".csv"));
    if (invalid) {
      setLocalError(`${invalid.name} is not a CSV file. Import CSV data files only.`);
      return;
    }
    setLocalError("");
    setFiles((previous) => [...new Map([...previous, ...selected].map((file) => [file.name, file])).values()]);
  };
  return <div className="modal-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}><div className="modal import-modal" role="dialog" aria-modal="true" aria-labelledby="import-title">
    <div className="modal-header"><div><span className="modal-kicker">DATASET MANAGEMENT</span><h2 id="import-title">Import audit data</h2><p>Load the case list and supporting CSV evidence into this workspace.</p></div><button className="close-button" onClick={onClose} aria-label="Close">×</button></div>
    <div className={`dropzone ${dragging ? "dragging" : ""}`} onDragOver={(event) => { event.preventDefault(); setDragging(true); }} onDragLeave={() => setDragging(false)} onDrop={(event) => { event.preventDefault(); setDragging(false); addFiles(event.dataTransfer.files); }}>
      <div className="upload-symbol">↑</div><strong>Drop CSV files here</strong><span>or choose files from your computer</span>
      <label className="button secondary file-picker">Browse files<input type="file" accept=".csv,text/csv" multiple onChange={(event: ChangeEvent<HTMLInputElement>) => addFiles(event.target.files)} /></label>
    </div>
    <div className="upload-hint"><span>i</span><div><strong>Required file</strong><p>Include <code>deletion_requests.csv</code>. Add supporting CSV files such as identities, activity logs, and deletion rules.</p></div></div>
    {localError && <div className="inline-error">{localError}</div>}
    {files.length > 0 && <div className="selected-files"><div className="selected-header"><strong>Selected files <span>{files.length}</span></strong><button onClick={() => setFiles([])}>Clear all</button></div>{files.map((file) => <div className="file-row" key={file.name}><span className="file-mark">CSV</span><span>{file.name}</span><small>{formatBytes(file.size)}</small><button aria-label={`Remove ${file.name}`} onClick={() => setFiles((previous) => previous.filter((item) => item.name !== file.name))}>×</button></div>)}</div>}
    <div className="modal-footer"><button className="button secondary" onClick={onClose}>Cancel</button><button className="button primary" disabled={busy || !files.some((file) => file.name.toLowerCase() === "deletion_requests.csv")} onClick={() => onImport(files)}>{busy ? "Importing…" : "Import selected files"}</button></div>
  </div></div>;
}

function AuditDrawer({ auditCase, busy, onClose, onAnalyze, onSave }: { auditCase: AuditCase; busy: boolean; onClose: () => void; onAnalyze: (caseId: string) => Promise<AuditAnalysis | null>; onSave: (status: AuditStatus, steps: AuditStep[]) => void }) {
  const [status, setStatus] = useState<AuditStatus>(auditCase.deletionStatus
    ?? (auditCase.deletionIntent === "NOT_REQUESTED" ? "NOT_APPLICABLE" : "INCOMPLETE"));
  const [steps, setSteps] = useState<AuditStep[]>(auditCase.steps ?? []);
  const [tab, setTab] = useState<"audit" | "request" | "evidence">("audit");
  const [evidenceSearch, setEvidenceSearch] = useState(auditCase.email);
  const [evidence, setEvidence] = useState<SourceRecord[]>([]);
  const [evidenceLoading, setEvidenceLoading] = useState(false);
  const [evidenceError, setEvidenceError] = useState("");
  const [identityResolution, setIdentityResolution] = useState<IdentityResolution | null>(null);
  const [identityLoading, setIdentityLoading] = useState(false);
  const [identityError, setIdentityError] = useState("");
  const [analysis, setAnalysis] = useState<AuditAnalysis | null>(null);
  const visibleSteps = useMemo(() => steps.map((step, index) => ({ ...step, stepNo: index + 1 })), [steps]);

  useEffect(() => {
    setStatus(auditCase.deletionStatus
      ?? (auditCase.deletionIntent === "NOT_REQUESTED" ? "NOT_APPLICABLE" : "INCOMPLETE"));
    setSteps(auditCase.steps ?? []);
    setAnalysis(null);
  }, [auditCase.caseId, auditCase.deletionStatus, auditCase.steps]);

  useEffect(() => {
    let cancelled = false;
    setIdentityLoading(true);
    api.identities(auditCase.caseId)
      .then((result) => { if (!cancelled) setIdentityResolution(result); })
      .catch((cause: unknown) => { if (!cancelled) setIdentityError(cause instanceof Error ? cause.message : "Could not resolve current and historical identifiers."); })
      .finally(() => { if (!cancelled) setIdentityLoading(false); });
    return () => { cancelled = true; };
  }, [auditCase.caseId]);

  const changeStatus = (next: AuditStatus) => {
    setStatus(next);
    if (next === "NOT_APPLICABLE") setSteps([]);
    if (next === "COMPLETE") setSteps((current) => current.filter((step) => step.phase === "VERIFICATION"));
  };

  const addStep = () => {
    const phase: Phase = status === "COMPLETE" ? "VERIFICATION" : visibleSteps[visibleSteps.length - 1]?.phase ?? "FINDING";
    const defaultChoice = operationOptions[phase][0];
    setSteps((current) => [...current, {
      stepNo: current.length + 1,
      phase,
      systemId: "",
      resourceId: "",
      operation: defaultChoice.operation,
      state: defaultChoice.state,
      evidenceId: "",
    }]);
  };

  const updateStep = (index: number, patch: Partial<AuditStep>) => {
    setSteps((current) => current.map((step, stepIndex) => {
      if (stepIndex !== index) return step;
      const updated = { ...step, ...patch };
      if (patch.phase && patch.phase !== step.phase) {
        const selected = operationOptions[patch.phase][0];
        updated.operation = selected.operation;
        updated.state = selected.state;
      }
      if (patch.operation) {
        const selected = operationOptions[updated.phase].find((item) => item.operation === patch.operation);
        if (selected) updated.state = selected.state;
      }
      return updated;
    }));
  };

  const searchEvidence = async (query: string) => {
    setEvidenceLoading(true);
    setEvidenceError("");
    try {
      setEvidence(await api.searchEvidence(query));
    } catch (cause) {
      setEvidenceError(cause instanceof Error ? cause.message : "Could not search imported evidence.");
    } finally {
      setEvidenceLoading(false);
    }
  };

  const inspectIdentifier = (value: string) => {
    setEvidenceSearch(value);
    setTab("evidence");
    void searchEvidence(value);
  };

  const runAnalysis = async () => {
    const result = await onAnalyze(auditCase.caseId);
    if (!result) return;
    setStatus(result.audit.deletionStatus);
    setSteps(result.audit.steps);
    setAnalysis(result);
    setTab("audit");
  };

  return <div className="drawer-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}><aside className="audit-drawer" role="dialog" aria-modal="true" aria-labelledby="audit-title">
    <div className="drawer-top"><div><span className="modal-kicker">DELETION REQUEST</span><div className="drawer-idline"><h2 id="audit-title">{auditCase.caseId}</h2><StatusBadge value={auditCase.deletionStatus} /></div></div><button className="close-button" onClick={onClose} aria-label="Close">×</button></div>
    <div className="drawer-person"><div className="person-avatar">{auditCase.email.slice(0, 1).toUpperCase()}</div><div><strong>{auditCase.email}</strong><span>Requested {formatDate(auditCase.requestedAt)}</span></div></div>
    <div className="drawer-tabs"><button className={tab === "audit" ? "active" : ""} onClick={() => setTab("audit")}>Audit review</button><button className={tab === "evidence" ? "active" : ""} onClick={() => setTab("evidence")}>Source evidence</button><button className={tab === "request" ? "active" : ""} onClick={() => setTab("request")}>Request details</button></div>
    {tab === "request" ? <div className="request-details"><Detail label="Case ID" value={auditCase.caseId} /><Detail label="Requested at" value={formatDate(auditCase.requestedAt)} /><Detail label="Workflow claim" value={auditCase.reportedStatus ?? "Not reported"} /><Detail label="Claim reported at" value={formatDate(auditCase.reportedAt ?? null)} />
      <section className="identity-trail"><header><div><strong>Resolved identifiers</strong><span>Current profile and historical changes linked to this request email.</span></div><span className={`identity-load-state ${identityLoading ? "loading" : ""}`}>{identityLoading ? "Resolving…" : identityResolution ? `${identityResolution.identities.length} account(s)` : ""}</span></header>
        {identityError && <p className="identity-error">{identityError}</p>}
        {!identityLoading && identityResolution && identityResolution.identities.length === 0 && <p className="identity-empty">No matching current or historical identity was found in the imported identity records.</p>}
        {identityResolution?.identities.map((identity) => <div className="resolved-user" key={identity.userId}><button className="user-id-chip" onClick={() => inspectIdentifier(identity.userId)}>user_id · {identity.userId} <span>Inspect</span></button><div className="identifier-chips">{identity.identifiers.map((identifier) => <button key={`${identity.userId}-${identifier.field}-${identifier.source}-${identifier.value}`} className="identifier-chip" title={`${identifier.source.replace(/_/g, " ")}${identifier.eventTime ? ` · ${identifier.eventTime}` : ""}`} onClick={() => inspectIdentifier(identifier.value)}><strong>{identifier.field.replace(/_/g, " ")}</strong><span>{identifier.value}</span><i>{identifier.source === "CURRENT_IDENTITY" ? "current" : "history"}</i></button>)}</div></div>)}
      </section>
      <div className="request-intent"><span>REQUEST INTENT</span><strong className={auditCase.deletionIntent?.toLowerCase() ?? "review_required"}>{(auditCase.deletionIntent ?? "REVIEW_REQUIRED").replace(/_/g, " ")}</strong><p>{auditCase.intentExplanation ?? "Review the request wording before deciding whether deletion is authorized."}</p></div>
      <div className="request-text"><span>Request text</span><p>{auditCase.requestText || "No request text supplied."}</p></div><div className="claim-warning"><span>!</span><p>The reported status is a claim from the existing workflow, not proof of deletion.</p></div></div> : tab === "evidence" ? <div className="request-details evidence-panel">
      <div className="evidence-intro"><strong>Search uploaded CSV evidence</strong><span>Search current or historical identifiers. Results are source rows to inspect, not automatic proof.</span></div>
      <form className="evidence-search" onSubmit={(event) => { event.preventDefault(); void searchEvidence(evidenceSearch); }}>
        <input value={evidenceSearch} onChange={(event) => setEvidenceSearch(event.target.value)} placeholder="Email, user ID, or another identifier" aria-label="Search source evidence" />
        <button className="button primary" disabled={evidenceLoading || evidenceSearch.trim().length < 3}>{evidenceLoading ? "Searching…" : "Search"}</button>
      </form>
      {evidenceError && <div className="inline-error">{evidenceError}</div>}
      {!evidence.length && !evidenceLoading ? <div className="evidence-empty">Search imported CSV rows using an identity value to find possible evidence.</div> :
        <div className="evidence-results">{evidence.map((record) => <article className="evidence-record" key={record.evidenceId}><header><strong>{record.sourceFile}</strong><code>{record.evidenceId}</code></header><pre>{formatRecord(record.content)}</pre><button onClick={() => { navigator.clipboard?.writeText(record.evidenceId); }}>Copy evidence ID</button></article>)}</div>}
    </div> : <div className="drawer-body">
      <div className="review-note"><span>✦</span><p>Use the supplied evidence to determine the outcome. This workspace records your audit; it does not execute deletion operations.</p></div>
      <div className="ai-analysis-controls"><div><strong>AI evidence analysis</strong><span>Matching request data and evidence excerpts are sent to OpenAI. Review the proposed result before saving.</span></div><button className="button secondary" disabled={busy} onClick={() => void runAnalysis()}>{busy ? "Analyzing…" : "Analyze with AI"}</button></div>
      {analysis && <div className="ai-analysis-result"><strong>Analysis rationale</strong><p>{analysis.rationale}</p>{analysis.caveats.length > 0 && <><strong>Evidence limitations</strong><ul>{analysis.caveats.map((caveat, index) => <li key={index}>{caveat}</li>)}</ul></>}</div>}
      <label className="field-label" htmlFor="audit-status">AUDIT OUTCOME</label>
      <select className="status-select" id="audit-status" value={status} onChange={(event) => changeStatus(event.target.value as AuditStatus)}>
        <option value="INCOMPLETE">Incomplete — remediation required</option><option value="COMPLETE">Complete — verification only</option><option value="NOT_APPLICABLE" disabled={auditCase.deletionIntent !== "NOT_REQUESTED"}>Not applicable — no deletion requested</option>
      </select>
      {auditCase.deletionIntent === "NOT_REQUESTED" && <div className="not-applicable-note"><strong>Request text says deletion was not requested</strong><span>This case must be saved as NOT_APPLICABLE with an empty steps array.</span></div>}
      {auditCase.deletionIntent === "REVIEW_REQUIRED" && <div className="phase-reminder"><strong>Intent needs review:</strong> the request text does not clearly authorize deletion; confirm it before saving a deletion audit.</div>}
      {status === "NOT_APPLICABLE" ? <div className="not-applicable-note"><strong>No deletion audit will be exported</strong><span>Not-applicable cases must have an empty steps array.</span></div> : <>
        <div className="steps-heading"><div><h3>Audit steps</h3><p>Order findings, remediation, then verification.</p></div><span>{steps.length} {steps.length === 1 ? "step" : "steps"}</span></div>
        <div className="steps-list">{visibleSteps.map((step, index) => <StepEditor key={index} step={step} index={index} canRemove={steps.length > 1} onChange={updateStep} onRemove={() => setSteps((current) => current.filter((_, currentIndex) => currentIndex !== index))} />)}</div>
        <button className="add-step-button" onClick={addStep}>＋ Add audit step</button>
        {status === "INCOMPLETE" && <div className="phase-reminder"><strong>Required for incomplete:</strong> include at least one finding, remediation action, and verification check.</div>}
      </>}
    </div>}
    <div className="drawer-footer"><button className="button secondary" onClick={onClose}>Cancel</button>{tab === "audit" && <button className="button primary" disabled={busy} onClick={() => onSave(status, visibleSteps)}>{busy ? "Saving…" : "Save audit decision"}</button>}</div>
  </aside></div>;
}

function StepEditor({ step, index, canRemove, onChange, onRemove }: { step: AuditStep; index: number; canRemove: boolean; onChange: (index: number, patch: Partial<AuditStep>) => void; onRemove: () => void }) {
  return <div className="step-card">
    <div className="step-card-heading"><span className="step-number">{String(index + 1).padStart(2, "0")}</span><select aria-label={`Phase for step ${index + 1}`} value={step.phase} onChange={(event) => onChange(index, { phase: event.target.value as Phase })}>{phaseOptions.map((phase) => <option key={phase} value={phase}>{phase}</option>)}</select><button aria-label="Remove step" disabled={!canRemove} onClick={onRemove}>×</button></div>
    <label>OPERATION<select value={step.operation} onChange={(event) => onChange(index, { operation: event.target.value })}>{operationOptions[step.phase].map((option) => <option key={option.operation} value={option.operation}>{option.operation}</option>)}</select></label>
    <div className="step-fields"><label>SYSTEM ID<input value={step.systemId} placeholder="e.g. account_service" onChange={(event) => onChange(index, { systemId: event.target.value })} /></label><label>RESOURCE ID<input value={step.resourceId} placeholder="Record or process" onChange={(event) => onChange(index, { resourceId: event.target.value })} /></label></div>
    <label>EVIDENCE ID<input value={step.evidenceId} placeholder="e.g. evidence_01" onChange={(event) => onChange(index, { evidenceId: event.target.value })} /></label>
    <div className="step-state"><span>EXPECTED STATE</span><strong>{step.state}</strong></div>
  </div>;
}

function Detail({ label, value }: { label: string; value: string }) {
  return <div className="detail-row"><span>{label}</span><strong>{value}</strong></div>;
}

function formatDate(value: string | null) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat("en", { dateStyle: "medium", timeZone: "UTC" }).format(date);
}

function formatBytes(bytes: number) {
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function formatRecord(content: string) {
  try {
    return JSON.stringify(JSON.parse(content), null, 2);
  } catch {
    return content;
  }
}

export default App;
