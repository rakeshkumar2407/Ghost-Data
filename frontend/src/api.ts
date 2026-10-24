export type AuditStatus = "COMPLETE" | "INCOMPLETE" | "NOT_APPLICABLE";
export type Phase = "FINDING" | "REMEDIATION" | "VERIFICATION";

export interface AuditStep {
  stepNo: number;
  phase: Phase;
  systemId: string;
  resourceId: string;
  operation: string;
  state: string;
  evidenceId: string;
}

export interface AuditCase {
  caseId: string;
  email: string;
  requestedAt: string | null;
  reportedStatus: string | null;
  reportedAt?: string | null;
  auditAt?: string | null;
  requestText?: string | null;
  deletionStatus: AuditStatus | null;
  deletionIntent?: "REQUESTED" | "NOT_REQUESTED" | "REVIEW_REQUIRED";
  intentExplanation?: string;
  steps?: AuditStep[];
}

export interface Dashboard {
  totalCases: number;
  pendingCases: number;
  completeCases: number;
  incompleteCases: number;
  notApplicableCases: number;
  sourceFiles: number;
  reviewedPercent: number;
}

export interface SourceRecord {
  sourceFile: string;
  rowNumber: number;
  evidenceId: string;
  content: string;
}

export interface ResolvedIdentifier {
  field: string;
  value: string;
  source: string;
  eventTime: string;
}

export interface ResolvedIdentity {
  userId: string;
  identifiers: ResolvedIdentifier[];
}

export interface IdentityResolution {
  caseId: string;
  requestedEmail: string;
  identities: ResolvedIdentity[];
}

export interface AuditAnalysis {
  audit: {
    deletionStatus: AuditStatus;
    steps: AuditStep[];
  };
  rationale: string;
  caveats: string[];
}

export interface PageResult<T> {
  content: T[];
  totalPages: number;
  totalElements: number;
  number: number;
}

export interface AdminOverview {
  deletionRequests: number;
  pendingAudits: number;
  completedAudits: number;
  incompleteAudits: number;
  notApplicableRequests: number;
  customerProfiles: number;
  identityChanges: number;
  systemActivities: number;
  databaseRows: number;
  dataTables: number;
}

export interface AdminTableSummary {
  sourceFile: string;
  rowCount: number;
  category: string;
}

export interface AdminSourceRow {
  rowNumber: number;
  data: Record<string, unknown>;
}

export interface AdminCustomerDetail {
  profile: Record<string, unknown>;
  identityHistory: Record<string, unknown>[];
  deletionRequests: Record<string, unknown>[];
  systemActivity: Record<string, unknown>[];
}

const BASE_URL = import.meta.env.VITE_API_URL ?? "/api";

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(`${BASE_URL}${url}`, options);
  if (!response.ok) {
    const body = await response.json().catch(() => ({ error: response.statusText }));
    throw new Error(body.error ?? `Request failed (${response.status})`);
  }
  return response.json() as Promise<T>;
}

export const api = {
  dashboard: () => request<Dashboard>("/dashboard"),
  adminOverview: () => request<AdminOverview>("/admin/overview"),
  adminTables: () => request<AdminTableSummary[]>("/admin/tables"),
  adminTableRows: (sourceFile: string, search = "", page = 0, size = 20) =>
    request<PageResult<AdminSourceRow>>(`/admin/tables/${encodeURIComponent(sourceFile)}?search=${encodeURIComponent(search)}&page=${page}&size=${size}`),
  adminCustomer: (userId: string) => request<AdminCustomerDetail>(`/admin/customers/${encodeURIComponent(userId)}`),
  cases: (search = "", status = "ALL", page = 0, size = 20) =>
    request<PageResult<AuditCase>>(`/cases?search=${encodeURIComponent(search)}&status=${status}&page=${page}&size=${size}`),
  caseDetail: (caseId: string) => request<AuditCase>(`/cases/${encodeURIComponent(caseId)}`),
  analyzeCase: (caseId: string) =>
    request<AuditAnalysis>(`/cases/${encodeURIComponent(caseId)}/analyze`, { method: "POST" }),
  identities: (caseId: string) => request<IdentityResolution>(`/cases/${encodeURIComponent(caseId)}/identities`),
  searchEvidence: (search: string) =>
    request<SourceRecord[]>(`/source-records?search=${encodeURIComponent(search)}`),
  saveAudit: (caseId: string, deletionStatus: AuditStatus, steps: AuditStep[]) =>
    request<AuditCase>(`/cases/${encodeURIComponent(caseId)}/audit`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deletionStatus, steps }),
    }),
  importFiles: async (files: File[]) => {
    const form = new FormData();
    files.forEach((file) => form.append("files", file));
    return request<{ casesImported: number; csvFilesImported: number; sourceRowsImported: number }>("/import", {
      method: "POST",
      body: form,
    });
  },
  exportCsv: async () => {
    const response = await fetch(`${BASE_URL}/export`);
    if (!response.ok) {
      const body = await response.json().catch(() => ({ error: response.statusText }));
      throw new Error(body.error ?? `Export failed (${response.status})`);
    }
    return response.blob();
  },
};
