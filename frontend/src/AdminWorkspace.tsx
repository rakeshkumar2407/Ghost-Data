import { useEffect, useMemo, useState } from "react";
import { AdminCustomerDetail, AdminOverview, AdminSourceRow, AdminTableSummary, api, PageResult } from "./api";

type AdminView = "customers" | "data" | "activity";

const pageLabels: Record<AdminView, { title: string; description: string; table?: string }> = {
  customers: {
    title: "Customer operations",
    description: "Review imported customer profiles, identity changes, linked privacy requests, and matching system activity.",
    table: "user_identities.csv",
  },
  data: {
    title: "Data sources",
    description: "Browse every imported database table and row. Search is performed against the local PostgreSQL workspace.",
  },
  activity: {
    title: "System activity",
    description: "Inspect recorded operations across account, search, analytics, export, support, and storage systems.",
    table: "system_activity.csv",
  },
};

export function AdminWorkspace({
  view,
  onOpenCase,
}: {
  view: AdminView;
  onOpenCase: (caseId: string) => void;
}) {
  const [tables, setTables] = useState<AdminTableSummary[]>([]);
  const [selectedTable, setSelectedTable] = useState("");
  const [rows, setRows] = useState<AdminSourceRow[]>([]);
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalRows, setTotalRows] = useState(0);
  const [selectedCustomer, setSelectedCustomer] = useState("");
  const [customer, setCustomer] = useState<AdminCustomerDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    let cancelled = false;
    api.adminTables()
      .then((sourceTables) => {
        if (cancelled) return;
        setTables(sourceTables);
        const preferred = pageLabels[view].table;
        setSelectedTable(preferred && sourceTables.some((table) => table.sourceFile === preferred)
          ? preferred
          : sourceTables[0]?.sourceFile ?? "");
      })
      .catch((cause: unknown) => {
        if (!cancelled) {
          setError(cause instanceof Error ? cause.message : "Could not load the imported admin data.");
          setLoading(false);
        }
      });
    return () => { cancelled = true; };
  }, [view]);

  useEffect(() => {
    if (!selectedTable || (search.trim().length === 1)) {
      setRows([]);
      setTotalRows(0);
      setTotalPages(0);
      setLoading(false);
      return;
    }
    let cancelled = false;
    const timer = window.setTimeout(() => {
      setLoading(true);
      setError("");
      api.adminTableRows(selectedTable, search.trim(), page)
        .then((result: PageResult<AdminSourceRow>) => {
          if (cancelled) return;
          setRows(result.content);
          setTotalRows(result.totalElements);
          setTotalPages(result.totalPages);
        })
        .catch((cause: unknown) => {
          if (!cancelled) setError(cause instanceof Error ? cause.message : "Could not load this imported table.");
        })
        .finally(() => { if (!cancelled) setLoading(false); });
    }, 180);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [selectedTable, search, page]);

  useEffect(() => {
    if (!selectedCustomer) {
      setCustomer(null);
      return;
    }
    let cancelled = false;
    setCustomer(null);
    api.adminCustomer(selectedCustomer)
      .then((result) => { if (!cancelled) setCustomer(result); })
      .catch((cause: unknown) => {
        if (!cancelled) setError(cause instanceof Error ? cause.message : "Could not load this customer profile.");
      });
    return () => { cancelled = true; };
  }, [selectedCustomer]);

  const selectedSummary = useMemo(
    () => tables.find((table) => table.sourceFile === selectedTable),
    [selectedTable, tables],
  );
  const label = pageLabels[view];
  const headers = rows[0] ? Object.keys(rows[0].data) : [];

  return <div className="admin-workspace">
    <section className="admin-page-intro">
      <div><span className="admin-kicker">NORTHSTAR SHOP · ADMIN CONSOLE</span><h2>{label.title}</h2><p>{label.description}</p></div>
      <span className="admin-data-badge"><i /> LIVE LOCAL DATA</span>
    </section>

    {error && <div className="alert error-alert"><span>!</span><div>{error}</div><button onClick={() => setError("")}>×</button></div>}

    {view === "customers" && <section className="admin-record-panel">
      <div className="admin-table-heading">
        <div><div className="title-inline"><h3>Customer directory</h3><span className="count-chip">{selectedSummary?.rowCount ?? 0}</span></div><p>Current identities from the bundled customer database.</p></div>
        <div className="search-box"><span>⌕</span><input aria-label="Search customers" placeholder="Search name, email, or user ID…" value={search} onChange={(event) => { setSearch(event.target.value); setPage(0); }} /></div>
      </div>
      <DataTable rows={rows} headers={headers} loading={loading} emptyText="No matching customer records." onCustomerSelect={(row) => setSelectedCustomer(String(row.data.user_id ?? ""))} selectedCustomer={selectedCustomer} />
      <Pagination page={page} totalPages={totalPages} totalRows={totalRows} onPage={setPage} />
    </section>}

    {view === "customers" && customer && <CustomerDetails detail={customer} onOpenCase={onOpenCase} />}

    {(view === "data" || view === "activity") && <>
      <section className="admin-table-cards" aria-label="Imported data table counts">
        {tables.map((table) => <button key={table.sourceFile} className={`admin-table-card ${selectedTable === table.sourceFile ? "active" : ""}`} onClick={() => { setSelectedTable(table.sourceFile); setPage(0); setSearch(""); }}>
          <span>{table.category}</span><strong>{table.rowCount.toLocaleString()}</strong><small>{table.sourceFile}</small>
        </button>)}
      </section>
      <section className="admin-record-panel">
        <div className="admin-table-heading">
          <div><div className="title-inline"><h3>{view === "activity" ? "Recorded system events" : selectedSummary?.category ?? "Imported records"}</h3><span className="count-chip">{selectedSummary?.rowCount ?? totalRows}</span></div><p>{view === "activity" ? "Events are shown as recorded; this workspace does not invent orders, tickets, or customer actions." : selectedTable}</p></div>
          <div className="search-box"><span>⌕</span><input aria-label="Search imported records" placeholder="Search rows…" value={search} onChange={(event) => { setSearch(event.target.value); setPage(0); }} /></div>
        </div>
        <DataTable rows={rows} headers={headers} loading={loading} emptyText={search.length === 1 ? "Enter one more character to search." : "No records match this search."} />
        <Pagination page={page} totalPages={totalPages} totalRows={totalRows} onPage={setPage} />
      </section>
    </>}

    {view === "customers" && selectedCustomer && !customer && !error && <div className="admin-loading">Loading customer profile…</div>}

  </div>;
}

export function WorkspaceDataSummary() {
  const [summary, setSummary] = useState<AdminOverview | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    let cancelled = false;
    api.adminOverview()
      .then((result) => { if (!cancelled) setSummary(result); })
      .catch((cause: unknown) => {
        if (!cancelled) setError(cause instanceof Error ? cause.message : "Could not load the database summary.");
      });
    return () => { cancelled = true; };
  }, []);

  const metrics = [
    ["Customer profiles", summary?.customerProfiles],
    ["Identity changes", summary?.identityChanges],
    ["System activity rows", summary?.systemActivities],
    ["Imported data tables", summary?.dataTables],
  ] as const;

  return <section className="workspace-data-strip" aria-label="Database overview">
    {metrics.map(([label, value]) => <div key={label}><strong>{value == null ? "—" : value.toLocaleString()}</strong><span>{label}</span></div>)}
    {error && <span className="admin-summary-error">{error}</span>}
  </section>;
}

function CustomerDetails({ detail, onOpenCase }: { detail: AdminCustomerDetail; onOpenCase: (caseId: string) => void }) {
  const profile = detail.profile;
  return <section className="admin-customer-detail">
    <div className="admin-customer-heading">
      <div className="admin-customer-avatar">{String(profile.full_name ?? profile.name ?? profile.email ?? "C").slice(0, 1).toUpperCase()}</div>
      <div><span className="admin-kicker">CUSTOMER PROFILE</span><h3>{String(profile.full_name ?? profile.name ?? profile.email ?? profile.user_id ?? "Customer")}</h3><p>{String(profile.email ?? "")} · {String(profile.user_id ?? "")}</p></div>
      <span className={`customer-status ${String(profile.account_status ?? "UNKNOWN").toLowerCase()}`}>{String(profile.account_status ?? "STATUS UNKNOWN")}</span>
    </div>
    <div className="admin-detail-grid">
      <DetailTable title="Profile attributes" rows={[profile]} />
      <DetailTable title="Identity history" rows={detail.identityHistory} empty="No identity changes are recorded for this customer." />
      <div className="admin-subpanel"><h4>Deletion requests</h4>{detail.deletionRequests.length ? <div className="admin-related-list">{detail.deletionRequests.map((request) => <div key={String(request.case_id)}><span><strong>{String(request.case_id)}</strong><small>{String(request.requested_at ?? "Date unavailable")}</small></span><button className="button secondary" onClick={() => onOpenCase(String(request.case_id))}>Review audit</button></div>)}</div> : <p className="admin-empty">No deletion request matches this customer’s current or historical email.</p>}</div>
      <DetailTable title="Linked system activity" rows={detail.systemActivity} empty="No activity row is directly linked to this user ID." />
    </div>
  </section>;
}

function DetailTable({ title, rows, empty }: { title: string; rows: Record<string, unknown>[]; empty?: string }) {
  const columns = rows[0] ? Object.keys(rows[0]) : [];
  return <div className="admin-subpanel"><h4>{title}</h4>{rows.length ? <div className="admin-detail-rows">{rows.map((row, index) => <dl key={`${title}-${index}`}>{(columns.length ? columns : Object.keys(row)).map((key) => <div key={key}><dt>{key.replace(/_/g, " ")}</dt><dd>{formatValue(row[key])}</dd></div>)}</dl>)}</div> : <p className="admin-empty">{empty ?? "No records available."}</p>}</div>;
}

function DataTable({ rows, headers, loading, emptyText, onCustomerSelect, selectedCustomer }: {
  rows: AdminSourceRow[];
  headers: string[];
  loading: boolean;
  emptyText: string;
  onCustomerSelect?: (row: AdminSourceRow) => void;
  selectedCustomer?: string;
}) {
  if (loading) return <div className="admin-loading">Loading records…</div>;
  if (!rows.length) return <div className="admin-empty admin-table-empty">{emptyText}</div>;
  return <div className="admin-data-scroll"><table className="admin-data-table"><thead><tr><th>ROW</th>{headers.map((header) => <th key={header}>{header.replace(/_/g, " ")}</th>)}{onCustomerSelect && <th />}</tr></thead><tbody>
    {rows.map((row) => <tr key={row.rowNumber} className={selectedCustomer === String(row.data.user_id ?? "") ? "selected" : ""}>
      <td>{row.rowNumber}</td>{headers.map((header) => <td key={header} title={formatValue(row.data[header])}>{formatValue(row.data[header])}</td>)}
      {onCustomerSelect && <td><button className="admin-row-link" onClick={() => onCustomerSelect(row)}>View profile →</button></td>}
    </tr>)}
  </tbody></table></div>;
}

function Pagination({ page, totalPages, totalRows, onPage }: { page: number; totalPages: number; totalRows: number; onPage: (page: number) => void }) {
  return <div className="admin-pagination"><span>{totalRows.toLocaleString()} records · page {totalPages ? page + 1 : 0} of {totalPages}</span><div><button disabled={page === 0} onClick={() => onPage(page - 1)}>← Previous</button><button disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>Next →</button></div></div>;
}

function formatValue(value: unknown): string {
  if (value == null || value === "") return "—";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}
